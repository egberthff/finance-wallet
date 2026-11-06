package ph.financewallet.app.ui.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.data.advanced.CategoryReportLine
import ph.financewallet.data.advanced.NetWorthBreakdown
import ph.financewallet.data.advanced.RecurringCost
import ph.financewallet.data.ledger.TransactionListItem
import ph.financewallet.data.ledger.TxnFilter
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.ledger.PeriodSummary
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.report.CashFlow
import ph.financewallet.domain.report.RangeKind
import ph.financewallet.domain.report.ReportRanges
import java.io.Writer
import java.time.LocalDate

data class AccountSpending(
    val name: String,
    val amount: Money,
)

data class ReportsState(
    val loading: Boolean = true,
    val kind: RangeKind = RangeKind.MONTH,
    val range: Period? = null,
    val summary: PeriodSummary? = null,
    val spending: List<CategoryReportLine> = emptyList(),
    val income: List<CategoryReportLine> = emptyList(),
    val byAccount: List<AccountSpending> = emptyList(),
    val cashFlow: CashFlow? = null,
    val netWorth: NetWorthBreakdown? = null,
    val netWorthHistory: List<Pair<LocalDate, Money>> = emptyList(),
    val recurring: List<RecurringCost> = emptyList(),
    /** Transactions behind a tapped category total (RPT-03). */
    val drillDown: Pair<String, List<TransactionListItem>>? = null,
)

/**
 * Reports tab (RPT-01..04). Every number comes from [ph.financewallet.data.advanced.ReportService], computed from
 * posted transactions; the screen only formats. Ranges move by whole weeks/months/quarters/years (RPT-02).
 */
class ReportsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = mutable
    private var anchor: LocalDate = s.ctx.today()

    fun select(kind: RangeKind) {
        viewModelScope.launch { mutable.value = load(kind, anchor) }
    }

    /** Moves the range by one unit ([steps] = −1 for previous, +1 for next). */
    fun shift(steps: Int) {
        val current = mutable.value.range ?: return
        anchor = if (steps < 0) current.start.minusDays(1) else current.endInclusive.plusDays(1)
        viewModelScope.launch { mutable.value = load(mutable.value.kind, anchor) }
    }

    fun refresh() = select(mutable.value.kind)

    suspend fun load(
        kind: RangeKind,
        date: LocalDate,
    ): ReportsState {
        val php = CurrencyCode.PHP
        val range = ReportRanges.containing(kind, date)
        val names = s.accounts.accounts(includeArchived = true).associate { it.id to it.name }
        val nwEnd = minOf(range.endInclusive, s.ctx.today())
        return ReportsState(
            loading = false,
            kind = kind,
            range = range,
            summary = s.ledger.summary(range.start, range.endInclusive)[php],
            spending = s.reports.spendingByCategory(range, php),
            income = s.reports.incomeByCategory(range, php),
            byAccount =
                s.reports
                    .spendingByAccount(range)
                    .filterValues { it.currency == php }
                    .map { (id, m) -> AccountSpending(names[id] ?: id, m) }
                    .sortedByDescending { it.amount.minor },
            cashFlow =
                s.reports
                    .cashFlow(kind, range.start, range.endInclusive, php)
                    .singleOrNull()
                    ?.second,
            netWorth = s.reports.netWorth(nwEnd).firstOrNull { it.currency == php },
            netWorthHistory = s.reports.netWorthHistory(nwEnd.minusMonths(HISTORY_MONTHS).withDayOfMonth(1), nwEnd, php),
            recurring = s.recurring.costs(),
        )
    }

    /** RPT-03: the posted transactions that make up [line] in the current range. */
    fun drillDown(line: CategoryReportLine) {
        val range = mutable.value.range ?: return
        viewModelScope.launch {
            val items =
                s.transactions
                    .page(
                        limit = DRILL_LIMIT,
                        filter = TxnFilter(line.categoryId, range.start, range.endInclusive, postedOnly = true),
                    ).items
            mutable.value = mutable.value.copy(drillDown = line.name to items)
        }
    }

    fun closeDrillDown() {
        mutable.value = mutable.value.copy(drillDown = null)
    }

    /** UC-30: writes the current range as CSV to a location the user picked (plaintext; the UI warns first). */
    suspend fun export(out: Writer): Int {
        val range = checkNotNull(mutable.value.range)
        return s.csv.exportTransactions(range.start, range.endInclusive, out)
    }

    private companion object {
        const val HISTORY_MONTHS = 11L
        const val DRILL_LIMIT = 200
    }
}
