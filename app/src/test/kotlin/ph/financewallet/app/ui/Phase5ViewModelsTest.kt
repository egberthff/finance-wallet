package ph.financewallet.app.ui

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.advanced.AssetsViewModel
import ph.financewallet.app.ui.advanced.BillsViewModel
import ph.financewallet.app.ui.advanced.DebtsViewModel
import ph.financewallet.app.ui.advanced.ReportsViewModel
import ph.financewallet.app.ui.budgets.BudgetsViewModel
import ph.financewallet.app.ui.transactions.EntryViewModel
import ph.financewallet.data.advanced.AssetKind
import ph.financewallet.data.advanced.DebtKind
import ph.financewallet.data.advanced.OccurrenceState
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.budget.BudgetCategorySpec
import ph.financewallet.data.budget.BudgetSettings
import ph.financewallet.data.budget.LeftoverResolution
import ph.financewallet.data.budget.NewBudget
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.allocation.RolloverPolicy
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.report.RangeKind
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Screen logic of the Phase 5 screens and the Phase 4 gaps (edit, leftovers) against a real in-memory database. */
@RunWith(RobolectricTestRunner::class)
class Phase5ViewModelsTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private val clock = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneOffset.UTC) // Nov 6, Manila
    private val today = LocalDate.of(2026, 11, 6)
    private lateinit var bpi: String
    private lateinit var food: String
    private lateinit var internet: String

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
            bpi =
                s.ledger
                    .createAccount(
                        NewAccount("BPI", FinancialAccountType.BANK, CurrencyCode.PHP, LocalDate.of(2026, 10, 1), p(5_000_000)),
                    ).getOrThrow()
            food = s.categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            internet = s.categories.create("Internet", AccountClass.EXPENSE).getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity: ${report.failures}", report.isHealthy)
        }

    @Test
    fun `debts screen - loan into BPI, payment splits principal and interest`() =
        runBlocking<Unit> {
            val vm = DebtsViewModel(s)
            assertEquals(R.string.err_installments, vm.create("Loan", DebtKind.PERSONAL_LOAN, "12,000", bpi, "x", "15", "k1"))
            assertNull(vm.create("Loan", DebtKind.PERSONAL_LOAN, "12,000", bpi, "12", "15", "k2"))
            val debt = vm.load().debts.single()
            assertEquals(p(1_200_000), debt.outstanding)
            assertEquals(12, debt.remainingInstallments)
            assertEquals(R.string.err_account_required, vm.pay(debt, null, "1000", "", "", "p0"))
            assertNull(vm.pay(debt, bpi, "1,000", "150", "", "p1"))
            val after = vm.load().debts.single()
            assertEquals(p(1_100_000), after.outstanding)
            assertEquals(p(15_000), after.interestAndFeesPaid)
            assertEquals(11, after.remainingInstallments)
            assertEquals(listOf(bpi), vm.load().accounts.map { it.id }) // the loan itself is not offered as a payer
        }

    @Test
    fun `bills screen - a new bill starts in the future and is paid by a real transaction`() =
        runBlocking<Unit> {
            val vm = BillsViewModel(s)
            assertEquals(R.string.err_start_day, vm.create(RuleKind.BILL, "Internet", "1,699", "0", bpi, internet, "3", false))
            assertNull(vm.create(RuleKind.BILL, "Internet", "1,699", "5", bpi, internet, "3", false))
            val state = vm.load()
            assertEquals(LocalDate.of(2026, 12, 5), state.items.first().dueDate) // never starts overdue
            assertTrue(state.items.none { it.state == OccurrenceState.OVERDUE })
            assertNull(vm.markPaid(state.items.first(), null, "1,750", "m1"))
            assertEquals(
                OccurrenceState.PAID,
                vm
                    .load()
                    .items
                    .first()
                    .state,
            )
            assertEquals(p(5_000_000 - 175_000), s.ledger.balance(bpi, LocalDate.of(2100, 1, 1)))
            assertEquals(
                p(169_900 * 12),
                vm
                    .load()
                    .costs
                    .single()
                    .nextTwelveMonths,
            )
        }

    @Test
    fun `assets screen - value entered by the user flows into net worth`() =
        runBlocking<Unit> {
            val vm = AssetsViewModel(s)
            assertNull(vm.create("Car", AssetKind.VEHICLE, "600,000"))
            val car = s.assets.assets().single()
            assertEquals(p(60_000_000), car.latestValue)
            assertNull(vm.addValuation(car.id, "580,000"))
            assertEquals(
                p(5_000_000 + 58_000_000),
                s.reports
                    .netWorth(today)
                    .single()
                    .total,
            )
            // Regression (emulator, 2026-11-06): Home showed net worth without user-entered assets, so Home and Reports
            // disagreed. Home must use the same figure.
            assertEquals(
                p(5_000_000 + 58_000_000),
                ph.financewallet.app.ui.home
                    .HomeViewModel(s)
                    .load()
                    .netWorth,
            )
        }

    @Test
    fun `reports screen - month and quarter figures and net worth`() =
        runBlocking<Unit> {
            s.ledger
                .record(
                    TxnHeader(today, "e1", description = "Lunch"),
                ) { t, _ -> t.expense(bpi, listOf(Split(food, p(25_000)))) }
                .getOrThrow()
            s.ledger.record(TxnHeader(today.minusMonths(1), "e2")) { t, _ -> t.expense(bpi, listOf(Split(food, p(9_000)))) }.getOrThrow()
            val vm = ReportsViewModel(s)
            val month = vm.load(RangeKind.MONTH, today)
            assertEquals(p(25_000), month.summary?.spending)
            assertEquals(listOf(food to p(25_000)), month.spending.map { it.categoryId to it.total })
            assertEquals(p(5_000_000 - 25_000 - 9_000), month.netWorth?.total)
            assertEquals(p(34_000), vm.load(RangeKind.QUARTER, today).summary?.spending)
            assertEquals(LocalDate.of(2026, 11, 30), month.range?.endInclusive)
            assertTrue(month.netWorthHistory.isNotEmpty())
        }

    @Test
    fun `editing an expense creates a revision, unsupported types are refused`() =
        runBlocking<Unit> {
            val id =
                s.ledger
                    .record(
                        TxnHeader(today, "e1", description = "Lunch"),
                    ) { t, _ -> t.expense(bpi, listOf(Split(food, p(25_000)))) }
                    .getOrThrow()
            val vm = EntryViewModel(s)
            vm.loadOptions()
            assertTrue(vm.loadExisting(id))
            assertEquals("250.00", vm.form.value.amount)
            assertEquals(food, vm.form.value.categoryId)
            vm.edit { it.copy(amount = "300", categoryId = internet) }
            assertNull(vm.save())
            val edited = checkNotNull(s.ledger.loadTransaction(id))
            assertTrue(edited.entries.any { it.accountId == internet && it.amount == p(30_000) })
            assertEquals(2, s.ledger.revisionCount(id))
            val opening =
                checkNotNull(
                    s.transactions
                        .page(accountId = bpi)
                        .items
                        .firstOrNull { it.type.name == "OPENING_BALANCE" },
                )
            assertFalse(EntryViewModel(s).loadExisting(opening.id))
        }

    @Test
    fun `leftovers on the budget tab are resolved once`() =
        runBlocking<Unit> {
            val settings =
                BudgetSettings("Personal", IncomeMode.MANUAL, null, listOf(BudgetCategorySpec(food, rollover = RolloverPolicy.MANUAL)))
            val budget =
                s.budgets
                    .createBudget(
                        NewBudget(CurrencyCode.PHP, PeriodRule.Monthly(LocalDate.of(2026, 11, 1), 1), settings),
                    ).getOrThrow()
            val period = s.budgets.ensurePeriod(budget, today).getOrThrow()
            s.allocations.fund(period.id, p(100_000), "f").getOrThrow()
            s.allocations.allocate(period.id, food, p(100_000), "a").getOrThrow()
            s.budgets.closePeriod(period.id).getOrThrow()
            val vm = BudgetsViewModel(s)
            val leftover =
                vm
                    .load()
                    .leftovers
                    .getValue(budget)
                    .single()
            assertEquals(p(100_000), leftover.amount)
            assertNull(vm.resolve(leftover, LeftoverResolution.CarryToNextPeriod))
            assertTrue(vm.load().leftovers.isEmpty())
            assertEquals(R.string.err_generic, vm.resolve(leftover, LeftoverResolution.Expire)) // already resolved
        }
}
