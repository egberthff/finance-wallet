package ph.financewallet.data.backup

import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.data.advanced.AssetKind
import ph.financewallet.data.advanced.AssetService
import ph.financewallet.data.advanced.DebtKind
import ph.financewallet.data.advanced.DebtService
import ph.financewallet.data.advanced.NewDebt
import ph.financewallet.data.advanced.RecurringService
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.advanced.RuleSpec
import ph.financewallet.data.budget.AllocationService
import ph.financewallet.data.budget.BudgetCategorySpec
import ph.financewallet.data.budget.BudgetService
import ph.financewallet.data.budget.BudgetSettings
import ph.financewallet.data.budget.GoalDetails
import ph.financewallet.data.budget.GoalService
import ph.financewallet.data.budget.NewBudget
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.imports.ImportService
import ph.financewallet.data.ledger.FaultInjector
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.allocation.Bucket
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.id.UuidV7Generator
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** UC-29/30 against Room (AC-BAK-1/2/4): full round trip, rejection without damage, atomic restore. */
@RunWith(RobolectricTestRunner::class)
class BackupServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneOffset.UTC)
    private val php = CurrencyCode.PHP
    private val today = LocalDate.of(2026, 11, 6)
    private val fastKdf = Kdf.Argon2id(memoryKiB = 8_192, iterations = 1)
    private val pass = "mango season in May".toCharArray()
    private val opened = mutableListOf<FinanceDatabase>()

    private fun p(minor: Long) = Money(minor, php)

    private fun newDb() =
        FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true).also {
            opened +=
                it
        }

    private fun ctx(device: String) = WriteContext(clock, UuidV7Generator(clock), deviceId = device)

    private lateinit var source: FinanceDatabase

    @Before
    fun setUp() =
        runBlocking<Unit> {
            source = newDb()
            populate(source, ctx("phone-a"))
        }

    @After
    fun tearDown() = opened.forEach { it.close() }

    /** A realistic database touching every table: ledger, budgets, goals, debts, assets, bills, import, rules, audit. */
    // Test fixture: one step per table family; splitting it would only scatter the scenario.
    @Suppress("LongMethod")
    private suspend fun populate(
        db: FinanceDatabase,
        c: WriteContext,
    ) {
        val ledger = LedgerService(db, c).also { it.ensureSystemAccounts() }
        val bpi =
            ledger
                .createAccount(
                    NewAccount("BPI", FinancialAccountType.BANK, php, LocalDate.of(2026, 10, 1), p(5_000_000)),
                ).getOrThrow()
        val gcash =
            ledger
                .createAccount(
                    NewAccount("GCash \"main\"", FinancialAccountType.EWALLET, php, LocalDate.of(2026, 10, 1), p(100_000)),
                ).getOrThrow()
        val food = ledger.createCategory("Food", AccountClass.EXPENSE).getOrThrow()
        ledger.createCategory("Groceries", AccountClass.EXPENSE, food).getOrThrow()
        val salary = ledger.createCategory("Salary", AccountClass.INCOME).getOrThrow()
        ledger
            .record(
                TxnHeader(LocalDate.of(2026, 11, 1), "s1"),
            ) { t, _ -> t.income(bpi, listOf(Split(salary, p(3_000_000)))) }
            .getOrThrow()
        val lunch =
            ledger
                .record(TxnHeader(LocalDate.of(2026, 11, 2), "l1", description = "Jollibee, SM \"North\"\nnew line")) { t, _ ->
                    t.expense(gcash, listOf(Split(food, p(45_050))))
                }.getOrThrow()
        ledger.record(TxnHeader(today, "r1")) { t, r -> t.refund(gcash, p(5_000), food, r.refundOriginal(lunch)) }.getOrThrow()
        ledger.record(TxnHeader(today, "t1")) { t, _ -> t.transfer(bpi, gcash, p(200_000)) }.getOrThrow()
        val goals = GoalService(db, c, ledger)
        val laptop = goals.createGoal(php, GoalDetails("Laptop", p(5_000_000))).getOrThrow()
        val budgets = BudgetService(db, c)
        val budget =
            budgets
                .createBudget(
                    NewBudget(
                        php,
                        PeriodRule.Monthly(LocalDate.of(2026, 11, 1), 1),
                        BudgetSettings("Personal", IncomeMode.TRACKED, null, listOf(BudgetCategorySpec(food))),
                    ),
                ).getOrThrow()
        val period = budgets.ensurePeriod(budget, today).getOrThrow()
        AllocationService(db, c).allocate(period.id, food, p(500_000), "a1").getOrThrow()
        goals.contribute(Bucket.Unallocated(period.id), laptop, p(300_000), "g1").getOrThrow()
        DebtService(
            db,
            c,
            ledger,
        ).create(
            NewDebt("Card", DebtKind.CREDIT_CARD, php, p(250_000), today, currentlyOwed = p(250_000), idempotencyKey = "d1"),
        ).getOrThrow()
        val assets = AssetService(db, c)
        assets.addValuation(assets.create("Car", AssetKind.VEHICLE, php).getOrThrow(), today, p(60_000_000)).getOrThrow()
        val recurring = RecurringService(db, c, ledger)
        recurring
            .createRule(
                RuleSpec(
                    RuleKind.BILL,
                    "Internet",
                    p(169_900),
                    Recurrence.Monthly(LocalDate.of(2026, 10, 1), 15),
                    accountId = gcash,
                    categoryId = food,
                ),
            ).getOrThrow()
        val imports = ImportService(db, c, ledger, { android.util.Xml.newPullParser() })
        val file = imports.read("s.csv", "Date,Description,Amount\n2026-11-03,Grab,-180.00\n".toByteArray())
        imports.commit(imports.preview(gcash, file, checkNotNull(imports.suggest(file).second.mapping))).getOrThrow()
    }

    private suspend fun backupOf(
        db: FinanceDatabase,
        passphrase: CharArray = pass.copyOf(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BackupService(db, ctx("phone-a"), appVersion = 1).create(out, passphrase, fastKdf)
        return out.toByteArray()
    }

    /** All rows of [table] as text, in a stable order. */
    private fun dump(
        db: FinanceDatabase,
        table: String,
    ): List<String> =
        db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "∅" else c.getString(it) })
            }.sorted()
        }

    @Test
    fun `restoring into a new phone reproduces every table and every balance (AC-BAK-1)`() =
        runBlocking<Unit> {
            val bytes = backupOf(source)
            val target = newDb()
            LedgerService(target, ctx("phone-b")).ensureSystemAccounts()
            target.accountDao().setMeta("device_id", "phone-b")
            val summary = BackupService(target, ctx("phone-b"), appVersion = 1).restore({ ByteArrayInputStream(bytes) }, pass.copyOf())
            BackupService.TABLES.filter { it != "audit_event" && it != "app_meta" }.forEach { t ->
                assertEquals("table $t", dump(source, t), dump(target, t))
            }
            // The source's BACKUP_CREATED event is written after the payload, so it cannot be inside the file.
            assertTrue(dump(target, "audit_event").containsAll(dump(source, "audit_event").filterNot { it.contains("|BACKUP_CREATED|") }))
            assertEquals("phone-b", target.accountDao().meta("device_id")) // the new phone keeps its own identity
            assertTrue(IntegrityChecker(target).run().isHealthy)
            assertTrue(summary.transactions > 0)
            assertEquals(LocalDate.of(2026, 10, 1), summary.firstTxnDate)
            val ledger = LedgerService(target, ctx("phone-b"))
            val bpi =
                target
                    .accountDao()
                    .accounts(false)
                    .single { it.name == "BPI" }
                    .id
            assertEquals(p(5_000_000 + 3_000_000 - 200_000), ledger.balance(bpi, LocalDate.of(2100, 1, 1)))
        }

    @Test
    fun `a wrong passphrase or damaged file changes nothing (AC-BAK-2)`() =
        runBlocking<Unit> {
            val bytes = backupOf(source)
            val target = newDb()
            populate(target, ctx("phone-b"))
            val before = BackupService.TABLES.associateWith { dump(target, it) }
            val service = BackupService(target, ctx("phone-b"), appVersion = 1)
            for ((input, passphrase) in listOf(
                bytes to "wrong passphrase!".toCharArray(),
                bytes.copyOf(bytes.size - 20) to pass.copyOf(),
            )) {
                try {
                    service.restore({ ByteArrayInputStream(input) }, passphrase)
                    fail("expected rejection")
                } catch (e: BackupException) {
                    assertEquals(BackupError.WRONG_PASSPHRASE_OR_DAMAGED, e.error)
                }
            }
            assertEquals(before, BackupService.TABLES.associateWith { dump(target, it) })
        }

    @Test
    fun `an interrupted restore leaves the previous data intact (AC-BAK-4)`() =
        runBlocking<Unit> {
            val bytes = backupOf(source)
            val target = newDb()
            populate(target, ctx("phone-b"))
            val before = BackupService.TABLES.associateWith { dump(target, it) }
            for (checkpoint in listOf("restore_after_delete", "restore_after_insert")) {
                val crashing =
                    BackupService(target, ctx("phone-b"), appVersion = 1, faults = {
                        if (it ==
                            checkpoint
                        ) {
                            throw IllegalStateException("crash at $it")
                        }
                    })
                try {
                    crashing.restore({ ByteArrayInputStream(bytes) }, pass.copyOf())
                    fail("expected crash at $checkpoint")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message.orEmpty().contains(checkpoint))
                }
                assertEquals("after crash at $checkpoint", before, BackupService.TABLES.associateWith { dump(target, it) })
            }
            assertTrue(IntegrityChecker(target).run().isHealthy)
        }

    @Test
    fun `inspect verifies without writing, the safety copy runs first, protections come back`() =
        runBlocking<Unit> {
            val bytes = backupOf(source)
            assertNotNull(BackupService(source, ctx("phone-a"), 1).lastBackupAt())
            val target = newDb()
            LedgerService(target, ctx("phone-b")).ensureSystemAccounts()
            var copies = 0
            val service = BackupService(target, ctx("phone-b"), 1, safetyCopy = { copies++ })
            val summary = service.inspect({ ByteArrayInputStream(bytes) }, pass.copyOf())
            assertEquals(0, target.ledgerDao().allFinancialAccounts().size) // nothing written
            assertEquals(3, summary.accounts)
            service.restore({ ByteArrayInputStream(bytes) }, pass.copyOf())
            assertEquals(1, copies)
            val rejected =
                runCatching { target.openHelper.writableDatabase.execSQL("DELETE FROM txn_revision") }.exceptionOrNull()
            assertTrue(
                "append-only trigger restored: $rejected",
                rejected is SQLiteException && rejected.message.orEmpty().contains("APPEND_ONLY"),
            )
            assertEquals(
                1,
                target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM audit_event WHERE kind = 'RESTORE'").use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
        }

    @Test
    fun `backups from a newer schema are refused, plaintext export is readable`() =
        runBlocking<Unit> {
            val header = BackupCrypto.newHeader(fastKdf, 0, 99, FinanceDatabase.SCHEMA_VERSION + 1)
            val out = ByteArrayOutputStream()
            EncryptingOutputStream(out, BackupCrypto.deriveKey(pass, header), header).use { it.write(ByteArray(10)) }
            try {
                BackupService(source, ctx("x"), 1).inspect({ ByteArrayInputStream(out.toByteArray()) }, pass.copyOf())
                fail("expected SCHEMA_TOO_NEW")
            } catch (e: BackupException) {
                assertEquals(BackupError.SCHEMA_TOO_NEW, e.error)
            }
            val text = StringWriter()
            val counts = BackupService(source, ctx("phone-a"), 1).exportPlaintext(text)
            assertTrue(
                text
                    .toString()
                    .lines()
                    .first()
                    .contains("\"manifest\""),
            )
            assertTrue(text.toString().contains("Jollibee"))
            assertEquals(counts.values.sum(), text.toString().lines().count { it.contains("\"t\":\"row\"") })
        }
}
