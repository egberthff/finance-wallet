package ph.financewallet.data.budget

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.BudgetAccountEntity
import ph.financewallet.data.db.entity.BudgetCategoryEntity
import ph.financewallet.data.db.entity.BudgetEntity
import ph.financewallet.data.db.entity.BudgetPeriodEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.allocation.AllocationEntry
import ph.financewallet.domain.allocation.AllocationKind
import ph.financewallet.domain.allocation.BucketChange
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.allocation.RolloverAction
import ph.financewallet.domain.allocation.RolloverPlanner
import ph.financewallet.domain.allocation.RolloverPolicy
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.budget.PeriodCalculator
import ph.financewallet.domain.budget.PeriodRule
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/**
 * Budget use cases UC-16 (create/edit/rule change/archive), UC-17 (periods), UC-19 (close + rollover) and the
 * period overview (docs/12-use-cases.md, docs/10-domain-model.md §5). Allocations are in [AllocationService].
 * Every write is one database transaction that rolls back completely on any failed check.
 */
class BudgetService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val support = BudgetSupport(db, ctx)
    private val store = AllocationStore(db, ctx)
    private val dao get() = db.budgetDao()

    /** UC-16: creates a budget. Its rule applies from the rule's anchor date. */
    suspend fun createBudget(req: NewBudget): DomainResult<String> =
        db.validatedWrite {
            checkSettings(req.currency, req.settings)
            val id = ctx.ids.newId()
            val now = ctx.nowMillis()
            val r = RuleCodec.columns(req.rule)
            dao.insertBudget(
                BudgetEntity(
                    id = id,
                    name = req.settings.name.trim(),
                    currency = req.currency.code,
                    periodKind = r.kind,
                    anchorDate = r.anchorDate,
                    anchorDay = r.anchorDay,
                    anchorDay2 = r.anchorDay2,
                    everyNDays = r.everyNDays,
                    customEndDate = r.customEndDate,
                    ruleEffectiveFrom = req.rule.anchorDate.toString(),
                    incomeMode = req.settings.incomeMode.name,
                    fixedIncomeMinor = req.settings.fixedIncome?.minor,
                    accountFilter = req.settings.accountIds.isNotEmpty(),
                    archivedAt = null,
                    createdAt = now,
                    version = 1,
                    updatedAt = now,
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                ),
            )
            writeChildren(id, req.settings)
            id
        }

    /**
     * UC-16: replaces name, income mode, categories and account filter. The currency never changes. A category that
     * still holds allocation in an open period cannot be removed (reallocate it first), so no money is stranded.
     */
    suspend fun editBudget(
        budgetId: String,
        expectedVersion: Long,
        settings: BudgetSettings,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val b = current(budgetId, expectedVersion)
            checkSettings(CurrencyCode.of(b.currency), settings)
            val kept = settings.categories.map { it.categoryId }.toSet()
            dao.budgetCategories(budgetId).filter { it.categoryId !in kept }.forEach { removed ->
                ensure(dao.openCategoryAllocation(budgetId, removed.categoryId) == 0L, "categories", "CATEGORY_HAS_ALLOCATION")
            }
            dao.updateBudget(
                touch(b).copy(
                    name = settings.name.trim(),
                    incomeMode = settings.incomeMode.name,
                    fixedIncomeMinor = settings.fixedIncome?.minor,
                    accountFilter = settings.accountIds.isNotEmpty(),
                ),
            )
            dao.deleteBudgetCategories(budgetId)
            dao.deleteBudgetAccounts(budgetId)
            writeChildren(budgetId, settings)
        }

    /** A budget's current settings, period rule and version, for the edit screen (UC-16). Null if missing or archived. */
    suspend fun editState(budgetId: String): BudgetEditState? {
        val b = dao.budget(budgetId)?.takeIf { it.archivedAt == null } ?: return null
        val currency = CurrencyCode.of(b.currency)
        val specs =
            dao.budgetCategories(budgetId).sortedBy { it.sortOrder }.map { c ->
                BudgetCategorySpec(
                    categoryId = c.categoryId,
                    mode = AllocationMode.valueOf(c.allocationMode),
                    defaultAmount = c.defaultAmountMinor?.let { Money(it, currency) },
                    percentBp = c.percentBp,
                    rollover = RolloverPolicy.valueOf(c.rolloverPolicy),
                    carryNegative = c.carryNegative,
                    sweepGoalId = c.sweepGoalId,
                )
            }
        val settings =
            BudgetSettings(
                name = b.name,
                incomeMode = IncomeMode.valueOf(b.incomeMode),
                fixedIncome = b.fixedIncomeMinor?.let { Money(it, currency) },
                categories = specs,
                accountIds = dao.budgetAccountIds(budgetId).toSet(),
            )
        return BudgetEditState(settings, RuleCodec.ruleOf(b), b.version, effectiveDateFor(b))
    }

    /**
     * First day a new period rule may apply (domain §5.1): the day after the later of the current period's end and the
     * last stored period's end. The current period is never split.
     */
    suspend fun ruleChangeEffectiveDate(budgetId: String): LocalDate {
        val b = checkNotNull(dao.budget(budgetId)) { "budget $budgetId" }
        return effectiveDateFor(b)
    }

    /**
     * UC-16 rule change. Periods of the old rule up to the current one are stored, so their dates stay fixed. The
     * new rule's anchor must be on or after [ruleChangeEffectiveDate]; any gap before its first period becomes one
     * bridging period, so every date still belongs to exactly one period.
     */
    suspend fun changeRule(
        budgetId: String,
        expectedVersion: Long,
        newRule: PeriodRule,
    ): DomainResult<LocalDate> =
        db.validatedWrite {
            val b = current(budgetId, expectedVersion)
            val effectiveFrom = effectiveDateFor(b)
            ensure(!newRule.anchorDate.isBefore(effectiveFrom), "rule", "RULE_ANCHOR_BEFORE_EFFECTIVE_DATE")
            materializeOldPeriods(b, effectiveFrom)
            val first = PeriodCalculator.firstPeriod(newRule)
            if (first.start.isAfter(effectiveFrom)) {
                dao.insertPeriod(support.newPeriodRow(b.id, Period(effectiveFrom, first.start.minusDays(1))))
            }
            val r = RuleCodec.columns(newRule)
            dao.updateBudget(
                touch(b).copy(
                    periodKind = r.kind,
                    anchorDate = r.anchorDate,
                    anchorDay = r.anchorDay,
                    anchorDay2 = r.anchorDay2,
                    everyNDays = r.everyNDays,
                    customEndDate = r.customEndDate,
                    ruleEffectiveFrom = effectiveFrom.toString(),
                ),
            )
            effectiveFrom
        }

    /** UC-16: archive (kept with its history; no further allocations). */
    suspend fun archiveBudget(
        budgetId: String,
        expectedVersion: Long,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val b = current(budgetId, expectedVersion)
            dao.updateBudget(touch(b).copy(archivedAt = ctx.nowMillis()))
        }

    /** UC-17: the period containing [date], created on first use. */
    suspend fun ensurePeriod(
        budgetId: String,
        date: LocalDate,
    ): DomainResult<PeriodInfo> =
        db.validatedWrite {
            with(support) {
                val b = budget(budgetId)
                info(periodFor(b, date))
            }
        }

    /**
     * Lazy close (domain §5.5): closes, in order, every open period that ended before today, then returns today's
     * period (or null if today is before the budget's first period).
     */
    suspend fun refresh(budgetId: String): DomainResult<PeriodInfo?> =
        db.validatedWrite {
            with(support) {
                val b = activeBudget(budgetId)
                val today = ctx.today()
                dao.openPeriodsEndingBefore(budgetId, today.toString()).forEach { closeInternal(b, it) }
                periodOrNull(b, today, store = true)?.let(::info)
            }
        }

    /** UC-19: closes a period, applying each category's rollover policy once (idempotent per period and category). */
    suspend fun closePeriod(periodId: String): DomainResult<CloseResult> =
        db.validatedWrite {
            with(support) {
                val p = period(periodId)
                closeInternal(budget(p.budgetId), p)
            }
        }

    /** Positive leftovers of closed periods that wait for a decision (MANUAL policy, or CARRY with no next period). */
    suspend fun leftovers(budgetId: String): List<Leftover> {
        val b = checkNotNull(dao.budget(budgetId)) { "budget $budgetId" }
        val c = CurrencyCode.of(b.currency)
        return dao.periods(budgetId).filter { it.closedAt != null }.flatMap { p ->
            val hasNext = support.next(b, p, store = false)?.let { it.closedAt == null } ?: false
            dao.budgetCategories(budgetId).filter { support.isExpense(it.categoryId) }.mapNotNull { s ->
                if (store.existing(rolloverKey(p.id, s.categoryId)) != null) return@mapNotNull null
                val remaining = remaining(b, p, s.categoryId, c)
                val action = plan(s, remaining, hasNext)
                (action as? RolloverAction.ToReassign)?.let { Leftover(p.id, s.categoryId, it.amount) }
            }
        }
    }

    /** Resolves one leftover from [leftovers]. Recorded under the same key as automatic rollovers, so it happens once. */
    suspend fun resolveLeftover(
        periodId: String,
        categoryId: String,
        resolution: LeftoverResolution,
    ): DomainResult<String> =
        db.validatedWrite {
            with(support) {
                val p = period(periodId)
                ensure(p.closedAt != null, "periodId", "PERIOD_NOT_CLOSED")
                val b = activeBudget(p.budgetId)
                expenseSpec(b.id, categoryId)
                val key = rolloverKey(p.id, categoryId)
                ensure(store.existing(key) == null, "categoryId", "ALREADY_RESOLVED")
                val c = CurrencyCode.of(b.currency)
                val remaining = remaining(b, p, categoryId, c)
                ensure(remaining.isPositive, "categoryId", "NOTHING_TO_REASSIGN")
                val note = mapOf("remainingMinor" to remaining.minor.toString(), "resolution" to resolution.javaClass.simpleName)
                when (resolution) {
                    LeftoverResolution.Expire -> {
                        with(store) { writeGroup(AllocationKind.ROLLOVER, emptyList(), emptyList(), key, note = note) }
                    }

                    LeftoverResolution.CarryToNextPeriod -> {
                        val next = next(b, p, store = true) ?: fail("periodId", "NO_NEXT_PERIOD")
                        ensure(next.closedAt == null, "periodId", "NEXT_PERIOD_CLOSED")
                        writeRollover(AllocationKind.ROLLOVER, RolloverAction.Carry(remaining), p, next, categoryId, c, key, note)
                    }

                    is LeftoverResolution.SweepToGoal -> {
                        checkSweepGoal(resolution.goalId, c, remaining)
                        writeRollover(
                            AllocationKind.SWEEP,
                            RolloverAction.Sweep(resolution.goalId, remaining),
                            p,
                            null,
                            categoryId,
                            c,
                            key,
                            note,
                        )
                    }
                }
            }
        }

    /** Figures for one period (domain §5.4). Only expense categories are listed; income shows in [PeriodOverview.funding]. */
    suspend fun overview(periodId: String): PeriodOverview {
        val p = checkNotNull(dao.period(periodId)) { "period $periodId" }
        val b = checkNotNull(dao.budget(p.budgetId))
        val c = CurrencyCode.of(b.currency)
        val lines =
            dao.budgetCategories(b.id).filter { support.isExpense(it.categoryId) }.map { s ->
                val allocated = store.allocated(p.id, s.categoryId, c)
                val spent = store.spent(b, p, s.categoryId)
                CategoryLine(s.categoryId, allocated, spent, allocated - spent)
            }
        return PeriodOverview(support.info(p), store.funding(b, p), store.available(b, p), lines)
    }

    suspend fun periods(budgetId: String): List<PeriodInfo> = dao.periods(budgetId).map(support::info)

    /** Active (not archived) budgets, by name. */
    suspend fun activeBudgets(): List<BudgetHeader> =
        dao.activeBudgets().map {
            BudgetHeader(
                it.id,
                it.name,
                CurrencyCode.of(it.currency),
                IncomeMode.valueOf(it.incomeMode),
                it.version,
            )
        }

    // ---- internals ----------------------------------------------------------------------------------------------

    private suspend fun ValidationScope.closeInternal(
        b: BudgetEntity,
        p: BudgetPeriodEntity,
    ): CloseResult {
        if (p.closedAt != null) return CloseResult(p.id, alreadyClosed = true, rollovers = emptyList())
        val c = CurrencyCode.of(b.currency)
        val next = support.next(b, p, store = true)?.takeIf { it.closedAt == null }
        val results =
            dao.budgetCategories(b.id).filter { support.isExpense(it.categoryId) }.map { s ->
                val key = rolloverKey(p.id, s.categoryId)
                val remaining = remaining(b, p, s.categoryId, c)
                var action = plan(s, remaining, next != null)
                if (store.existing(key) != null) return@map CategoryRollover(s.categoryId, remaining, RolloverAction.None)
                if (action is RolloverAction.Sweep && !sweepAllowed(action.goalId, c, action.amount)) {
                    action = RolloverAction.ToReassign(action.amount) // goal unavailable or over its linked cap
                }
                val note = mapOf("remainingMinor" to remaining.minor.toString(), "policy" to s.rolloverPolicy)
                when (action) {
                    is RolloverAction.Carry -> writeRollover(AllocationKind.ROLLOVER, action, p, next, s.categoryId, c, key, note)
                    is RolloverAction.Sweep -> writeRollover(AllocationKind.SWEEP, action, p, null, s.categoryId, c, key, note)
                    else -> Unit
                }
                CategoryRollover(s.categoryId, remaining, action)
            }
        dao.updatePeriod(p.copy(closedAt = ctx.nowMillis(), version = p.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()))
        return CloseResult(p.id, alreadyClosed = false, rollovers = results)
    }

    private suspend fun ValidationScope.writeRollover(
        kind: AllocationKind,
        action: RolloverAction,
        p: BudgetPeriodEntity,
        next: BudgetPeriodEntity?,
        categoryId: String,
        c: CurrencyCode,
        key: String,
        note: Map<String, String>,
    ): String {
        val entries = RolloverPlanner.entries(action, p.id, next?.id, categoryId)
        val changes = changesOf(entries, c)
        return with(store) { writeGroup(kind, entries, changes, key, note = note) }
    }

    private suspend fun changesOf(
        entries: List<AllocationEntry>,
        c: CurrencyCode,
    ): List<BucketChange> {
        val balances = store.balancesFor(entries.map { it.bucket }, c)
        return entries.groupBy { it.bucket }.map { (bucket, es) ->
            val previous = balances.of(bucket)
            BucketChange(bucket, previous, previous + Money.sum(es.map { it.amount }, c))
        }
    }

    private suspend fun ValidationScope.checkSweepGoal(
        goalId: String,
        c: CurrencyCode,
        amount: Money,
    ) {
        val goal = dao.goal(goalId) ?: notFound("goal", goalId)
        ensure(goal.currency == c.code, "goalId", "GOAL_CURRENCY_MISMATCH")
        ensure(goal.status in GoalService.CONTRIBUTABLE, "goalId", "GOAL_NOT_ACTIVE")
        val headroom = store.goalCap(c).headroom(goalId, c)
        ensure(headroom == null || amount <= headroom, "amount", "GOAL_EXCEEDS_LINKED_BALANCE")
    }

    private suspend fun sweepAllowed(
        goalId: String,
        c: CurrencyCode,
        amount: Money,
    ): Boolean {
        val goal = dao.goal(goalId) ?: return false
        if (goal.currency != c.code || goal.status !in GoalService.CONTRIBUTABLE) return false
        val headroom = store.goalCap(c).headroom(goalId, c)
        return headroom == null || amount <= headroom
    }

    private suspend fun remaining(
        b: BudgetEntity,
        p: BudgetPeriodEntity,
        categoryId: String,
        c: CurrencyCode,
    ): Money = store.allocated(p.id, categoryId, c) - store.spent(b, p, categoryId)

    private fun plan(
        s: BudgetCategoryEntity,
        remaining: Money,
        hasNext: Boolean,
    ): RolloverAction = RolloverPlanner.plan(RolloverPolicy.valueOf(s.rolloverPolicy), s.carryNegative, remaining, s.sweepGoalId, hasNext)

    private suspend fun effectiveDateFor(b: BudgetEntity): LocalDate {
        val today = ctx.today()
        val currentEnd = support.periodOrNull(b, today, store = false)?.endDate?.let(LocalDate::parse)
        val lastStoredEnd = dao.periods(b.id).maxOfOrNull { LocalDate.parse(it.endDate) }
        val latest = listOfNotNull(currentEnd, lastStoredEnd).maxOrNull() ?: return maxOf(today, LocalDate.parse(b.ruleEffectiveFrom))
        return latest.plusDays(1)
    }

    /** Stores every period of the current rule that starts before [until], so later rule changes cannot move them. */
    private suspend fun ValidationScope.materializeOldPeriods(
        b: BudgetEntity,
        until: LocalDate,
    ) {
        val rule = RuleCodec.ruleOf(b)
        var p: Period? = PeriodCalculator.firstPeriod(rule)
        var count = 0
        while (p != null && p.start.isBefore(until)) {
            invariant(++count <= MAX_MATERIALIZED, "TOO_MANY_PERIODS") { "budget ${b.id}" }
            if (dao.periodContaining(b.id, p.start.toString()) == null) dao.insertPeriod(support.newPeriodRow(b.id, p))
            p = PeriodCalculator.next(rule, p)
        }
    }

    private suspend fun ValidationScope.current(
        budgetId: String,
        expectedVersion: Long,
    ): BudgetEntity {
        val b = with(support) { activeBudget(budgetId) }
        ensure(b.version == expectedVersion, "budgetId", "STALE_VERSION")
        return b
    }

    private fun touch(b: BudgetEntity) =
        b.copy(version = b.version + 1, updatedAt = ctx.nowMillis(), updatedByDevice = ctx.deviceId, hlc = ctx.nextHlc())

    private suspend fun ValidationScope.checkSettings(
        currency: CurrencyCode,
        s: BudgetSettings,
    ) {
        ensure(s.name.trim().length in 1..MAX_NAME, "name", "NAME_LENGTH")
        if (s.incomeMode == IncomeMode.FIXED) {
            val fixed = s.fixedIncome ?: fail("fixedIncome", "FIXED_INCOME_REQUIRED")
            ensure(fixed.currency == currency, "fixedIncome", "CURRENCY_MISMATCH")
            ensure(fixed.isPositive, "fixedIncome", "AMOUNT_NOT_POSITIVE")
        } else {
            ensure(s.fixedIncome == null, "fixedIncome", "FIXED_INCOME_ONLY_IN_FIXED_MODE")
        }
        ensure(s.categories.isNotEmpty(), "categories", "BUDGET_CATEGORIES_EMPTY")
        val ids = s.categories.map { it.categoryId }
        ensure(ids.toSet().size == ids.size, "categories", "DUPLICATE_CATEGORY")
        var percentTotal = 0
        s.categories.forEach { spec ->
            val account = db.ledgerDao().ledgerAccount(spec.categoryId) ?: notFound("category", spec.categoryId)
            val category = db.ledgerDao().category(spec.categoryId) ?: fail("categories", "NOT_A_CATEGORY")
            ensure(account.archivedAt == null, "categories", "CATEGORY_ARCHIVED")
            ensure(category.parentId == null || category.parentId !in ids, "categories", "BUDGET_CATEGORY_OVERLAP")
            when (account.accountClass) {
                "EXPENSE" -> {
                    percentTotal += checkExpenseSpec(spec, currency)
                }

                "INCOME" -> {
                    ensure(
                        spec.mode == AllocationMode.NONE && spec.rollover == RolloverPolicy.EXPIRE,
                        "categories",
                        "INCOME_CATEGORY_NOT_ALLOCATABLE",
                    )
                }

                else -> {
                    fail("categories", "CATEGORY_WRONG_CLASS")
                }
            }
        }
        ensure(percentTotal <= BP_100, "categories", "WEIGHTS_EXCEED_100_PERCENT")
        s.accountIds.forEach { id ->
            ensure(db.ledgerDao().financialAccount(id) != null, "accountIds", "ACCOUNT_NOT_REAL")
        }
    }

    /** Validates an expense category's allocation and rollover settings; returns its percentage (bp) or 0. */
    private suspend fun ValidationScope.checkExpenseSpec(
        spec: BudgetCategorySpec,
        currency: CurrencyCode,
    ): Int {
        when (spec.mode) {
            AllocationMode.FIXED -> {
                val amount = spec.defaultAmount ?: fail("defaultAmount", "DEFAULT_AMOUNT_REQUIRED")
                ensure(amount.currency == currency && amount.isPositive, "defaultAmount", "AMOUNT_NOT_POSITIVE")
            }

            AllocationMode.PERCENT -> {
                ensure(spec.percentBp != null && spec.percentBp in 1..BP_100, "percentBp", "WEIGHT_OUT_OF_RANGE")
            }

            AllocationMode.NONE -> {}
        }
        if (spec.rollover == RolloverPolicy.SWEEP_TO_GOAL) {
            val goalId = spec.sweepGoalId ?: fail("sweepGoalId", "SWEEP_GOAL_REQUIRED")
            val goal = dao.goal(goalId) ?: notFound("goal", goalId)
            ensure(goal.currency == currency.code, "sweepGoalId", "GOAL_CURRENCY_MISMATCH")
        }
        return if (spec.mode == AllocationMode.PERCENT) checkNotNull(spec.percentBp) else 0
    }

    private suspend fun writeChildren(
        budgetId: String,
        s: BudgetSettings,
    ) {
        val now = ctx.nowMillis()
        dao.insertBudgetCategories(
            s.categories.mapIndexed { i, spec ->
                BudgetCategoryEntity(
                    budgetId = budgetId,
                    categoryId = spec.categoryId,
                    allocationMode = spec.mode.name,
                    defaultAmountMinor = spec.defaultAmount?.minor.takeIf { spec.mode == AllocationMode.FIXED },
                    percentBp = spec.percentBp.takeIf { spec.mode == AllocationMode.PERCENT },
                    rolloverPolicy = spec.rollover.name,
                    carryNegative = spec.carryNegative,
                    sweepGoalId = spec.sweepGoalId.takeIf { spec.rollover == RolloverPolicy.SWEEP_TO_GOAL },
                    sortOrder = i,
                    version = 1,
                    updatedAt = now,
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                )
            },
        )
        if (s.accountIds.isNotEmpty()) {
            dao.insertBudgetAccounts(s.accountIds.sorted().map { BudgetAccountEntity(budgetId, it, 1, now, ctx.deviceId, ctx.nextHlc()) })
        }
    }

    internal companion object {
        const val MAX_NAME = 80
        const val BP_100 = 10_000
        const val MAX_MATERIALIZED = 20_000

        fun rolloverKey(
            periodId: String,
            categoryId: String,
        ) = "rollover:$periodId:$categoryId"
    }
}
