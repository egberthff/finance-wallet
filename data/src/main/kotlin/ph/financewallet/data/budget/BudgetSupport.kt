package ph.financewallet.data.budget

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.BudgetCategoryEntity
import ph.financewallet.data.db.entity.BudgetEntity
import ph.financewallet.data.db.entity.BudgetPeriodEntity
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.budget.PeriodCalculator
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.common.ValidationScope
import java.time.LocalDate

/** Column values of a [PeriodRule] in the `budget` table. */
internal data class RuleColumns(
    val kind: String,
    val anchorDate: String,
    val anchorDay: Int?,
    val anchorDay2: Int?,
    val everyNDays: Int?,
    val customEndDate: String?,
)

internal object RuleCodec {
    fun columns(rule: PeriodRule): RuleColumns {
        val anchor = rule.anchorDate.toString()
        return when (rule) {
            is PeriodRule.Weekly -> RuleColumns("WEEKLY", anchor, null, null, null, null)
            is PeriodRule.Biweekly -> RuleColumns("BIWEEKLY", anchor, null, null, null, null)
            is PeriodRule.EveryNDays -> RuleColumns("EVERY_N_DAYS", anchor, null, null, rule.days, null)
            is PeriodRule.Monthly -> RuleColumns("MONTHLY", anchor, rule.anchorDay, null, null, null)
            is PeriodRule.SemiMonthly -> RuleColumns("SEMI_MONTHLY", anchor, rule.day1, rule.day2, null, null)
            is PeriodRule.CustomOnce -> RuleColumns("CUSTOM_ONCE", anchor, null, null, null, rule.endInclusive.toString())
        }
    }

    fun ruleOf(b: BudgetEntity): PeriodRule {
        val anchor = LocalDate.parse(b.anchorDate)
        return when (b.periodKind) {
            "WEEKLY" -> PeriodRule.Weekly(anchor)
            "BIWEEKLY" -> PeriodRule.Biweekly(anchor)
            "EVERY_N_DAYS" -> PeriodRule.EveryNDays(anchor, checkNotNull(b.everyNDays))
            "MONTHLY" -> PeriodRule.Monthly(anchor, checkNotNull(b.anchorDay))
            "SEMI_MONTHLY" -> PeriodRule.SemiMonthly(anchor, checkNotNull(b.anchorDay), checkNotNull(b.anchorDay2))
            "CUSTOM_ONCE" -> PeriodRule.CustomOnce(anchor, LocalDate.parse(checkNotNull(b.customEndDate)))
            else -> error("unknown period kind ${b.periodKind}")
        }
    }
}

/**
 * Lookups shared by the budget, allocation and goal services. Period resolution (UC-17, docs/10-domain-model.md §5.1):
 * a stored period always wins (stored dates survive rule changes); otherwise dates on or after the budget's
 * `rule_effective_from` use the current rule, and the period is stored on first use (idempotent by start date).
 */
internal class BudgetSupport(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    val dao get() = db.budgetDao()

    suspend fun ValidationScope.budget(id: String): BudgetEntity = dao.budget(id) ?: notFound("budget", id)

    suspend fun ValidationScope.activeBudget(id: String): BudgetEntity {
        val b = budget(id)
        ensure(b.archivedAt == null, "budgetId", "BUDGET_ARCHIVED")
        return b
    }

    suspend fun ValidationScope.period(id: String): BudgetPeriodEntity = dao.period(id) ?: notFound("budget period", id)

    /** Period [id], which must be open and belong to an active budget. */
    suspend fun ValidationScope.openPeriod(id: String): Pair<BudgetEntity, BudgetPeriodEntity> {
        val p = period(id)
        ensure(p.closedAt == null, "periodId", "PERIOD_CLOSED")
        return activeBudget(p.budgetId) to p
    }

    suspend fun ValidationScope.spec(
        budgetId: String,
        categoryId: String,
    ): BudgetCategoryEntity =
        dao.budgetCategories(budgetId).firstOrNull { it.categoryId == categoryId }
            ?: fail("categoryId", "CATEGORY_NOT_IN_BUDGET")

    /** Expense category of the budget (income categories are tracked, never allocated). */
    suspend fun ValidationScope.expenseSpec(
        budgetId: String,
        categoryId: String,
    ): BudgetCategoryEntity {
        val s = spec(budgetId, categoryId)
        ensure(isExpense(categoryId), "categoryId", "CATEGORY_NOT_ALLOCATABLE")
        return s
    }

    suspend fun isExpense(categoryId: String): Boolean = db.ledgerDao().ledgerAccount(categoryId)?.accountClass == "EXPENSE"

    /** The period containing [date], storing it on first use. */
    suspend fun ValidationScope.periodFor(
        budget: BudgetEntity,
        date: LocalDate,
    ): BudgetPeriodEntity = periodOrNull(budget, date, store = true) ?: fail("date", "NO_PERIOD_FOR_DATE")

    suspend fun periodOrNull(
        budget: BudgetEntity,
        date: LocalDate,
        store: Boolean,
    ): BudgetPeriodEntity? {
        dao.periodContaining(budget.id, date.toString())?.let { return it }
        val computed =
            PeriodCalculator
                .periodContaining(RuleCodec.ruleOf(budget), date)
                ?.takeUnless { date.isBefore(LocalDate.parse(budget.ruleEffectiveFrom)) }
        val row = computed?.let { newPeriodRow(budget.id, it) }
        if (store && row != null) dao.insertPeriod(row)
        return row
    }

    /** The period right after [period], if the budget has one; stored when [store] is true. */
    suspend fun next(
        budget: BudgetEntity,
        period: BudgetPeriodEntity,
        store: Boolean,
    ): BudgetPeriodEntity? = periodOrNull(budget, LocalDate.parse(period.endDate).plusDays(1), store)

    fun newPeriodRow(
        budgetId: String,
        p: Period,
    ): BudgetPeriodEntity {
        val now = ctx.nowMillis()
        return BudgetPeriodEntity(
            ctx.ids.newId(),
            budgetId,
            p.start.toString(),
            p.endInclusive.toString(),
            null,
            1,
            now,
            ctx.deviceId,
            ctx.nextHlc(),
        )
    }

    fun info(p: BudgetPeriodEntity) =
        PeriodInfo(
            p.id,
            p.budgetId,
            LocalDate.parse(p.startDate),
            LocalDate.parse(p.endDate),
            p.closedAt != null,
        )
}
