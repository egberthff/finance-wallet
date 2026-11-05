package ph.financewallet.data.ledger

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.id.UuidV7Generator
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Paged transaction list (TXN-10 list, NFR-03): keyset pages, no gaps or duplicates, display signs. */
@RunWith(RobolectricTestRunner::class)
class TransactionQueriesTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: LedgerService
    private lateinit var queries: TransactionQueries
    private val clock = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneOffset.UTC)
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "test-device")
    private val php = CurrencyCode.PHP
    private val nov1 = LocalDate.of(2026, 11, 1)
    private var keySeq = 0

    private fun p(minor: Long) = Money(minor, php)

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            ledger = LedgerService(db, ctx)
            queries = TransactionQueries(db)
            ledger.ensureSystemAccounts()
        }

    @After
    fun tearDown() = db.close()

    private suspend fun account(name: String) =
        ledger.createAccount(NewAccount(name, FinancialAccountType.EWALLET, php, nov1, p(0))).getOrThrow()

    @Test
    fun `pages walk the whole history newest first without gaps or duplicates`() =
        runBlocking<Unit> {
            val gcash = account("GCash")
            val food = ledger.createCategory("Food", AccountClass.EXPENSE).getOrThrow()
            repeat(120) { i ->
                ledger
                    .record(TxnHeader(nov1.plusDays((i % 7).toLong()), "k${++keySeq}")) { t, _ ->
                        t.expense(gcash, listOf(Split(food, p(100L + i))))
                    }.getOrThrow()
            }
            val seen = mutableListOf<TransactionListItem>()
            var cursor: PageCursor? = null
            var pages = 0
            do {
                val page = queries.page(cursor, limit = 50)
                seen += page.items
                cursor = page.next
                pages++
            } while (cursor != null)
            assertEquals(3, pages)
            assertEquals(120, seen.size)
            assertEquals(120, seen.map { it.id }.toSet().size)
            assertEquals(seen.sortedWith(compareByDescending<TransactionListItem> { it.date }.thenByDescending { it.id }), seen)
            assertEquals(listOf("Food"), seen.first().categoryNames)
            assertEquals(true, seen.all { it.amount.isNegative && it.accountName == "GCash" })
        }

    @Test
    fun `transfers show the amount moved, or the signed side when filtered by account, deleted rows are hidden`() =
        runBlocking<Unit> {
            val gcash = account("GCash")
            val bpi = account("BPI")
            ledger.record(TxnHeader(nov1, "open")) { t, _ -> t.balanceAdjustment(gcash, p(500_000)) }.getOrThrow()
            val transfer = ledger.record(TxnHeader(nov1.plusDays(1), "tr")) { t, _ -> t.transfer(gcash, bpi, p(200_000)) }.getOrThrow()
            val deleted = ledger.record(TxnHeader(nov1.plusDays(2), "del")) { t, _ -> t.transfer(gcash, bpi, p(1)) }.getOrThrow()
            ledger.delete(deleted).getOrThrow()

            val all = queries.page().items
            assertEquals(listOf(transfer, all.last().id), all.map { it.id })
            val moved = all.first()
            assertEquals(TxnType.TRANSFER, moved.type)
            assertEquals(p(200_000) to ("GCash" to "BPI"), moved.amount to (moved.accountName to moved.counterpartyName))

            val bpiView = queries.page(accountId = bpi).items.single()
            assertEquals(p(200_000), bpiView.amount)
            val gcashView = queries.page(accountId = gcash).items.first()
            assertEquals(p(-200_000), gcashView.amount)
            assertNull(queries.page(accountId = gcash).next)
        }
}
