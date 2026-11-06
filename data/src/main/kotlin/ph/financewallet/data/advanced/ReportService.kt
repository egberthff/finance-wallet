package ph.financewallet.data.advanced

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.ledger.Entry
import ph.financewallet.domain.ledger.LedgerTransaction
import ph.financewallet.domain.ledger.PeriodSummary
import ph.financewallet.domain.ledger.TxnStatus
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.report.CashFlow
import ph.financewallet.domain.report.NetWorthCalculator
import ph.financewallet.domain.report.RangeKind
import ph.financewallet.domain.report.ReportCalculator
import ph.financewallet.domain.report.ReportRanges
import ph.financewallet.domain.report.Valuation
import java.time.LocalDate

/** One top-level category in a report, with its subcategories (RPT-01 "spending by category"). */
data class CategoryReportLine(
    val categoryId: String,
    val name: String,
    /** Set for system accounts (e.g. `expense:interest_fees`) so the UI can show a translated label. */
    val systemKey: String?,
    val total: Money,
    val children: List<CategoryReportChild>,
)

data class CategoryReportChild(
    val categoryId: String,
    val name: String,
    val amount: Money,
)

data class PeriodReport(
    val period: Period,
    val summary: PeriodSummary?,
)

data class NetWorthBreakdown(
    val currency: CurrencyCode,
    /** Σ included asset-account balances. */
    val accounts: Money,
    /** Σ included liability balances (negative = owed). */
    val liabilities: Money,
    /** Σ latest valuations of included user-entered assets. */
    val otherAssets: Money,
) {
    val total: Money get() = accounts + liabilities + otherAssets
}

/**
 * Report query service (RPT-01..03, NW-01/02). Reads the money ledger only, never budgets (BUD-04), so totals of
 * different budgets are never added. Every figure is posted, non-deleted data; tests cross-check each against an
 * independent recompute (AC-ADV-5/6). Work is bounded by the requested range.
 */
