package ph.financewallet.domain.insight

import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** A category whose spending rose noticeably compared with the previous period (INS-01). */
public data class CategoryChange(
    public val categoryId: String,
    public val current: Money,
    public val previous: Money,
    /** Increase in basis points of the previous amount (floor); null when there was no previous spending. */
    public val increaseBp: Long?,
)

/** One recent expense to check against the category's history. */
public data class RecentExpense(
    public val txnId: String,
    public val categoryId: String,
    public val date: LocalDate,
    public val amount: Money,
)

/** One expense that is far above what the category usually costs. */
public data class UnusualExpense(
    public val expense: RecentExpense,
    /** The category's median expense in the look-back window. */
    public val typical: Money,
)

/** Estimated monthly saving capacity from recent full months (always labeled as an estimate, INS-02). */
public data class SavingCapacity(
    /** Floor of the average monthly (income − spending); negative means spending exceeded income. */
    public val perMonth: Money,
    public val monthsUsed: Int,
)

/** Bills due soon compared with free money. */
public data class BillsCoverage(
    public val dueSoon: Money,
    public val freeMoney: Money,
) {
    /** How much is missing to pay every bill due soon from free money (zero when covered). */
    public val shortfall: Money get() = if (dueSoon > freeMoney) dueSoon - freeMoney else Money.zero(dueSoon.currency)
}

/**
 * Deterministic on-device insights (ADR-0012, INS-01..03): plain rules over figures the user can see, with every threshold
 * stated here. They read data only and never change records. Integer arithmetic only.
 */
public object InsightRules {
    /** A category is "up" when it rose by at least 25 % and at least ₱500 (or by ₱500 or more from nothing). */
    public const val MIN_INCREASE_BP: Long = 2_500
    public const val MIN_INCREASE_MINOR: Long = 50_000

    /** An expense is unusual when it is more than 3× the category median, at least ₱1,000, with at least 5 past expenses. */
    public const val UNUSUAL_MULTIPLE: Long = 3
    public const val UNUSUAL_MIN_MINOR: Long = 100_000
    public const val UNUSUAL_MIN_SAMPLES: Int = 5

    private const val FULL_BP = 10_000L

    public fun categoryChanges(
        current: Map<String, Money>,
        previous: Map<String, Money>,
        currency: CurrencyCode,
    ): List<CategoryChange> =
        current
            .mapNotNull { (cat, now) ->
                val before = previous[cat] ?: Money.zero(currency)
                val delta = now.minor - before.minor
                val bp = if (before.isPositive) delta * FULL_BP / before.minor else null
                val noticeable = delta >= MIN_INCREASE_MINOR && (bp == null || bp >= MIN_INCREASE_BP)
                if (noticeable) CategoryChange(cat, now, before, bp) else null
            }.sortedByDescending { it.current.minor - it.previous.minor }

    /** Median of [amounts] (lower middle for an even count, so the threshold is never inflated). */
    public fun median(amounts: List<Long>): Long {
        require(amounts.isNotEmpty()) { "median of nothing" }
        val sorted = amounts.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    /** Expenses in [recent] that are unusual against [history] (past expense amounts per category, positive minor units). */
    public fun unusual(
        recent: List<RecentExpense>,
        history: Map<String, List<Long>>,
    ): List<UnusualExpense> =
        recent
            .mapNotNull { e ->
                val past = history[e.categoryId].orEmpty()
                if (past.size < UNUSUAL_MIN_SAMPLES || e.amount.minor < UNUSUAL_MIN_MINOR) return@mapNotNull null
                val typical = median(past)
                if (e.amount.minor >
                    Math.multiplyExact(typical, UNUSUAL_MULTIPLE)
                ) {
                    UnusualExpense(e, Money(typical, e.amount.currency))
                } else {
                    null
                }
            }.sortedByDescending { it.expense.amount.minor }

    /** Average of (income − spending) over [months] (each a pair of income and spending), floored. Null with no data. */
    public fun savingCapacity(
        months: List<Pair<Money, Money>>,
        currency: CurrencyCode,
    ): SavingCapacity? {
        if (months.isEmpty()) return null
        val total = months.fold(0L) { acc, (income, spending) -> Math.addExact(acc, income.minor - spending.minor) }
        return SavingCapacity(Money(Math.floorDiv(total, months.size.toLong()), currency), months.size)
    }

    /** The [n] largest positive amounts, largest first (ties by id for a stable order). */
    public fun top(
        amounts: Map<String, Money>,
        n: Int,
    ): List<Pair<String, Money>> =
        amounts.entries
            .filter { it.value.isPositive }
            .sortedWith(compareByDescending<Map.Entry<String, Money>> { it.value.minor }.thenBy { it.key })
            .take(n)
            .map { it.key to it.value }
}
