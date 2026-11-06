package ph.financewallet.data.insight

import ph.financewallet.data.advanced.OccurrenceState
import ph.financewallet.data.advanced.RecurringService
import ph.financewallet.data.advanced.ReportService
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.budget.GoalService
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.insight.BillsCoverage
import ph.financewallet.domain.insight.CategoryChange
import ph.financewallet.domain.insight.InsightRules
import ph.financewallet.domain.insight.RecentExpense
import ph.financewallet.domain.insight.SavingCapacity
import ph.financewallet.domain.insight.UnusualExpense
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth

/** "Where did my money go this month?" in figures (all from the ledger, nothing estimated). */
data class MonthFlow(
    val income: Money,
    val spending: Money,
    /** Net money moved into savings accounts (savings deposits minus withdrawals). */
    val movedToSavings: Money,
) {
    /** income − spending − moved to savings: what is left from this month's income (negative = drew on earlier money). */
    val leftOver: Money get() = income - spending - movedToSavings
}

/** Everything the Insights screen shows. Only [savingCapacity] is an estimate (INS-02). */
data class InsightsReport(
    val period: Period,
    val previousPeriod: Period,
    val flow: MonthFlow,
    val topCategories: List<Pair<String, Money>>,
    val increases: List<CategoryChange>,
    val unusual: List<UnusualExpense>,
    val bills: BillsCoverage,
    val savingCapacity: SavingCapacity?,
    val categoryNames: Map<String, String>,
)

/**
 * On-device insights (ADR-0012, INS-01..03): reads the ledger, recurring rules and goals, and applies the deterministic
 * rules of [InsightRules]. It never writes. Periods: this calendar month to date versus the same days of last month.
 */
class InsightsService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
    private val ledger: LedgerService,
    private val reports: ReportService,
    private val recurring: RecurringService,
    private val goals: GoalService,
) {
    suspend fun report(currency: CurrencyCode = CurrencyCode.PHP): InsightsReport {
        val today = ctx.today()
        val period = Period(today.withDayOfMonth(1), today)
        val lastMonth = YearMonth.from(today).minusMonths(1)
        val previous = Period(lastMonth.atDay(1), lastMonth.atDay(minOf(today.dayOfMonth, lastMonth.lengthOfMonth())))
        val current = reports.spendingByCategory(period, currency).associate { it.categoryId to it.total }
        val before = reports.spendingByCategory(previous, currency).associate { it.categoryId to it.total }
        val names = db.ledgerDao().allLedgerAccounts().associate { it.id to it.name }
        return InsightsReport(
            period = period,
            previousPeriod = previous,
            flow = flow(period, currency),
            topCategories = InsightRules.top(current, TOP),
            increases = InsightRules.categoryChanges(current, before, currency),
            unusual = unusual(period, currency),
            bills = bills(today, currency),
            savingCapacity = InsightRules.savingCapacity(lastFullMonths(today, currency), currency),
            categoryNames = names,
        )
    }

    private suspend fun flow(
        period: Period,
        currency: CurrencyCode,
    ): MonthFlow {
        val summary = ledger.summary(period.start, period.endInclusive)[currency]
        val directory = ledger.directory()
        var moved = 0L
        db.advancedDao().entriesBetween(period.start.toString(), period.endInclusive.toString()).forEach { e ->
            val type = TxnType.valueOf(e.type)
            val isSavingsTarget = directory.find(e.accountId)?.type?.name == "SAVINGS" && e.currency == currency.code
            if (isSavingsTarget && (type == TxnType.SAVINGS_CONTRIBUTION || type == TxnType.SAVINGS_WITHDRAWAL)) moved += e.amountMinor
        }
        return MonthFlow(summary?.income ?: Money.zero(currency), summary?.spending ?: Money.zero(currency), Money(moved, currency))
    }

    /** Expenses this period versus each category's expenses in the 90 days before it. */
    private suspend fun unusual(
        period: Period,
        currency: CurrencyCode,
    ): List<UnusualExpense> {
        val directory = ledger.directory()

        suspend fun expenses(range: Period): List<RecentExpense> =
            db
                .advancedDao()
                .entriesBetween(range.start.toString(), range.endInclusive.toString())
                .filter { it.type == TxnType.EXPENSE.name && it.currency == currency.code && it.amountMinor > 0 }
                .filter { directory.find(it.accountId)?.accountClass == AccountClass.EXPENSE }
                .map { RecentExpense(it.txnId, it.accountId, LocalDate.parse(it.txnDate), Money(it.amountMinor, currency)) }
        val history =
            expenses(Period(period.start.minusDays(HISTORY_DAYS), period.start.minusDays(1))).groupBy({
                it.categoryId
            }, { it.amount.minor })
        return InsightRules.unusual(expenses(period), history)
    }

    private suspend fun bills(
        today: LocalDate,
        currency: CurrencyCode,
    ): BillsCoverage {
        recurring.materialize()
        val due =
            recurring
                .upcoming(today.minusDays(OVERDUE_DAYS), today.plusDays(BILLS_AHEAD))
                .filter { it.kind != RuleKind.EXPECTED_INCOME && it.amount.currency == currency }
                .filter {
                    it.state == OccurrenceState.UPCOMING || it.state == OccurrenceState.DUE_TODAY ||
                        it.state == OccurrenceState.OVERDUE
                }
        return BillsCoverage(Money.sum(due.map { it.amount }, currency), goals.freeMoney(currency))
    }

    /** Income and spending of the last three complete calendar months that have any income or spending. */
    private suspend fun lastFullMonths(
        today: LocalDate,
        currency: CurrencyCode,
    ): List<Pair<Money, Money>> =
        (1..FULL_MONTHS).mapNotNull { back ->
            val m = YearMonth.from(today).minusMonths(back)
            ledger.summary(m.atDay(1), m.atEndOfMonth())[currency]?.let { it.income to it.spending }?.takeIf { (i, s) ->
                !i.isZero ||
                    !s.isZero
            }
        }

    private companion object {
        const val TOP = 5
        const val HISTORY_DAYS = 90L
        const val BILLS_AHEAD = 7L
        const val OVERDUE_DAYS = 31L
        const val FULL_MONTHS = 3L
    }
}
