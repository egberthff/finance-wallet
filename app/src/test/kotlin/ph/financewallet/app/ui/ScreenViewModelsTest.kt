package ph.financewallet.app.ui

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
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.accounts.AccountsViewModel
import ph.financewallet.app.ui.budgets.BudgetsViewModel
import ph.financewallet.app.ui.budgets.GoalsViewModel
import ph.financewallet.app.ui.home.HomeViewModel
import ph.financewallet.app.ui.transactions.EntryKind
import ph.financewallet.app.ui.transactions.EntryViewModel
import ph.financewallet.app.ui.transactions.TransactionsViewModel
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Screen logic against a real (in-memory) database: what each screen shows and what its actions store.
 * Composables only render this state, so these tests cover the behaviour behind the UI (UX-05 states, TXN-13 entry).
 */
@RunWith(RobolectricTestRunner::class)
class ScreenViewModelsTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC) // Nov 20, Manila

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity: ${report.failures}", report.isHealthy)
        }

    private suspend fun accountId(name: String) =
        s.accounts
            .accounts()
            .first { it.name == name }
            .id

    @Test
    fun `accounts screen creates accounts from typed text and shows calculated balances`() =
        runBlocking<Unit> {
            val vm = AccountsViewModel(s)
            assertEquals(R.string.err_amount_format, vm.create("GCash", FinancialAccountType.EWALLET, "1.500,00"))
            assertNull(vm.create("GCash", FinancialAccountType.EWALLET, "1,500.00"))
            assertNull(vm.create("Card", FinancialAccountType.CREDIT_CARD, "250"))
            assertEquals(R.string.err_name_length, vm.create(" ", FinancialAccountType.CASH, ""))
            val state = vm.load()
            assertEquals(mapOf("GCash" to p(150_000), "Card" to p(-25_000)), state.accounts.associate { it.row.name to it.balance })
            assertNull(vm.reconcile(accountId("GCash"), "1,450", createAdjustment = true, key = "r1"))
            val gcash = vm.load().accounts.first { it.row.name == "GCash" }
            assertEquals(p(145_000), gcash.balance)
            assertEquals(p(145_000), gcash.reconciled?.statementBalance)
            assertEquals(R.string.err_balance_not_zero, vm.archive(gcash.row.id, acknowledgeNonZeroBalance = false))
        }

    @Test
    fun `fast entry validates, saves, and resets for the next entry`() =
        runBlocking<Unit> {
            AccountsViewModel(s).create("GCash", FinancialAccountType.EWALLET, "1000")
            val food = s.categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            val vm = EntryViewModel(s)
            vm.loadOptions()
            assertEquals(accountId("GCash"), vm.form.value.accountId) // defaults to the first account
            vm.edit { it.copy(amount = "125.50") }
            assertEquals(R.string.err_category_required, vm.save())
            vm.edit { it.copy(categoryId = food, date = "2026-13-01") }
            assertEquals(R.string.err_date_format, vm.save())
            vm.edit { it.copy(date = "2026-11-20", description = "Lunch") }
            assertNull(vm.save())
            assertEquals("", vm.form.value.amount)
            assertEquals(food, vm.form.value.categoryId) // kept for the next entry (TXN-13)
            assertEquals(p(100_000 - 12_550), s.ledger.balance(accountId("GCash"), LocalDate.of(2026, 11, 20)))

            val list = TransactionsViewModel(s)
            list.reload()
            assertEquals(
                listOf("Lunch", ""),
                list.state.value.items
                    .map { it.description },
            )
        }

    @Test
    fun `transfers through the entry screen never count as spending on the dashboard`() =
        runBlocking<Unit> {
            val accounts = AccountsViewModel(s)
            accounts.create("GCash", FinancialAccountType.EWALLET, "5000")
            accounts.create("BPI", FinancialAccountType.BANK, "")
            accounts.create("Card", FinancialAccountType.CREDIT_CARD, "1200")
            val salary = s.categories.create("Salary", AccountClass.INCOME).getOrThrow()
            val entry = EntryViewModel(s)
            entry.loadOptions()
            entry.edit { it.copy(kind = EntryKind.INCOME, amount = "30,000", categoryId = salary) }
            assertNull(entry.save())
            entry.edit { it.copy(kind = EntryKind.TRANSFER, amount = "2,000", accountId = null) }
            assertEquals(R.string.err_account_required, entry.save())
            entry.edit { it.copy(accountId = accountIdBlocking("GCash"), toAccountId = accountIdBlocking("BPI")) }
            assertNull(entry.save())

            val home = HomeViewModel(s).load()
            assertEquals(p(3_500_000), home.inAccounts) // 5,000 + 30,000 (transfer moves money, no change in total)
            assertEquals(p(120_000), home.owed)
            assertEquals(p(3_380_000), home.netWorth)
            assertEquals(p(3_000_000), home.monthIncome)
            assertEquals(p(0), home.monthSpending)
        }

    @Test
    fun `budget tab creates a budget usable today and allocates`() =
        runBlocking<Unit> {
            AccountsViewModel(s).create("GCash", FinancialAccountType.EWALLET, "")
            val food = s.categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            val vm = BudgetsViewModel(s)
            assertEquals(R.string.err_start_day, vm.createMonthly("Personal", "32", listOf(food), IncomeMode.MANUAL))
            assertEquals(R.string.err_budget_categories_empty, vm.createMonthly("Personal", "5", emptyList(), IncomeMode.MANUAL))
            assertNull(vm.createMonthly("Personal", "5", listOf(food), IncomeMode.MANUAL))
            val card = vm.load().budgets.single()
            val period = checkNotNull(card.current).period
            assertEquals(LocalDate.of(2026, 11, 5) to LocalDate.of(2026, 12, 4), period.start to period.endInclusive)
            assertNull(vm.fund(period.id, "10,000", "f1"))
            assertNull(vm.allocate(period.id, food, "4,000", "a1"))
            val o =
                checkNotNull(
                    vm
                        .load()
                        .budgets
                        .single()
                        .current,
                )
            assertEquals(p(600_000), o.availableToBudget)
            assertEquals(p(400_000), o.categories.single().allocated)
        }

    @Test
    fun `current period start handles short months`() {
        assertEquals(LocalDate.of(2026, 11, 5), BudgetsViewModel.currentPeriodStart(5, LocalDate.of(2026, 11, 20)))
        assertEquals(LocalDate.of(2026, 10, 25), BudgetsViewModel.currentPeriodStart(25, LocalDate.of(2026, 11, 20)))
        assertEquals(LocalDate.of(2027, 1, 31), BudgetsViewModel.currentPeriodStart(31, LocalDate.of(2027, 2, 10)))
        assertEquals(LocalDate.of(2027, 2, 28), BudgetsViewModel.currentPeriodStart(31, LocalDate.of(2027, 2, 28)))
    }

    @Test
    fun `goals screen sets aside free money and releases only with confirmation`() =
        runBlocking<Unit> {
            AccountsViewModel(s).create("GCash", FinancialAccountType.EWALLET, "10,000")
            val vm = GoalsViewModel(s)
            assertNull(vm.create("Laptop", "50,000"))
            val goal = vm.load().goals.single()
            assertNull(vm.earmark(goal.id, "3,000", "e1"))
            assertEquals(p(700_000), vm.load().freeMoney)
            assertEquals(R.string.err_confirmation_required, vm.release(goal.id, "1,000", confirmed = false, key = "r1"))
            assertNull(vm.release(goal.id, "1,000", confirmed = true, key = "r2"))
            assertEquals(
                p(200_000),
                vm
                    .load()
                    .goals
                    .single()
                    .allocated,
            )
            assertEquals(
                400,
                vm
                    .load()
                    .goals
                    .single()
                    .progressBp,
            ) // 2,000 of 50,000 = 4 %
        }

    private fun accountIdBlocking(name: String) = runBlocking { accountId(name) }
}
