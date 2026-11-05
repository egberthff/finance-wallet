package ph.financewallet.domain.allocation

import ph.financewallet.domain.money.Money

/** Allocation group kinds (docs/10-domain-model.md §5.4). */
public enum class AllocationKind(
    /** Single-sided kinds add or release money from outside the allocation ledger; all others are zero-sum. */
    public val singleSided: Boolean = false,
    /** Kinds that move money out of savings require explicit user confirmation (SAV-04). */
    public val needsConfirmation: Boolean = false,
) {
    FUND(singleSided = true),
    ALLOCATE,
    REALLOCATE,
    GOAL_CONTRIBUTE,
    GOAL_WITHDRAW(needsConfirmation = true),
    GOAL_DIRECT(singleSided = true),
    GOAL_TRANSFER(needsConfirmation = true),
    ROLLOVER,
    SWEEP,
}

/** Where allocated money sits. Budget buckets belong to one budget period; goals are global. */
public sealed interface Bucket {
    public data class Unallocated(
        public val periodId: String,
    ) : Bucket

    public data class Category(
        public val periodId: String,
        public val categoryId: String,
    ) : Bucket

    public data class Goal(
        public val goalId: String,
    ) : Bucket

    /** Category and goal buckets may never hold a negative allocation; "unallocated" may (over-allocated). */
    public val mayGoNegative: Boolean get() = this is Unallocated
}

public data class AllocationEntry(
    public val bucket: Bucket,
    public val amount: Money,
)

/** Before/after of one bucket, recorded with the group for audit (SAV-04). */
public data class BucketChange(
    public val bucket: Bucket,
    public val previous: Money,
    public val updated: Money,
)

/** A validated allocation action, ready to persist as one allocation_group with its entries. */
public data class AllocationDraft(
    public val kind: AllocationKind,
    public val entries: List<AllocationEntry>,
    public val changes: List<BucketChange>,
    public val reason: String? = null,
    public val confirmed: Boolean = false,
)

/** Current allocated amount of a bucket (supplied by the data layer from allocation_entry sums). */
public fun interface BucketBalances {
    public fun of(bucket: Bucket): Money
}
