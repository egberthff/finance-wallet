package ph.financewallet.app.ui.budgets

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.categories.CategoryOption
import ph.financewallet.app.ui.categories.categoryOptions
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.parseAmount
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.budget.AllocationMode
import ph.financewallet.data.budget.BudgetCategorySpec
import ph.financewallet.data.budget.BudgetEditState
import ph.financewallet.domain.allocation.RolloverPolicy
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.money.MoneyFormatter
import java.time.LocalDate

/** One category line being edited: the spec fields as text, so the form can hold half-typed values. */
data class SpecForm(
    val categoryId: String,
    val mode: AllocationMode = AllocationMode.NONE,
    val amount: String = "",
    val percent: String = "",
    val rollover: RolloverPolicy = RolloverPolicy.EXPIRE,
    val carryNegative: Boolean = false,
    val sweepGoalId: String? = null,
)

data class BudgetEditForm(
    val name: String = "",
    val manualIncome: Boolean = false,
    val specs: List<SpecForm> = emptyList(),
)

data class BudgetEditUiState(
    val loading: Boolean = true,
    /** Null after loading: the budget is gone or archived. */
    val loaded: BudgetEditState? = null,
    val form: BudgetEditForm = BudgetEditForm(),
    val categories: List<CategoryOption> = emptyList(),
    val goals: List<Option> = emptyList(),
)

/**
 * Budget edit (UC-16; blueprint I): name, income mode, categories with their allocation, rollover, carry-negative and
 * sweep-goal settings; a period-rule change from the first day it may apply; archive. Every write goes through
 * [ph.financewallet.data.budget.BudgetService], which keeps the version check and the no-stranded-money rule.
 */
class BudgetEditViewModel(
    private val s: AppServices,
    private val budgetId: String,
) : ViewModel() {
    private val mutable = MutableStateFlow(BudgetEditUiState())
    val state: StateFlow<BudgetEditUiState> = mutable

    suspend fun load() {
        val loaded = s.budgets.editState(budgetId)
        val rows = s.categories.categories(includeArchived = true)
        val used =
            loaded
                ?.settings
                ?.categories
                ?.map { it.categoryId }
                ?.toSet()
                .orEmpty()
        mutable.value =
            BudgetEditUiState(
                loading = false,
                loaded = loaded,
                form = loaded?.let(::formOf) ?: BudgetEditForm(),
                categories = categoryOptions(rows, "EXPENSE", includeIds = used),
                goals =
                    s.goals
                        .goals()
                        .filter { it.status.name == "ACTIVE" }
                        .map { Option(it.id, it.name) },
            )
    }

    fun edit(change: (BudgetEditForm) -> BudgetEditForm) = mutable.update { it.copy(form = change(it.form)) }

    /** Adds or removes a category; a new one starts with no allocation and "expire" rollover. */
    fun toggleCategory(id: String) =
        edit { f ->
            if (f.specs.any { it.categoryId == id }) {
                f.copy(specs = f.specs.filterNot { it.categoryId == id })
            } else {
                f.copy(specs = f.specs + SpecForm(id))
            }
        }

    fun editSpec(
        categoryId: String,
        change: (SpecForm) -> SpecForm,
    ) = edit { f -> f.copy(specs = f.specs.map { if (it.categoryId == categoryId) change(it) else it }) }

    /** Saves name, income mode and category settings. Returns an error message resource or null. */
    suspend fun save(): Int? {
        val loaded = mutable.value.loaded ?: return R.string.err_not_found
        val f = mutable.value.form
        val specs = f.specs.map { spec -> specOf(spec) ?: return R.string.err_budget_category_amount }
        val settings =
            loaded.settings.copy(
                name = f.name,
                incomeMode = if (f.manualIncome) ph.financewallet.domain.allocation.IncomeMode.MANUAL else defaultMode(loaded),
                categories = specs,
            )
        return s.budgets
            .editBudget(budgetId, loaded.version, settings)
            .errorRes()
            .also { if (it == null) load() }
    }

    /** Changes the period rule; it applies from [BudgetEditState.ruleChangeFrom] (the current period is never split). */
    suspend fun changeRule(form: PeriodForm): Int? {
        val loaded = mutable.value.loaded ?: return R.string.err_not_found
        val (rule, error) = form.toRule(loaded.ruleChangeFrom, forNewBudget = false)
        if (rule == null) return error ?: R.string.err_generic
        return s.budgets
            .changeRule(budgetId, loaded.version, rule)
            .errorRes()
            .also { if (it == null) load() }
    }

    /** Archive keeps the budget with its history; it stops counting and takes no new allocations. */
    suspend fun archive(): Int? {
        val loaded = mutable.value.loaded ?: return R.string.err_not_found
        return s.budgets.archiveBudget(budgetId, loaded.version).errorRes()
    }

    fun ruleChangeFrom(): LocalDate? = mutable.value.loaded?.ruleChangeFrom

    fun currentRule(): PeriodRule? = mutable.value.loaded?.rule

    private fun defaultMode(loaded: BudgetEditState) =
        loaded.settings.incomeMode.takeIf { it != ph.financewallet.domain.allocation.IncomeMode.MANUAL }
            ?: ph.financewallet.domain.allocation.IncomeMode.TRACKED

    private fun specOf(f: SpecForm): BudgetCategorySpec? {
        val amount =
            if (f.mode == AllocationMode.FIXED) parseAmount(f.amount).first ?: return null else null
        val percentBp =
            if (f.mode == AllocationMode.PERCENT) {
                f.percent
                    .trim()
                    .toBigDecimalOrNull()
                    ?.movePointRight(2)
                    ?.toInt()
                    ?.takeIf { it in 1..BP_FULL } ?: return null
            } else {
                null
            }
        return BudgetCategorySpec(
            categoryId = f.categoryId,
            mode = f.mode,
            defaultAmount = amount,
            percentBp = percentBp,
            rollover = f.rollover,
            carryNegative = f.carryNegative,
            sweepGoalId = f.sweepGoalId.takeIf { f.rollover == RolloverPolicy.SWEEP_TO_GOAL },
        )
    }

    private fun formOf(e: BudgetEditState) =
        BudgetEditForm(
            name = e.settings.name,
            manualIncome = e.settings.incomeMode == ph.financewallet.domain.allocation.IncomeMode.MANUAL,
            specs =
                e.settings.categories.map { c ->
                    SpecForm(
                        categoryId = c.categoryId,
                        mode = c.mode,
                        amount = c.defaultAmount?.let { MoneyFormatter.plain(it) }.orEmpty(),
                        percent =
                            c.percentBp
                                ?.toBigDecimal()
                                ?.movePointLeft(2)
                                ?.stripTrailingZeros()
                                ?.toPlainString()
                                .orEmpty(),
                        rollover = c.rollover,
                        carryNegative = c.carryNegative,
                        sweepGoalId = c.sweepGoalId,
                    )
                },
        )

    private companion object {
        const val BP_FULL = 10_000
    }
}
