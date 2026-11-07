package ph.financewallet.data.ledger

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.data.account.CategoryService
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.id.UuidV7Generator
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Remembered entry defaults and the picker's "Recent" categories (ANALYSIS.md blueprint E). */
@RunWith(RobolectricTestRunner::class)
class EntryPreferencesTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: LedgerService
    private lateinit var prefs: EntryPreferences
    private lateinit var categories: CategoryService
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "test-device")
    private val php = CurrencyCode.PHP
    private val today = LocalDate.of(2026, 11, 20)
    private var keySeq = 0

    private fun p(minor: Long) = Money(minor, php)

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            ledger = LedgerService(db, ctx)
            prefs = EntryPreferences(db)
            categories = CategoryService(db, ctx)
            ledger.ensureSystemAccounts()
        }

    @After
    fun tearDown() = db.close()

    private suspend fun spend(
        account: String,
        category: String,
        date: LocalDate,
    ) = ledger.record(TxnHeader(date, "k${++keySeq}")) { t, _ -> t.expense(account, listOf(Split(category, p(100)))) }.getOrThrow()

    @Test
    fun `last account and last category per kind are remembered`() =
        runBlocking<Unit> {
            assertNull(prefs.lastAccount())
            prefs.remember("acc-1", AccountClass.EXPENSE, "food").getOrThrow()
            prefs.remember("acc-2", AccountClass.INCOME, "salary").getOrThrow()
            prefs.remember("acc-3", null, null).getOrThrow() // a transfer: account only
            assertEquals("acc-3", prefs.lastAccount())
            assertEquals("food", prefs.lastCategory(AccountClass.EXPENSE))
            assertEquals("salary", prefs.lastCategory(AccountClass.INCOME))
        }

    @Test
    fun `recent categories are the most used in the last 60 days, live only`() =
        runBlocking<Unit> {
            val gcash =
                ledger
                    .createAccount(
                        NewAccount("GCash", FinancialAccountType.EWALLET, php, LocalDate.of(2026, 1, 1), p(1_000_000)),
                    ).getOrThrow()
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            val fare = categories.create("Fare", AccountClass.EXPENSE).getOrThrow()
            val old = categories.create("Old", AccountClass.EXPENSE).getOrThrow()
            val gone = categories.create("Gone", AccountClass.EXPENSE).getOrThrow()
            repeat(3) { spend(gcash, fare, today.minusDays(1)) }
            repeat(2) { spend(gcash, food, today.minusDays(59)) }
            repeat(5) { spend(gcash, old, today.minusDays(61)) } // outside the window
            spend(gcash, gone, today)
            categories.archive(gone).getOrThrow() // archived: never offered as recent

            assertEquals(listOf(fare, food), prefs.recentCategories(AccountClass.EXPENSE, today))
            assertEquals(emptyList<String>(), prefs.recentCategories(AccountClass.INCOME, today))
        }

    /** NFR: the "Recent" query on 50,000 transactions uses the date index and stays fast. */
    @Test
    fun `recent categories on 50k transactions use the date index`() =
        runBlocking<Unit> {
            val gcash =
                ledger
                    .createAccount(
                        NewAccount("GCash", FinancialAccountType.EWALLET, php, LocalDate.of(2020, 1, 1), p(0)),
                    ).getOrThrow()
            val cats = (1..20).map { categories.create("C$it", AccountClass.EXPENSE).getOrThrow() }
            val w = db.openHelper.writableDatabase
            w.beginTransaction()
            try {
                val txn =
                    w.compileStatement(
                        "INSERT INTO txn (id, type, status, txn_date, source, idempotency_key, " +
                            "created_at, updated_at, updated_by_device, hlc) " +
                            "VALUES (?, 'EXPENSE', 'POSTED', ?, 'MANUAL', ?, 0, 0, 'test', '0')",
                    )
                val entry =
                    w.compileStatement(
                        "INSERT INTO entry (id, txn_id, line_no, ledger_account_id, amount_minor, currency) VALUES (?, ?, ?, ?, ?, 'PHP')",
                    )
                val start = LocalDate.of(2020, 1, 1)
                for (i in 0 until FIXTURE) {
                    val id = "t%06d".format(i)
                    txn.bindString(1, id)
                    txn.bindString(2, start.plusDays((i % DAYS).toLong()).toString())
                    txn.bindString(3, "fx$i")
                    txn.executeInsert()
                    listOf(gcash to -100L, cats[i % cats.size] to 100L).forEachIndexed { line, (account, amount) ->
                        entry.bindString(1, "$id-$line")
                        entry.bindString(2, id)
                        entry.bindLong(3, line.toLong())
                        entry.bindString(4, account)
                        entry.bindLong(5, amount)
                        entry.executeInsert()
                    }
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
            val plan =
                w
                    .query(
                        "EXPLAIN QUERY PLAN SELECT e.ledger_account_id FROM txn t JOIN entry e ON e.txn_id = t.id " +
                            "WHERE t.txn_date >= '2026-01-01' AND t.deleted_at IS NULL",
                    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }
            assertTrue("plan: $plan", plan.any { it.contains("ix_txn_date") })

            prefs.recentCategories(AccountClass.EXPENSE, today) // warm-up
            val started = System.nanoTime()
            val recent = prefs.recentCategories(AccountClass.EXPENSE, today)
            val millis = (System.nanoTime() - started) / NANOS_PER_MS
            println("recentCategories on $FIXTURE transactions: $millis ms")
            assertEquals(EntryPreferences.RECENT_LIMIT, recent.size)
            assertTrue("took $millis ms", millis < BUDGET_MS)
        }

    private companion object {
        const val FIXTURE = 50_000
        const val DAYS = 2_500
        const val NANOS_PER_MS = 1_000_000L
        const val BUDGET_MS = 500L
    }
}
