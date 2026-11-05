package ph.financewallet.domain.allocation

import ph.financewallet.domain.money.Money

/** What happens to a category's leftover when its period closes (BUD-07, docs/10-domain-model.md §5.5). */
public enum class RolloverPolicy { EXPIRE, CARRY, SWEEP_TO_GOAL, MANUAL }

/** The outcome of closing one category of one period. */
public sealed interface RolloverAction {
    /** Nothing is written: the leftover lapses, or there is none. */
    public data object None : RolloverAction

    /** ROLLOVER of [amount] (signed: negative carries an overspend) into the same category of the next period. */
    public data class Carry(
        public val amount: Money,
    ) : RolloverAction

    /** SWEEP of a positive [amount] into savings goal [goalId]. */
    public data class Sweep(
        public val goalId: String,
        public val amount: Money,
    ) : RolloverAction

    /** Positive leftover kept on the "to reassign" list until the user decides (MANUAL, or CARRY without a next period). */
    public data class ToReassign(
        public val amount: Money,
    ) : RolloverAction
}

/**
 * Rollover rules (docs/10-domain-model.md §5.5):
 *
 * | Policy | R > 0 | R < 0 |
 * |---|---|---|
 * | EXPIRE | nothing | nothing |
 * | CARRY | carry R | carry R only if carryNegative |
 * | SWEEP_TO_GOAL | sweep R to the goal | as CARRY |
 * | MANUAL | to reassign | as CARRY |
 *
 * A budget with a single custom period has no next period: positive CARRY leftovers then go to "to reassign" instead
 * of lapsing silently, and negative ones are not carried.
 */
public object RolloverPlanner {
    public fun plan(
        policy: RolloverPolicy,
        carryNegative: Boolean,
        remaining: Money,
        sweepGoalId: String?,
        hasNextPeriod: Boolean,
    ): RolloverAction =
        when {
            remaining.isZero || policy == RolloverPolicy.EXPIRE -> {
                RolloverAction.None
            }

            remaining.isNegative -> {
                if (carryNegative && hasNextPeriod) RolloverAction.Carry(remaining) else RolloverAction.None
            }

            else -> {
                when (policy) {
                    RolloverPolicy.CARRY -> {
                        if (hasNextPeriod) RolloverAction.Carry(remaining) else RolloverAction.ToReassign(remaining)
                    }

                    RolloverPolicy.SWEEP_TO_GOAL -> {
                        RolloverAction.Sweep(requireNotNull(sweepGoalId) { "SWEEP_TO_GOAL needs a goal" }, remaining)
                    }

                    RolloverPolicy.MANUAL -> {
                        RolloverAction.ToReassign(remaining)
                    }

                    RolloverPolicy.EXPIRE -> {
                        RolloverAction.None
                    }
                }
            }
        }

    /**
     * Entries for [action]. They always sum to zero: the closing period's category gives up R and the destination
     * receives it. A carried overspend (R < 0) therefore starts the next period's category at a negative allocation;
     * this is the one case where a category bucket may be negative, and it shows as "overspent, carried".
     */
    public fun entries(
        action: RolloverAction,
        periodId: String,
        nextPeriodId: String?,
        categoryId: String,
    ): List<AllocationEntry> {
        val source = Bucket.Category(periodId, categoryId)
        return when (action) {
            is RolloverAction.Carry -> {
                val next = Bucket.Category(requireNotNull(nextPeriodId) { "carry needs a next period" }, categoryId)
                listOf(AllocationEntry(source, -action.amount), AllocationEntry(next, action.amount))
            }

            is RolloverAction.Sweep -> {
                listOf(AllocationEntry(source, -action.amount), AllocationEntry(Bucket.Goal(action.goalId), action.amount))
            }

            RolloverAction.None, is RolloverAction.ToReassign -> {
                emptyList()
            }
        }
    }
}
