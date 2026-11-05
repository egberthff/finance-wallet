package ph.financewallet.data.budget

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.allocation.AllocationDraft
import ph.financewallet.domain.allocation.AllocationTemplates
import ph.financewallet.domain.allocation.Bucket
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money

/**
 * UC-18: fund, allocate (fixed or by percentage), apply a budget's default allocations, reallocate
 * (docs/10-domain-model.md §5.4). The money ledger is never touched: allocating says what money is *for*.
 * Every call is idempotent by its key: repeating it returns the first group's id without writing.
 */
class AllocationService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val support = BudgetSupport(db, ctx)
    private val store = AllocationStore(db, ctx)

    /** Manual funding of a period's pool. Only in income mode MANUAL (TRACKED and FIXED derive funding). */
    suspend fun fund(
        periodId: String,
        amount: Money,
        idempotencyKey: String,
    ): DomainResult<String> =
        write(idempotencyKey) {
            val (b, _) = with(support) { openPeriod(periodId) }
            ensure(b.incomeMode == IncomeMode.MANUAL.name, "periodId", "FUND_REQUIRES_MANUAL_MODE")
            apply(CurrencyCode.of(b.currency), listOf(Bucket.Unallocated(periodId)), idempotencyKey) { it.fund(periodId, amount) }
        }

    suspend fun allocate(
        periodId: String,
        categoryId: String,
        amount: Money,
        idempotencyKey: String,
    ): DomainResult<String> =
        write(idempotencyKey) {
            val (b, _) = with(support) { openPeriod(periodId) }
            with(support) { expenseSpec(b.id, categoryId) }
            val buckets = listOf(Bucket.Unallocated(periodId), Bucket.Category(periodId, categoryId))
            apply(CurrencyCode.of(b.currency), buckets, idempotencyKey) { it.allocate(periodId, categoryId, amount) }
        }

    /** Percentage allocation of [pool] (BUD-05, EX-16), split exactly by largest remainder. */
    suspend fun allocatePercent(
        periodId: String,
        pool: Money,
        weightsBp: List<Pair<String, Int>>,
        idempotencyKey: String,
    ): DomainResult<String> =
        write(idempotencyKey) {
            val (b, _) = with(support) { openPeriod(periodId) }
            weightsBp.forEach { (categoryId, _) -> with(support) { expenseSpec(b.id, categoryId) } }
            val buckets = listOf(Bucket.Unallocated(periodId)) + weightsBp.map { Bucket.Category(periodId, it.first) }
            apply(CurrencyCode.of(b.currency), buckets, idempotencyKey) { it.allocatePercent(periodId, pool, weightsBp) }
        }

    /**
     * Applies the budget's configured allocations to a period: each FIXED category gets its default amount, and the
     * PERCENT categories share the period's funding. Each part has its own derived key, so a repeat (or a partial
     * earlier run) never allocates twice. Returns the ids of all groups (new or existing).
     */
    suspend fun applyDefaults(
        periodId: String,
        idempotencyKey: String,
    ): DomainResult<List<String>> =
        db.validatedWrite {
            ensure(idempotencyKey.isNotBlank(), "idempotencyKey", "IDEMPOTENCY_KEY_REQUIRED")
            val (b, p) = with(support) { openPeriod(periodId) }
            val c = CurrencyCode.of(b.currency)
            val specs = db.budgetDao().budgetCategories(b.id).filter { support.isExpense(it.categoryId) }
            val fixed =
                specs.filter { it.allocationMode == AllocationMode.FIXED.name }.map { s ->
                    val key = "$idempotencyKey:fixed:${s.categoryId}"
                    store.existing(key) ?: apply(c, listOf(Bucket.Unallocated(periodId), Bucket.Category(periodId, s.categoryId)), key) {
                        it.allocate(periodId, s.categoryId, Money(checkNotNull(s.defaultAmountMinor), c))
                    }
                }
            val weights =
                specs.filter { it.allocationMode == AllocationMode.PERCENT.name }.map {
                    it.categoryId to
                        checkNotNull(it.percentBp)
                }
            val pool = store.funding(b, p)
            val percentKey = "$idempotencyKey:percent"
            val percent =
                when {
                    weights.isEmpty() -> {
                        null
                    }

                    store.existing(percentKey) != null -> {
                        store.existing(percentKey)
                    }

                    !pool.isPositive -> {
                        fail("periodId", "NO_FUNDING_FOR_PERCENT")
                    }

                    else -> {
                        val buckets = listOf(Bucket.Unallocated(periodId)) + weights.map { Bucket.Category(periodId, it.first) }
                        apply(c, buckets, percentKey) { it.allocatePercent(periodId, pool, weights) }
                    }
                }
            fixed + listOfNotNull(percent)
        }

    /** Moves allocation within one period: category ↔ category or ↔ the pool. */
    suspend fun reallocate(
        from: Bucket,
        to: Bucket,
        amount: Money,
        idempotencyKey: String,
        reason: String? = null,
    ): DomainResult<String> =
        write(idempotencyKey) {
            val periodId =
                when (from) {
                    is Bucket.Unallocated -> from.periodId
                    is Bucket.Category -> from.periodId
                    is Bucket.Goal -> fail("from", "USE_GOAL_ACTIONS")
                }
            val (b, _) = with(support) { openPeriod(periodId) }
            listOf(from, to).filterIsInstance<Bucket.Category>().forEach { with(support) { expenseSpec(b.id, it.categoryId) } }
            apply(CurrencyCode.of(b.currency), listOf(from, to).filter { it !is Bucket.Goal }, idempotencyKey) {
                it.reallocate(from, to, amount, reason)
            }
        }

    private suspend fun write(
        key: String,
        block: suspend ValidationScope.() -> String,
    ): DomainResult<String> =
        db.validatedWrite {
            store.existing(key) ?: block()
        }

    private suspend fun ValidationScope.apply(
        currency: CurrencyCode,
        buckets: List<Bucket>,
        key: String,
        build: (AllocationTemplates) -> DomainResult<AllocationDraft>,
    ): String {
        val templates = AllocationTemplates(currency, store.balancesFor(buckets, currency), store.goalCap(currency))
        val draft = build(templates).bind()
        return with(store) { writeGroup(draft.kind, draft.entries, draft.changes, key, draft.reason, draft.confirmed) }
    }
}
