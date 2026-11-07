package ph.financewallet.data.budget

import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.allocation.RolloverAction
import ph.financewallet.domain.allocation.RolloverPolicy
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** How a category's allocation is suggested each period (BUD-05). NONE = allocate by hand. */
enum class AllocationMode { NONE, FIXED, PERCENT }

/** One category of a budget (docs/11-database-schema.md `budget_category`). */
data class BudgetCategorySpec(
    val categoryId: String,
    val mode: AllocationMode = AllocationMode.NONE,
    val defaultAmount: Money? = null,
    val percentBp: Int? = null,
    val rollover: RolloverPolicy = RolloverPolicy.EXPIRE,
    val carryNegative: Boolean = false,
    val sweepGoalId: String? = null,
)

/** The editable part of a budget. Currency and period rule have their own rules (see [BudgetService]). */
data class BudgetSettings(
    val name: String,
    val incomeMode: IncomeMode,
    val fixedIncome: Money? = null,
    val categories: List<BudgetCategorySpec>,
    /** Empty = all accounts; otherwise only transactions touching these accounts count (domain §5.2). */
    val accountIds: Set<String> = emptySet(),
)

data class NewBudget(
    val currency: CurrencyCode,
    val rule: PeriodRule,
    val settings: BudgetSettings,
)

data class PeriodInfo(
    val id: String,
    val budgetId: String,
    val start: LocalDate,
    val endInclusive: LocalDate,
    val closed: Boolean,
)

data class CategoryLine(
    val categoryId: String,
    val allocated: Money,
    val spent: Money,
    /** allocated − spent; negative = overspent. Income categories show income received as [spent]. */
    val remaining: Money,
)

/** Everything the budget screen shows for one period. Never summed across budgets (domain §5.3). */
data class PeriodOverview(
    val period: PeriodInfo,
    val funding: Money,
    /** Funding minus what has been allocated; negative = over-allocated. */
    val availableToBudget: Money,
    val categories: List<CategoryLine>,
)

data class CategoryRollover(
    val categoryId: String,
    val remaining: Money,
    val action: RolloverAction,
)

data class CloseResult(
    val periodId: String,
    val alreadyClosed: Boolean,
    val rollovers: List<CategoryRollover>,
)

/** A MANUAL (or un-carryable) leftover waiting for the user's decision. */
data class Leftover(
    val periodId: String,
    val categoryId: String,
    val amount: Money,
)

sealed interface LeftoverResolution {
    /** Into the same category of the next period. */
    data object CarryToNextPeriod : LeftoverResolution

    data class SweepToGoal(
        val goalId: String,
    ) : LeftoverResolution

    /** Let it lapse; recorded so it leaves the "to reassign" list. */
    data object Expire : LeftoverResolution
}

data class BudgetHeader(
    val id: String,
    val name: String,
    val currency: CurrencyCode,
    val incomeMode: IncomeMode,
    val version: Long,
)

/** What the budget edit screen starts from (UC-16): settings, the period rule, and when a new rule could start. */
data class BudgetEditState(
    val settings: BudgetSettings,
    val rule: PeriodRule,
    val version: Long,
    val ruleChangeFrom: LocalDate,
)
