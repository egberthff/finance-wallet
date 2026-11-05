package ph.financewallet.domain.allocation

import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money

/** How a budget period is funded (docs/10-domain-model.md §5.4). */
public enum class IncomeMode { TRACKED, FIXED, MANUAL }

/** Derived budget and savings figures. All inputs are already-summed amounts from the two ledgers. */
public object BudgetMath {
    /**
     * Funding of a period: TRACKED = in-scope income from the money ledger; FIXED = configured amount;
     * MANUAL = Σ FUND allocation entries.
     */
    public fun funding(
        mode: IncomeMode,
        trackedIncome: Money,
        fixedAmount: Money?,
        manualFunds: Money,
    ): Money =
        when (mode) {
            IncomeMode.TRACKED -> trackedIncome
            IncomeMode.FIXED -> requireNotNull(fixedAmount) { "FIXED income mode needs an amount" }
            IncomeMode.MANUAL -> manualFunds
        }

    /**
     * Available to budget = funding + Σ entries on the period's UNALLOCATED bucket, excluding FUND entries, which are
     * already part of [funding] in MANUAL mode. Negative means over-allocated.
     */
    public fun availableToBudget(
        funding: Money,
        unallocatedMovements: Money,
    ): Money = funding + unallocatedMovements

    /** Remaining in a category: allocated − spent (negative = overspent). */
    public fun categoryRemaining(
        allocated: Money,
        spent: Money,
    ): Money = allocated - spent

    /** Goal progress in basis points, floor-rounded and capped at 100 %. */
    public fun goalProgressBp(
        allocated: Money,
        target: Money,
    ): Int {
        require(target.isPositive) { "target must be positive" }
        if (!allocated.isPositive) return 0
        val bp =
            java.math.BigInteger.valueOf(allocated.minor) * java.math.BigInteger.valueOf(FULL_BP) /
                java.math.BigInteger.valueOf(target.minor)
        return bp.min(java.math.BigInteger.valueOf(FULL_BP)).toInt()
    }

    /**
     * Free money (dashboard, domain §6) = Σ balances of spendable asset accounts − Σ allocations of goals that are
     * not linked to any account. Linked goals are already covered by their own account balances.
     */
    public fun freeMoney(
        currency: CurrencyCode,
        spendableBalances: Collection<Money>,
        unlinkedGoalAllocations: Collection<Money>,
    ): Money = Money.sum(spendableBalances, currency) - Money.sum(unlinkedGoalAllocations, currency)

    private const val FULL_BP = 10_000L
}