class ReportService(
    private val db: FinanceDatabase,
    private val ledger: LedgerService,
) {
    private val dao get() = db.advancedDao()

    /** Income vs expense per [kind] bucket over [from]..[to] in [currency] (RPT-02 ranges). */
    suspend fun incomeVsExpense(
        kind: RangeKind,
        from: LocalDate,
        to: LocalDate,
        currency: CurrencyCode,
    ): List<PeriodReport> =
        ReportRanges.buckets(kind, from, to).map { PeriodReport(it, ledger.summary(it.start, it.endInclusive)[currency]) }

    /** Same figures for any explicit ranges, e.g. a budget's own periods ("budget period" range, RPT-02). */
    suspend fun incomeVsExpense(
        periods: List<Period>,
        currency: CurrencyCode,
    ): List<PeriodReport> = periods.map { PeriodReport(it, ledger.summary(it.start, it.endInclusive)[currency]) }

    /** Spending per top-level category, subcategories rolled up, largest first. Refunds reduce their category. */
    suspend fun spendingByCategory(
        range: Period,
        currency: CurrencyCode,
    ): List<CategoryReportLine> = byCategory("EXPENSE", range, currency, sign = 1)

    /** Income per top-level category, largest first. */
    suspend fun incomeByCategory(
        range: Period,
        currency: CurrencyCode,
    ): List<CategoryReportLine> = byCategory("INCOME", range, currency, sign = -1)

    /** Spending per paying account (see ReportCalculator.spendingByAccount for the attribution rule). */
    suspend fun spendingByAccount(range: Period): Map<String, Money> =
        ReportCalculator(ledger.directory()).spendingByAccount(range, transactionsIn(range))

    /** Cash flow of asset accounts per bucket. Net = change of total asset balance in the currency. */
    suspend fun cashFlow(
        kind: RangeKind,
        from: LocalDate,
        to: LocalDate,
        currency: CurrencyCode,
    ): List<Pair<Period, CashFlow>> {
        val calc = ReportCalculator(ledger.directory())
        val txns = transactionsIn(Period(from, to))
        return ReportRanges.buckets(kind, from, to).map { it to calc.cashFlow(it, currency, txns) }
    }

    /** Net worth on [asOf] per currency (NW-01), components shown separately. */
    suspend fun netWorth(asOf: LocalDate): List<NetWorthBreakdown> {
        val classes = db.ledgerDao().allLedgerAccounts().associate { it.id to it.accountClass }
        val balances = dao.netWorthBalances(asOf.toString())
        val valuations = NetWorthCalculator.valuationTotals(valuationsUpTo(asOf), asOf)
        val currencies = (balances.map { CurrencyCode.of(it.currency) } + valuations.keys).distinct().sortedBy { it.code }
        return currencies.map { c ->
            fun sum(cls: String) = Money(balances.filter { it.currency == c.code && classes[it.accountId] == cls }.sumOf { it.total }, c)
            NetWorthBreakdown(c, sum("ASSET"), sum("LIABILITY"), valuations[c] ?: Money.zero(c))
        }
    }

    /** Month-end net worth in [currency] from [from] to [to] (NW-02); the last point is [to] itself. */
    suspend fun netWorthHistory(
        from: LocalDate,
        to: LocalDate,
        currency: CurrencyCode,
    ): List<Pair<LocalDate, Money>> =
        NetWorthCalculator.monthEnds(from, to).map { d ->
            d to
                (netWorth(d).firstOrNull { it.currency == currency }?.total ?: Money.zero(currency))
        }

    // ---- internals ----------------------------------------------------------------------------------------------

    private suspend fun byCategory(
        accountClass: String,
        range: Period,
        currency: CurrencyCode,
        sign: Int,
    ): List<CategoryReportLine> {
        val totals =
            dao
                .totalsByAccountClass(accountClass, range.start.toString(), range.endInclusive.toString())
                .filter { it.currency == currency.code }
        val accounts = db.ledgerDao().allLedgerAccounts().associateBy { it.id }
        val parents = db.accountDao().categories(includeArchived = true).associate { it.id to it.parentId }
        return totals
            .groupBy { parents[it.accountId] ?: it.accountId }
            .map { (topId, rows) ->
                val top = accounts.getValue(topId)
                CategoryReportLine(
                    categoryId = topId,
                    name = top.name,
                    systemKey = top.systemKey,
                    total = Money(sign * rows.sumOf { it.total }, currency),
                    children =
                        rows
                            .filter { it.accountId != topId }
                            .map {
                                CategoryReportChild(
                                    it.accountId,
                                    accounts.getValue(it.accountId).name,
                                    Money(sign * it.total, currency),
                                )
                            }.sortedByDescending { it.amount.minor },
                )
            }.filter { !it.total.isZero }
            .sortedByDescending { it.total.minor }
    }

    /** Effective transactions dated in [range], rebuilt from one range-bounded entry query. */
    private suspend fun transactionsIn(range: Period): List<LedgerTransaction> =
        dao
            .entriesBetween(range.start.toString(), range.endInclusive.toString())
            .groupBy { it.txnId }
            .map { (id, rows) ->
                LedgerTransaction(
                    id = id,
                    type = TxnType.valueOf(rows.first().type),
                    status = TxnStatus.POSTED,
                    txnDate = LocalDate.parse(rows.first().txnDate),
                    entries = rows.map { Entry(it.accountId, Money(it.amountMinor, CurrencyCode.of(it.currency))) },
                )
            }

    private suspend fun valuationsUpTo(asOf: LocalDate): List<Valuation> {
        val assets = dao.assets().associateBy { it.id }
        return dao.netWorthValuations(asOf.toString()).map { v ->
            Valuation(v.assetId, LocalDate.parse(v.asOfDate), Money(v.valueMinor, CurrencyCode.of(assets.getValue(v.assetId).currency)))
        }
    }
}
