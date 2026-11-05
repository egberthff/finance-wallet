package ph.financewallet.domain.allocation

import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.common.validating
import ph.financewallet.domain.money.Allocation
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.money.RateMath

/**
 * Builds allocation-ledger actions (docs/10-domain-model.md §5.4, §6). The money ledger is never touched here:
 * allocating to savings changes what money is *for*, not where it is, so it is never an expense (SAV-02).
 *
 * Every draft records before/after per bucket ([BucketChange]) for the audit trail (SAV-04).
 */
public class AllocationTemplates(
    private val currency: CurrencyCode,
    private val balances: BucketBalances,
    private val goalCap: LinkedGoalCap? = null,
) {
    /** Manual funding of a period's pool (income mode MANUAL). */
    public fun fund(
        periodId: String,
        amount: Money,
    ): DomainResult<AllocationDraft> = draft(AllocationKind.FUND) { positive(amount) + listOf(Bucket.Unallocated(periodId) to amount) }

    /** Pool → category. "Unallocated" may go negative (over-allocated), which the UI shows as a warning. */
    public fun allocate(
        periodId: String,
        categoryId: String,
        amount: Money,
    ): DomainResult<AllocationDraft> =
        draft(AllocationKind.ALLOCATE) {
            positive(amount) + move(Bucket.Unallocated(periodId), Bucket.Category(periodId, categoryId), amount)
        }

    /**
     * Percentage allocation (BUD-05, EX-16): each category receives its share of `pool × Σbp / 10 000`, split by
     * largest remainder so the parts sum exactly. Categories with 0 bp are skipped.
     */
    public fun allocatePercent(
        periodId: String,
        pool: Money,
        weightsBp: List<Pair<String, Int>>,
    ): DomainResult<AllocationDraft> =
        draft(AllocationKind.ALLOCATE) {
            positive(pool)
            ensure(weightsBp.isNotEmpty(), "weights", "WEIGHTS_EMPTY")
            ensure(weightsBp.all { it.second in 0..RateMath.BP_PER_UNIT.toInt() }, "weights", "WEIGHT_OUT_OF_RANGE")
            val totalBp = weightsBp.sumOf { it.second }
            ensure(totalBp in 1..RateMath.BP_PER_UNIT.toInt(), "weights", "WEIGHTS_EXCEED_100_PERCENT")
            val portion = RateMath.applyBp(pool, totalBp.toLong(), java.math.RoundingMode.FLOOR)
            val shares = Allocation.split(portion, weightsBp.filter { it.second > 0 }.map { it.first to it.second.toLong() })
            shares.filterValues { !it.isZero }.flatMap { (categoryId, share) ->
                move(Bucket.Unallocated(periodId), Bucket.Category(periodId, categoryId), share)
            }
        }

    /** Moves allocation between buckets of the same budget period (category ↔ category or ↔ unallocated). */
    public fun reallocate(
        from: Bucket,
        to: Bucket,
        amount: Money,
        reason: String? = null,
    ): DomainResult<AllocationDraft> =
        draft(AllocationKind.REALLOCATE, reason) {
            ensure(from != to, "to", "SAME_BUCKET")
            ensure(from !is Bucket.Goal && to !is Bucket.Goal, "to", "USE_GOAL_ACTIONS")
            ensure(periodOf(from) == periodOf(to), "to", "DIFFERENT_PERIODS")
            positive(amount) + move(from, to, amount)
        }

    /** Savings from the budget pool or a category into a goal (SAV-02, EX-15). */
    public fun contributeToGoal(
        source: Bucket,
        goalId: String,
        amount: Money,
    ): DomainResult<AllocationDraft> =
        draft(AllocationKind.GOAL_CONTRIBUTE) {
            ensure(source !is Bucket.Goal, "source", "USE_GOAL_TRANSFER")
            checkCap(goalId, amount)
            positive(amount) + move(source, Bucket.Goal(goalId), amount)
        }

    /** Goal → period pool. Requires explicit confirmation (SAV-04). */
    public fun withdrawFromGoal(
        goalId: String,
        toPeriodId: String,
        amount: Money,
        confirmed: Boolean,
        reason: String? = null,
    ): DomainResult<AllocationDraft> =
        draft(AllocationKind.GOAL_WITHDRAW, reason, confirmed) {
            positive(amount) + move(Bucket.Goal(goalId), Bucket.Unallocated(toPeriodId), amount)
        }

    /** Goal → goal. Requires explicit confirmation (SAV-04). */
    public fun transferBetweenGoals(
        fromGoalId: String,
        toGoalId: String,
        amount: Money,
        confirmed: Boolean,
        reason: String? = null,
    ): DomainResult<AllocationDraft> =
        draft(AllocationKind.GOAL_TRANSFER, reason, confirmed) {
            ensure(fromGoalId != toGoalId, "toGoalId", "SAME_BUCKET")
            checkCap(toGoalId, amount)
            positive(amount) + move(Bucket.Goal(fromGoalId), Bucket.Goal(toGoalId), amount)
        }

    /**
     * Earmark "free money" for a goal without a budget (GOAL_DIRECT). A positive [delta] earmarks; a negative one
     * releases, which needs confirmation like any withdrawal.
     */
    public fun earmarkDirect(
        goalId: String,
        delta: Money,
        confirmed: Boolean = false,
    ): DomainResult<AllocationDraft> =
        draft(AllocationKind.GOAL_DIRECT, confirmed = confirmed) {
            ensure(!delta.isZero, "amount", "AMOUNT_ZERO")
            sameCurrency(delta)
            if (delta.isNegative) ensure(confirmed, "confirmed", "CONFIRMATION_REQUIRED") else checkCap(goalId, delta)
            listOf(Bucket.Goal(goalId) to delta)
        }

    // ---- building blocks ----------------------------------------------------------------------------------------

    private fun draft(
        kind: AllocationKind,
        reason: String? = null,
        confirmed: Boolean = false,
        build: ValidationScope.() -> List<Pair<Bucket, Money>>,
    ): DomainResult<AllocationDraft> =
        validating {
            if (kind.needsConfirmation) ensure(confirmed, "confirmed", "CONFIRMATION_REQUIRED")
            ensure(reason == null || reason.length <= MAX_REASON_LENGTH, "reason", "REASON_TOO_LONG")
            val parts = build()
            val entries = parts.map { (bucket, amount) -> AllocationEntry(bucket, amount) }
            if (!kind.singleSided) {
                val sum = entries.fold(0L) { acc, e -> Math.addExact(acc, e.amount.minor) }
                invariant(sum == 0L, "ALLOCATION_UNBALANCED") { "$kind sums to $sum" }
            }
            AllocationDraft(kind, entries, changesOf(entries), reason, confirmed)
        }

    private fun ValidationScope.changesOf(entries: List<AllocationEntry>): List<BucketChange> =
        entries.groupBy { it.bucket }.map { (bucket, es) ->
            val previous = balances.of(bucket)
            val updated = previous + Money.sum(es.map { it.amount }, currency)
            ensure(bucket.mayGoNegative || !updated.isNegative, "amount", "ALLOCATION_INSUFFICIENT")
            BucketChange(bucket, previous, updated)
        }

    private fun move(
        from: Bucket,
        to: Bucket,
        amount: Money,
    ): List<Pair<Bucket, Money>> = listOf(from to -amount, to to amount)

    private fun ValidationScope.positive(amount: Money): List<Pair<Bucket, Money>> {
        ensure(amount.isPositive, "amount", "AMOUNT_NOT_POSITIVE")
        sameCurrency(amount)
        return emptyList()
    }

    private fun ValidationScope.sameCurrency(amount: Money) = ensure(amount.currency == currency, "amount", "CURRENCY_MISMATCH")

    private fun ValidationScope.checkCap(
        goalId: String,
        increase: Money,
    ) {
        val headroom = goalCap?.headroom(goalId, currency) ?: return
        ensure(increase <= headroom, "amount", "GOAL_EXCEEDS_LINKED_BALANCE")
    }

    private fun periodOf(bucket: Bucket): String? =
        when (bucket) {
            is Bucket.Unallocated -> bucket.periodId
            is Bucket.Category -> bucket.periodId
            is Bucket.Goal -> null
        }

    private companion object {
        const val MAX_REASON_LENGTH = 500
    }
}
