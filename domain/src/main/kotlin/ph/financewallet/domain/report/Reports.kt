package ph.financewallet.domain.report

import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.AccountDirectory
import ph.financewallet.domain.ledger.LedgerTransaction
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** Calendar report ranges (RPT-02). Budget-period ranges come from the budget's own periods. */
public enum class RangeKind { WEEK, MONTH, QUARTER, YEAR }

public object ReportRanges {
    /** The [kind] range containing [date]. Weeks start on Monday (ISO). */
    public fun containing(
        kind: RangeKind,
        date: LocalDate,
    ): Period =
        when (kind) {
            RangeKind.WEEK -> {
                val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Period(start, start.plusDays(DAYS_IN_WEEK - 1))
            }

            RangeKind.MONTH -> {
                YearMonth.from(date).let { Period(it.atDay(1), it.atEndOfMonth()) }
            }

            RangeKind.QUARTER -> {
                val q = date.get(IsoFields.QUARTER_OF_YEAR)
                val first = YearMonth.of(date.year, (q - 1) * MONTHS_IN_QUARTER + 1)
                Period(first.atDay(1), first.plusMonths(MONTHS_IN_QUARTER - 1L).atEndOfMonth())
            }

            RangeKind.YEAR -> {
                Period(LocalDate.of(date.year, 1, 1), LocalDate.of(date.year, MONTHS_IN_YEAR, LAST_DAY_DEC))
            }
        }

    /** Consecutive [kind] ranges covering [from]..[to], each clipped to that span. */
    public fun buckets(
        kind: RangeKind,
        from: LocalDate,
        to: LocalDate,
    ): List<Period> {
        require(!to.isBefore(from)) { "to must not be before from" }
        val out = mutableListOf<Period>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            val r = containing(kind, cursor)
            out += Period(maxOf(r.start, from), minOf(r.endInclusive, to))
            cursor = r.endInclusive.plusDays(1)
        }
        return out
    }

    private const val DAYS_IN_WEEK = 7L
    private const val MONTHS_IN_QUARTER = 3
    private const val MONTHS_IN_YEAR = 12
    private const val LAST_DAY_DEC = 31
}

/** Money in and out of the user's asset accounts for a range (cash flow). */
public data class CashFlow(
    public val inflow: Money,
    public val outflow: Money,
) {
    public val net: Money get() = inflow - outflow
}

/**
 * Reference implementations of the reports that are not already in LedgerCalculator (RPT-01/03). The data layer
 * computes the same figures with SQL; tests cross-check both on random ledgers (AC-ADV-6).
 */
public class ReportCalculator(
    private val accounts: AccountDirectory,
) {
    /**
     * Spending per top-level category: each subcategory's spending is added to its parent ([parentOf] returns null
     * for top-level categories). Categories with net zero are omitted.
     */
    public fun rollUp(
        byCategory: Map<String, Money>,
        parentOf: (String) -> String?,
    ): Map<String, Money> =
        byCategory.entries
            .groupBy({ parentOf(it.key) ?: it.key }, { it.value })
            .mapValues { (_, amounts) -> amounts.reduce { a, b -> a + b } }
            .filterValues { !it.isZero }

    /**
     * Spending per paying account: each transaction's net expense-category amount is attributed to its real account on
     * the opposite side (the account money left; for refunds, the account it returned to). Asset accounts win over
     * liabilities when both are present (e.g. the interest part of a debt payment is paid from the asset).
     */
    public fun spendingByAccount(
        range: Period,
        transactions: Iterable<LedgerTransaction>,
    ): Map<String, Money> {
        val out = HashMap<String, Money>()
        transactions.filter { it.isEffective && it.txnDate in range }.forEach { t ->
            val expense = t.entries.filter { accounts.find(it.accountId)?.accountClass == AccountClass.EXPENSE }
            if (expense.isEmpty()) return@forEach
            val spent = expense.map { it.amount }.reduce { a, b -> a + b }
            if (spent.isZero) return@forEach
            val real = t.entries.filter { accounts.find(it.accountId)?.accountClass?.isReal == true }
            val opposite = real.filter { it.amount.isNegative == spent.isPositive }
            val payer =
                opposite.firstOrNull { accounts.find(it.accountId)?.accountClass == AccountClass.ASSET }
                    ?: opposite.firstOrNull()
                    ?: real.first()
            out[payer.accountId] = (out[payer.accountId] ?: Money.zero(spent.currency)) + spent
        }
        return out.filterValues { !it.isZero }
    }

    /**
     * Cash flow of asset accounts in [currency] (RPT-01): every entry on an asset account counts, except transactions
     * that only move money between the user's own asset accounts in one currency (two or more real entries, all on
     * assets). Hence net cash flow = change of the total asset balance in [currency] over the range. A cross-currency
     * transfer is counted, because it changes the balance held in each currency.
     */
    public fun cashFlow(
        range: Period,
        currency: CurrencyCode,
        transactions: Iterable<LedgerTransaction>,
    ): CashFlow {
        var inflow = 0L
        var outflow = 0L
        transactions.filter { it.isEffective && it.txnDate in range }.forEach { t ->
            val real = t.entries.filter { accounts.find(it.accountId)?.accountClass?.isReal == true }
            val internalMove =
                real.size > 1 &&
                    real.all { accounts.find(it.accountId)?.accountClass == AccountClass.ASSET } &&
                    real.map { it.amount.currency }.distinct().size == 1
            if (internalMove) return@forEach
            real
                .filter { accounts.find(it.accountId)?.accountClass == AccountClass.ASSET && it.amount.currency == currency }
                .forEach { e ->
                    if (e.amount.isPositive) {
                        inflow = Math.addExact(inflow, e.amount.minor)
                    } else {
                        outflow =
                            Math.subtractExact(outflow, e.amount.minor)
                    }
                }
        }
        return CashFlow(Money(inflow, currency), Money(outflow, currency))
    }
}

/** One recorded value of a user-entered asset (AST-01). */
public data class Valuation(
    public val assetId: String,
    public val asOf: LocalDate,
    public val value: Money,
)

/** Net worth with asset valuations (docs/10-domain-model.md §8, NW-01/02). */
public object NetWorthCalculator {
    /** Σ of each asset's latest valuation on or before [asOf], per currency. Assets without one count as nothing. */
    public fun valuationTotals(
        valuations: Iterable<Valuation>,
        asOf: LocalDate,
    ): Map<CurrencyCode, Money> =
        valuations
            .filter { !it.asOf.isAfter(asOf) }
            .groupBy { it.assetId }
            .values
            .map { vs -> vs.maxBy { it.asOf }.value }
            .groupBy { it.currency }
            .mapValues { (c, vs) -> Money.sum(vs, c) }

    /** Account balances plus valuation totals, per currency. Currencies are never mixed. */
    public fun combine(
        accountTotals: Map<CurrencyCode, Money>,
        valuationTotals: Map<CurrencyCode, Money>,
    ): Map<CurrencyCode, Money> =
        (accountTotals.keys + valuationTotals.keys).associateWith { c ->
            (accountTotals[c] ?: Money.zero(c)) + (valuationTotals[c] ?: Money.zero(c))
        }

    /** Month ends from [from]'s month through [to]'s month; the last one is [to] itself if the month is not over. */
    public fun monthEnds(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        require(!to.isBefore(from)) { "to must not be before from" }
        val out = mutableListOf<LocalDate>()
        var m = YearMonth.from(from)
        while (!m.isAfter(YearMonth.from(to))) {
            out += minOf(m.atEndOfMonth(), to)
            m = m.plusMonths(1)
        }
        return out
    }
}
