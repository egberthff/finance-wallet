package ph.financewallet.app.ui.budgets

import ph.financewallet.app.R
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.budget.PeriodCalculator
import ph.financewallet.domain.budget.PeriodRule
import java.time.LocalDate

/** The budget period types offered in the UI (BUD-01/02), each mapping to one [PeriodRule]. */
enum class PeriodChoice(
    val label: Int,
) {
    MONTHLY(R.string.period_monthly),
    SEMI_MONTHLY(R.string.period_semi_monthly),
    WEEKLY(R.string.period_weekly),
    BIWEEKLY(R.string.period_biweekly),
    EVERY_N_DAYS(R.string.period_every_n_days),
    CUSTOM_ONCE(R.string.period_custom_once),
}

/**
 * What the user typed for a budget period. [toRule] turns it into a [PeriodRule] whose first period starts on or after
 * [earliest] (today for a new budget; the rule-change date for an edit), or returns a message resource. All date
 * arithmetic is done by [PeriodCalculator], so the preview shows exactly the periods the budget will use.
 */
data class PeriodForm(
    val choice: PeriodChoice = PeriodChoice.MONTHLY,
    val day1: String = "1",
    val day2: String = "16",
    val everyNDays: String = "10",
    val start: LocalDate? = null,
    val end: LocalDate? = null,
) {
    /**
     * For a new budget ([forNewBudget]) the first period is the one that *contains* [earliest], so the budget can be
     * used today. For a rule change, periods start on or after [earliest] (the current period is never split).
     */
    fun toRule(
        earliest: LocalDate,
        forNewBudget: Boolean,
    ): Pair<PeriodRule?, Int?> {
        val startDate = start ?: earliest
        return when (choice) {
            PeriodChoice.MONTHLY -> monthly(earliest, forNewBudget)
            PeriodChoice.SEMI_MONTHLY -> semiMonthly(earliest, forNewBudget)
            PeriodChoice.WEEKLY -> dated(startDate, earliest, forNewBudget) { PeriodRule.Weekly(it) }
            PeriodChoice.BIWEEKLY -> dated(startDate, earliest, forNewBudget) { PeriodRule.Biweekly(it) }
            PeriodChoice.EVERY_N_DAYS -> everyNDays(startDate, earliest, forNewBudget)
            PeriodChoice.CUSTOM_ONCE -> customOnce(startDate, earliest, forNewBudget)
        }
    }

    private fun monthly(
        earliest: LocalDate,
        forNewBudget: Boolean,
    ): Pair<PeriodRule?, Int?> {
        val day = day(day1, PeriodRule.MAX_DAY_OF_MONTH) ?: return null to R.string.err_start_day
        return monthlyLike(earliest, forNewBudget) { anchor -> PeriodRule.Monthly(anchor, day) }
    }

    private fun semiMonthly(
        earliest: LocalDate,
        forNewBudget: Boolean,
    ): Pair<PeriodRule?, Int?> {
        val first = day(day1, PeriodRule.MAX_SEMI_MONTHLY_DAY1)
        val second = day(day2, PeriodRule.MAX_DAY_OF_MONTH)
        if (first == null || second == null || second <= first) return null to R.string.err_semi_monthly_days
        return monthlyLike(earliest, forNewBudget) { anchor -> PeriodRule.SemiMonthly(anchor, first, second) }
    }

    private fun everyNDays(
        startDate: LocalDate,
        earliest: LocalDate,
        forNewBudget: Boolean,
    ): Pair<PeriodRule?, Int?> {
        val n = day(everyNDays, PeriodRule.MAX_EVERY_N_DAYS) ?: return null to R.string.err_every_n_days
        return dated(startDate, earliest, forNewBudget) { PeriodRule.EveryNDays(it, n) }
    }

    private fun customOnce(
        startDate: LocalDate,
        earliest: LocalDate,
        forNewBudget: Boolean,
    ): Pair<PeriodRule?, Int?> {
        val endDate = end
        return when {
            endDate == null || endDate.isBefore(startDate) -> null to R.string.err_custom_end
            !forNewBudget && startDate.isBefore(earliest) -> null to R.string.err_rule_start_too_early
            else -> PeriodRule.CustomOnce(startDate, endDate) to null
        }
    }

    private fun day(
        text: String,
        max: Int,
    ): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..max }

    /** Monthly and semi-monthly: the anchor is the start of the period containing (new) or following (change) [earliest]. */
    private fun monthlyLike(
        earliest: LocalDate,
        forNewBudget: Boolean,
        build: (LocalDate) -> PeriodRule,
    ): Pair<PeriodRule?, Int?> {
        if (!forNewBudget) return build(earliest) to null
        val probe = build(earliest.minusMonths(PROBE_MONTHS))
        val containing = checkNotNull(PeriodCalculator.periodContaining(probe, earliest))
        return build(containing.start) to null
    }

    private fun dated(
        startDate: LocalDate,
        earliest: LocalDate,
        forNewBudget: Boolean,
        build: (LocalDate) -> PeriodRule,
    ): Pair<PeriodRule?, Int?> =
        when {
            !forNewBudget && startDate.isBefore(earliest) -> null to R.string.err_rule_start_too_early
            forNewBudget && startDate.isAfter(earliest) -> null to R.string.err_rule_start_future
            else -> build(startDate) to null
        }

    private companion object {
        const val PROBE_MONTHS = 2L
    }
}

/**
 * [count] periods of [rule] from the one containing [from] (or the rule's first period, when [from] is before it), for the
 * "Next periods" preview. Fewer for a one-off period.
 */
fun previewPeriods(
    rule: PeriodRule,
    from: LocalDate? = null,
    count: Int = PREVIEW_COUNT,
): List<Period> {
    val first = from?.let { PeriodCalculator.periodContaining(rule, it) } ?: PeriodCalculator.firstPeriod(rule)
    val result = mutableListOf(first)
    while (result.size < count) result += PeriodCalculator.next(rule, result.last()) ?: break
    return result
}

/** The form that describes an existing rule, for the edit screen. */
fun PeriodRule.toForm(): PeriodForm =
    when (this) {
        is PeriodRule.Monthly -> PeriodForm(PeriodChoice.MONTHLY, day1 = anchorDay.toString())
        is PeriodRule.SemiMonthly -> PeriodForm(PeriodChoice.SEMI_MONTHLY, day1 = day1.toString(), day2 = day2.toString())
        is PeriodRule.Weekly -> PeriodForm(PeriodChoice.WEEKLY, start = anchorDate)
        is PeriodRule.Biweekly -> PeriodForm(PeriodChoice.BIWEEKLY, start = anchorDate)
        is PeriodRule.EveryNDays -> PeriodForm(PeriodChoice.EVERY_N_DAYS, everyNDays = days.toString(), start = anchorDate)
        is PeriodRule.CustomOnce -> PeriodForm(PeriodChoice.CUSTOM_ONCE, start = start, end = endInclusive)
    }

const val PREVIEW_COUNT = 3
