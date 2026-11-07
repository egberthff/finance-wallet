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
import ph.financewallet.app.ui.advanced.BillsViewModel
import ph.financewallet.app.ui.budgets.BudgetEditViewModel
import ph.financewallet.app.ui.budgets.BudgetsViewModel
import ph.financewallet.app.ui.budgets.GoalsViewModel
import ph.financewallet.app.ui.budgets.PeriodChoice
import ph.financewallet.app.ui.budgets.PeriodForm
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.advanced.RuleStatus
import ph.financewallet.data.budget.AllocationMode
import ph.financewallet.data.budget.GoalStatus
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.allocation.RolloverPolicy
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Service capabilities now reachable from the UI (blueprint I): account, budget, bill and goal actions. */
@RunWith(RobolectricTestRunner::class)
class OrphanActionsViewModelTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private lateinit var gcash: String
    private lateinit var food: String
    private lateinit var fare: String
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 20)

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
            gcash =
                s.ledger
                    .createAccount(
                        NewAccount("GCash", FinancialAccountType.EWALLET, CurrencyCode.PHP, LocalDate.of(2026, 11, 1), p(500_000)),
                    ).getOrThrow()
            food = s.categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            fare = s.categories.create("Fare", AccountClass.EXPENSE).getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity: ${report.failures}", report.isHealthy)
        }

    @Test
    fun `account edit changes name and institution, and the opening balance through a revision`() =
        runBlocking<Unit> {
            val vm = AccountsViewModel(s)
            val state = checkNotNull(vm.editState(gcash))
            assertEquals(p(500_000), state.edit.openingAmount)
            assertNull(vm.edit(gcash, state, "GCash wallet", "G-Xchange", "4,500.00"))
            val row = s.accounts.accounts().single { it.id == gcash }
            assertEquals("GCash wallet" to "G-Xchange", row.name to row.institution)
            assertEquals(p(450_000), s.ledger.balance(gcash, today))
            val opening = checkNotNull(db.ledgerDao().liveOpeningOf(gcash))
            assertEquals(listOf("CREATE", "UPDATE"), db.ledgerDao().revisions(opening.id).map { it.changeKind })
            assertEquals(R.string.err_amount_format, vm.edit(gcash, checkNotNull(vm.editState(gcash)), "GCash", "", "4.500,00"))
        }

    @Test
    fun `account type changes from e-wallet to bank and, with only an opening balance, to credit card and back`() =
        runBlocking<Unit> {
            val vm = AccountsViewModel(s)

            suspend fun row() = s.accounts.accounts().single { it.id == gcash }
            var state = checkNotNull(vm.editState(gcash))
            assertEquals(FinancialAccountType.EWALLET, state.edit.type)
            assertNull(vm.edit(gcash, state, "GCash", "", "5,000.00", FinancialAccountType.BANK))
            assertEquals("BANK" to "ASSET", row().type to row().accountClass)
            assertEquals(p(500_000), s.ledger.balance(gcash, today))

            state = checkNotNull(vm.editState(gcash))
            assertNull(vm.edit(gcash, state, "GCash", "", "5,000.00", FinancialAccountType.CREDIT_CARD))
            assertEquals("CREDIT_CARD" to "LIABILITY", row().type to row().accountClass)
            assertEquals(p(-500_000), s.ledger.balance(gcash, today))

            state = checkNotNull(vm.editState(gcash))
            assertNull(vm.edit(gcash, state, "GCash", "", "5,000.00", FinancialAccountType.EWALLET))
            assertEquals("EWALLET" to "ASSET", row().type to row().accountClass)
            assertEquals(p(500_000), s.ledger.balance(gcash, today))
        }

    @Test
    fun `a weekly budget is created, its category settings edited, its rule changed, then archived`() =
        runBlocking<Unit> {
            val budgets = BudgetsViewModel(s)
            assertNull(
                budgets.create(
                    "Weekly",
                    PeriodForm(PeriodChoice.WEEKLY, start = LocalDate.of(2026, 11, 16)),
                    listOf(food),
                    IncomeMode.MANUAL,
                ),
            )
            val id =
                s.budgets
                    .activeBudgets()
                    .single()
                    .id
            assertEquals(
                LocalDate.of(2026, 11, 16),
                checkNotNull((s.budgets.refresh(id) as ph.financewallet.domain.common.DomainResult.Ok).value).start,
            )

            val vm = BudgetEditViewModel(s, id)
            vm.load()
            vm.toggleCategory(fare)
            vm.editSpec(
                food,
            ) { it.copy(mode = AllocationMode.FIXED, amount = "1,500", rollover = RolloverPolicy.CARRY, carryNegative = true) }
            vm.editSpec(fare) { it.copy(mode = AllocationMode.PERCENT, percent = "12.5") }
            vm.edit { it.copy(name = "Weekly spending") }
            assertNull(vm.save())
            val saved = checkNotNull(s.budgets.editState(id))
            assertEquals("Weekly spending", saved.settings.name)
            val foodSpec = saved.settings.categories.single { it.categoryId == food }
            assertEquals(
                Triple(AllocationMode.FIXED, p(150_000), RolloverPolicy.CARRY),
                Triple(foodSpec.mode, foodSpec.defaultAmount, foodSpec.rollover),
            )
            assertTrue(foodSpec.carryNegative)
            assertEquals(
                1_250,
                saved.settings.categories
                    .single { it.categoryId == fare }
                    .percentBp,
            )
            vm.editSpec(fare) { it.copy(percent = "150") }
            assertEquals(R.string.err_budget_category_amount, vm.save())

            vm.load()
            val from = checkNotNull(vm.ruleChangeFrom())
            assertEquals(LocalDate.of(2026, 11, 23), from) // the current week (16–22 Nov) is never split
            assertEquals(R.string.err_rule_start_too_early, vm.changeRule(PeriodForm(PeriodChoice.WEEKLY, start = from.minusDays(1))))
            assertNull(vm.changeRule(PeriodForm(PeriodChoice.MONTHLY, day1 = "1")))
            assertTrue(checkNotNull(s.budgets.editState(id)).rule is PeriodRule.Monthly)

            assertNull(vm.archive())
            assertTrue(s.budgets.activeBudgets().isEmpty())
        }

    @Test
    fun `a bill is edited, paused, resumed and ended`() =
        runBlocking<Unit> {
            val vm = BillsViewModel(s)
            assertNull(vm.create(RuleKind.BILL, "Internet", "1,699", "15", gcash, food, "3", estimate = false))
            val rule = vm.load().rules.single()
            assertNull(vm.editRule(rule, "Fiber internet", "1,899", "20", gcash, fare, "2", estimate = true))
            val edited = vm.load().rules.single()
            assertEquals(
                listOf("Fiber internet", "189900", "20", fare, "true"),
                listOf(
                    edited.name,
                    edited.amountMinor.toString(),
                    edited.day1.toString(),
                    edited.categoryId,
                    edited.amountIsEstimate.toString(),
                ),
            )
            assertNull(vm.setStatus(rule.id, RuleStatus.PAUSED))
            assertEquals(
                RuleStatus.PAUSED.name,
                vm
                    .load()
                    .rules
                    .single()
                    .status,
            )
            assertNull(vm.setStatus(rule.id, RuleStatus.ACTIVE))
            assertNull(vm.setStatus(rule.id, RuleStatus.ENDED))
            assertEquals(
                RuleStatus.ENDED.name,
                vm
                    .load()
                    .rules
                    .single()
                    .status,
            )
        }

    @Test
    fun `goals are edited, linked, withdrawn to a budget with confirmation, and moved to another goal`() =
        runBlocking<Unit> {
            val goals = GoalsViewModel(s)
            assertNull(goals.create("Laptop", "60,000"))
            assertNull(goals.create("Trip", "20,000"))
            val budgets = BudgetsViewModel(s)
            assertNull(budgets.create("Monthly", PeriodForm(PeriodChoice.MONTHLY, day1 = "1"), listOf(food), IncomeMode.MANUAL))
            val state = goals.load()
            val laptop = state.goals.single { it.name == "Laptop" }
            val trip = state.goals.single { it.name == "Trip" }
            assertNull(goals.earmark(laptop.id, "4,000", "k1"))

            val withTarget = goals.load().goals.single { it.id == laptop.id }
            assertNull(goals.edit(withTarget, "Work laptop", "65,000", LocalDate.of(2027, 3, 1), "for freelancing", GoalStatus.ACTIVE))
            val details = checkNotNull(goals.details(laptop.id))
            assertEquals(
                Triple("Work laptop", p(6_500_000), LocalDate.of(2027, 3, 1)),
                Triple(details.name, details.target, details.targetDate),
            )

            var g = goals.load().goals.single { it.id == laptop.id }
            // GCash holds ₱5,000: a goal linked to it may hold up to that, never more (SAV-03).
            assertNull(goals.linkAccounts(g, setOf(gcash)))
            assertEquals(R.string.err_goal_cap, goals.earmark(laptop.id, "1,500", "k2"))
            g = goals.load().goals.single { it.id == laptop.id }
            assertEquals(setOf(gcash), g.linkedAccountIds)

            val period = goals.load().periods.single()
            assertEquals(
                R.string.err_confirmation_required,
                goals.withdraw(g, period.id, "2,000", "laptop on sale", confirmed = false, key = "w1"),
            )
            assertEquals(R.string.err_goal_period_required, goals.withdraw(g, null, "2,000", "", confirmed = true, key = "w2"))
            assertNull(goals.withdraw(g, period.id, "2,000", "laptop on sale", confirmed = true, key = "w3"))
            assertEquals(
                p(200_000),
                goals
                    .load()
                    .goals
                    .single { it.id == laptop.id }
                    .allocated,
            )

            g = goals.load().goals.single { it.id == laptop.id }
            assertNull(goals.transfer(g, trip.id, "1,500", "trip first", confirmed = true, key = "t1"))
            val after = goals.load().goals
            assertEquals(p(50_000), after.single { it.id == laptop.id }.allocated)
            assertEquals(p(150_000), after.single { it.id == trip.id }.allocated)
        }
}
