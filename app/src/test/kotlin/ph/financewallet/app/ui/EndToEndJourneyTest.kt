package ph.financewallet.app.ui

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.app.AppServices
import ph.financewallet.app.ui.backup.BackupViewModel
import ph.financewallet.app.ui.home.HomeViewModel
import ph.financewallet.app.ui.imports.ImportViewModel
import ph.financewallet.data.budget.BudgetCategorySpec
import ph.financewallet.data.budget.BudgetSettings
import ph.financewallet.data.budget.GoalDetails
import ph.financewallet.data.budget.NewBudget
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.allocation.Bucket
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.allocation.RolloverPolicy
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * CLAUDE.md §19 end-to-end journeys 1–11 and 13 (12, sync, is out of scope: ADR-0012) in one run against the real services
 * and ViewModels, with the financial-integrity checks after each step: transfers and savings never count as spending,
 * balances add up, nothing is duplicated, and IC-1..8 hold at the end. On-device runs of the same journeys are Phase 8 (device).
 */
@RunWith(RobolectricTestRunner::class)
class EndToEndJourneyTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 20)
    private val far = LocalDate.of(2100, 1, 1)
    private val db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
    private lateinit var s: AppServices
    private lateinit var gcash: String
    private lateinit var bpi: String
    private lateinit var savings: String
    private lateinit var food: String
    private lateinit var salary: String

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @After
    fun tearDown() {
        val report = runBlocking { IntegrityChecker(db).run() }
        db.close()
        assertTrue("integrity: ${report.failures}", report.isHealthy)
    }

    private suspend fun account(
        name: String,
        type: FinancialAccountType,
        opening: Long,
    ) = s.ledger.createAccount(NewAccount(name, type, CurrencyCode.PHP, LocalDate.of(2026, 11, 1), p(opening))).getOrThrow()

    /** Journeys 1–3: local-only start, accounts, income. */
    private suspend fun startAndEarn() {
        s = AppServices.create(db, clock)
        gcash = account("GCash", FinancialAccountType.EWALLET, 200_000)
        bpi = account("BPI", FinancialAccountType.BANK, 1_000_000)
        savings = account("BPI Savings", FinancialAccountType.SAVINGS, 0)
        salary = s.ledger.createCategory("Salary", AccountClass.INCOME).getOrThrow()
        food = s.ledger.createCategory("Food", AccountClass.EXPENSE).getOrThrow()
        s.ledger
            .record(
                TxnHeader(LocalDate.of(2026, 11, 15), "salary"),
            ) { t, _ -> t.income(bpi, listOf(Split(salary, p(3_000_000)))) }
            .getOrThrow()
        // the same idempotency key again (double tap, retry) must not record a second salary
        s.ledger
            .record(
                TxnHeader(LocalDate.of(2026, 11, 15), "salary"),
            ) { t, _ -> t.income(bpi, listOf(Split(salary, p(3_000_000)))) }
            .getOrThrow()
        assertEquals(p(4_000_000), s.ledger.balance(bpi, far))
    }

    /** Journeys 4–6: custom budget Nov 5 – Dec 4, allocate income, record expenses. */
    private suspend fun budgetAndSpend(): String {
        val settings =
            BudgetSettings("Personal", IncomeMode.MANUAL, null, listOf(BudgetCategorySpec(food, rollover = RolloverPolicy.MANUAL)))
        val budget =
            s.budgets
                .createBudget(
                    NewBudget(CurrencyCode.PHP, PeriodRule.Monthly(LocalDate.of(2026, 11, 5), 5), settings),
                ).getOrThrow()
        val period = s.budgets.ensurePeriod(budget, today).getOrThrow()
        assertEquals(LocalDate.of(2026, 11, 5) to LocalDate.of(2026, 12, 4), period.start to period.endInclusive)
        s.allocations.fund(period.id, p(3_000_000), "fund").getOrThrow()
        s.allocations.allocate(period.id, food, p(800_000), "alloc-food").getOrThrow()
        s.ledger
            .record(
                TxnHeader(LocalDate.of(2026, 11, 16), "lunch"),
            ) { t, _ -> t.expense(gcash, listOf(Split(food, p(45_050)))) }
            .getOrThrow()
        s.ledger
            .record(
                TxnHeader(LocalDate.of(2026, 11, 17), "groceries"),
            ) { t, _ -> t.expense(bpi, listOf(Split(food, p(250_000)))) }
            .getOrThrow()
        return period.id
    }

    /** Journeys 7–9: transfer, savings deposit, goal allocation and withdrawal; none of it is spending. */
    private suspend fun moveAndSave(periodId: String) {
        s.ledger.record(TxnHeader(LocalDate.of(2026, 11, 18), "topup")) { t, _ -> t.transfer(bpi, gcash, p(200_000)) }.getOrThrow()
        s.ledger
            .record(
                TxnHeader(LocalDate.of(2026, 11, 18), "save"),
            ) { t, _ -> t.savingsContribution(bpi, savings, p(500_000)) }
            .getOrThrow()
        val goal = s.goals.createGoal(CurrencyCode.PHP, GoalDetails("Emergency fund", p(5_000_000))).getOrThrow()
        s.goals.contribute(Bucket.Unallocated(periodId), goal, p(500_000), "goal-in").getOrThrow()
        s.goals.withdraw(goal, periodId, p(100_000), confirmed = true, idempotencyKey = "goal-out", reason = "dentist").getOrThrow()
        assertEquals(p(400_000), s.goals.summary(goal).allocated)
        val month = checkNotNull(s.ledger.summary(LocalDate.of(2026, 11, 1), today)[CurrencyCode.PHP])
        assertEquals("one salary only", p(3_000_000), month.income)
        assertEquals("transfers and savings are not spending", p(295_050), month.spending)
        assertEquals(p(200_000 - 45_050 + 200_000), s.ledger.balance(gcash, far))
        assertEquals(p(4_000_000 - 250_000 - 200_000 - 500_000), s.ledger.balance(bpi, far))
        assertEquals(p(500_000), s.ledger.balance(savings, far))
    }

    /** Journey 10: reports and the dashboard agree with the ledger. */
    private suspend fun review() {
        val home = HomeViewModel(s).load()
        assertEquals("all money in accounts, opening + salary − spending", p(1_200_000 + 3_000_000 - 295_050), home.inAccounts)
        assertEquals(p(295_050), home.monthSpending)
        val byCategory =
            s.reports.spendingByCategory(
                ph.financewallet.domain.budget
                    .Period(LocalDate.of(2026, 11, 1), today),
                CurrencyCode.PHP,
            )
        assertEquals(listOf(food to p(295_050)), byCategory.map { it.categoryId to it.total })
    }

    /** Journey 11: statement import; re-importing the same file adds nothing. */
    private suspend fun importStatement() {
        val csv = "Date,Description,Amount\n2026-11-19,Jollibee,-180.00\n2026-11-19,Refund Shopee,120.00\n"
        repeat(2) {
            val vm = ImportViewModel(s)
            vm.loadOptions()
            vm.chooseAccount(gcash)
            vm.load("gcash.csv", csv.toByteArray())
            if (vm.state.value.draft.datePattern == null) vm.editDraft { d -> d.copy(datePattern = "yyyy-MM-dd") }
            assertNull(vm.preview())
            assertNull(vm.commit())
        }
        assertEquals("second import found duplicates only", p(354_950 - 18_000 + 12_000), s.ledger.balance(gcash, far))
    }

    /** Journey 13: back up, lose a later change, restore; balances return to the backed-up state. */
    private suspend fun backupAndRestore() {
        val vm = BackupViewModel(s)
        val file = ByteArrayOutputStream()
        assertNull(vm.create({ file }, "isang-mahabang-passphrase", "isang-mahabang-passphrase"))
        val before = s.ledger.balance(bpi, far)
        s.ledger.record(TxnHeader(today, "after-backup")) { t, _ -> t.expense(bpi, listOf(Split(food, p(99_900)))) }.getOrThrow()
        val bytes = file.toByteArray()
        assertNull(vm.inspect({ ByteArrayInputStream(bytes) }, "isang-mahabang-passphrase"))
        assertNull(vm.restore({ ByteArrayInputStream(bytes) }, "isang-mahabang-passphrase"))
        assertEquals(before, s.ledger.balance(bpi, far))
    }

    @Test
    fun `a month of real use keeps every total consistent`() =
        runBlocking<Unit> {
            startAndEarn()
            val period = budgetAndSpend()
            moveAndSave(period)
            review()
            importStatement()
            backupAndRestore()
        }
}
