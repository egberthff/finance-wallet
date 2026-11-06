package ph.financewallet.app.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.errorText
import ph.financewallet.app.ui.common.parseAmount
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.budget.BudgetCategorySpec
import ph.financewallet.data.budget.BudgetSettings
import ph.financewallet.data.budget.GoalDetails
import ph.financewallet.data.budget.GoalSummary
import ph.financewallet.data.budget.Leftover
import ph.financewallet.data.budget.LeftoverResolution
import ph.financewallet.data.budget.NewBudget
import ph.financewallet.data.budget.PeriodOverview
import ph.financewallet.domain.allocation.Bucket
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth

data class BudgetCard(
    val id: String,
    val name: String,
    val incomeMode: IncomeMode,
    /** Null when today is before the budget's first period. */
    val current: PeriodOverview?,
)

data class BudgetsState(
    val loading: Boolean = true,
    val budgets: List<BudgetCard> = emptyList(),
    val categoryNames: Map<String, String> = emptyMap(),
    val expenseCategories: List<Option> = emptyList(),
    val goals: List<GoalSummary> = emptyList(),
    /** Leftovers of closed periods waiting for a decision (MANUAL rollover policy), per budget id. */
    val leftovers: Map<String, List<Leftover>> = emptyMap(),
)

/**
 * Budgets tab (UC-16..19). Each budget is shown on its own; totals of different budgets are never added (BUD-04).
 * Opening the tab closes ended periods lazily (rollover) and shows the current one.
 */
class BudgetsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(BudgetsState())
    val state: StateFlow<BudgetsState> = mutable

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    suspend fun load(): BudgetsState {
        val categories = s.categories.categories(includeArchived = true)
        val cards =
            s.budgets.activeBudgets().map { b ->
                val period = (s.budgets.refresh(b.id) as? DomainResult.Ok)?.value
                BudgetCard(b.id, b.name, b.incomeMode, period?.let { s.budgets.overview(it.id) })
            }
        return BudgetsState(
            loading = false,
            budgets = cards,
            categoryNames = categories.associate { it.id to it.name },
            expenseCategories = categories.filter { it.accountClass == "EXPENSE" && it.archivedAt == null }.map { Option(it.id, it.name) },
            goals = s.goals.goals().filter { it.status.name != "ARCHIVED" },
            leftovers = cards.associate { it.id to s.budgets.leftovers(it.id) }.filterValues { it.isNotEmpty() },
        )
    }

    /** Carry a leftover into the next period, sweep it to a goal, or let it lapse (recorded once). */
    suspend fun resolve(
        leftover: Leftover,
        resolution: LeftoverResolution,
    ): Int? =
        s.budgets.resolveLeftover(leftover.periodId, leftover.categoryId, resolution).errorRes().also {
            if (it ==
                null
            ) {
                mutable.value = load()
            }
        }

    /**
     * Monthly budget starting on [startDayText] (1–31; days past the month's end use its last day, BUD-02).
     * The first period is the one containing today, so the budget is usable immediately.
     */
    suspend fun createMonthly(
        name: String,
        startDayText: String,
        categoryIds: List<String>,
        mode: IncomeMode,
    ): Int? {
        val day = startDayText.trim().toIntOrNull()?.takeIf { it in 1..PeriodRule.MAX_DAY_OF_MONTH } ?: return R.string.err_start_day
        val rule = PeriodRule.Monthly(currentPeriodStart(day, s.ctx.today()), day)
        val settings = BudgetSettings(name, mode, null, categoryIds.map { BudgetCategorySpec(it) })
        return s.budgets.createBudget(NewBudget(CurrencyCode.PHP, rule, settings)).errorRes().also {
            if (it ==
                null
            ) {
                mutable.value = load()
            }
        }
    }

    suspend fun allocate(
        periodId: String,
        categoryId: String,
        amountText: String,
        key: String,
    ): Int? = withAmount(amountText) { s.allocations.allocate(periodId, categoryId, it, key) }

    suspend fun fund(
        periodId: String,
        amountText: String,
        key: String,
    ): Int? = withAmount(amountText) { s.allocations.fund(periodId, it, key) }

    /** Savings from the period's pool into a goal: lowers "available", never counts as spending (SAV-02). */
    suspend fun saveToGoal(
        periodId: String,
        goalId: String,
        amountText: String,
        key: String,
    ): Int? = withAmount(amountText) { s.goals.contribute(Bucket.Unallocated(periodId), goalId, it, key) }

    suspend fun close(periodId: String): Int? =
        s.budgets
            .closePeriod(periodId)
            .errorRes()
            .also { if (it == null) mutable.value = load() }

    private suspend fun withAmount(
        text: String,
        action: suspend (Money) -> DomainResult<*>,
    ): Int? {
        val (amount, error) = parseAmount(text)
        if (amount == null) return error
        val result = action(amount)
        return (result as? DomainResult.Err)?.let { errorText(it.error) }.also { if (it == null) mutable.value = load() }
    }

    companion object {
        /** The most recent day-[day] start on or before [today] (clamped to month length). */
        fun currentPeriodStart(
            day: Int,
            today: LocalDate,
        ): LocalDate {
            fun clamped(m: YearMonth) = m.atDay(minOf(day, m.lengthOfMonth()))
            val thisMonth = clamped(YearMonth.from(today))
            return if (!thisMonth.isAfter(today)) thisMonth else clamped(YearMonth.from(today).minusMonths(1))
        }
    }
}

data class GoalsState(
    val loading: Boolean = true,
    val goals: List<GoalSummary> = emptyList(),
    val freeMoney: Money = Money.zero(CurrencyCode.PHP),
)

/** Savings goals (UC-20/21) outside a budget: earmark free money, release with confirmation. */
class GoalsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(GoalsState())
    val state: StateFlow<GoalsState> = mutable

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    suspend fun load() = GoalsState(false, s.goals.goals(), s.goals.freeMoney(CurrencyCode.PHP))

    suspend fun create(
        name: String,
        targetText: String,
    ): Int? {
        val (target, error) = parseAmount(targetText)
        if (target == null) return error
        return s.goals
            .createGoal(CurrencyCode.PHP, GoalDetails(name, target))
            .errorRes()
            .also { if (it == null) mutable.value = load() }
    }

    suspend fun earmark(
        goalId: String,
        amountText: String,
        key: String,
    ): Int? {
        val (amount, error) = parseAmount(amountText)
        if (amount == null) return error
        return s.goals
            .earmark(goalId, amount, key)
            .errorRes()
            .also { if (it == null) mutable.value = load() }
    }

    /** Release requires the user's explicit [confirmed] (SAV-04). */
    suspend fun release(
        goalId: String,
        amountText: String,
        confirmed: Boolean,
        key: String,
    ): Int? {
        val (amount, error) = parseAmount(amountText)
        if (amount == null) return error
        return s.goals
            .earmark(goalId, -amount, key, confirmed)
            .errorRes()
            .also { if (it == null) mutable.value = load() }
    }
}
