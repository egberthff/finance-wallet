package ph.financewallet.domain.budget

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Closed-form (non-iterative) budget period computation (docs/10-domain-model.md §5.1).
 * All inputs are calendar dates in the user's zone; no time-of-day is involved.
 */
public object PeriodCalculator {

    /** The period of [rule] containing [date], or null if [date] is before the first period (or outside a custom period). */
    public fun periodContaining(rule: PeriodRule, date: LocalDate): Period? = when (rule) {
        is PeriodRule.Weekly -> fixedLength(rule.anchorDate, 7, date)
        is PeriodRule.Biweekly -> fixedLength(rule.anchorDate, 14, date)
        is PeriodRule.EveryNDays -> fixedLength(rule.anchorDate, rule.days.toLong(), date)
        is PeriodRule.Monthly -> monthly(rule, date)
        is PeriodRule.SemiMonthly -> semiMonthly(rule, date)
        is PeriodRule.CustomOnce -> Period(rule.start, rule.endInclusive).takeIf { date in it }
    }

    /** The first period of [rule]. */
    public fun firstPeriod(rule: PeriodRule): Period {
        val start = when (rule) {
            is PeriodRule.Monthly -> firstMonthlyStart(rule)
            is PeriodRule.SemiMonthly -> firstSemiMonthlyStart(rule)
            else -> rule.anchorDate
        }
        return checkNotNull(periodContaining(rule, start)) { "first period must exist" }
    }

    /** The period after [period], or null for [PeriodRule.CustomOnce]. */
    public fun next(rule: PeriodRule, period: Period): Period? =
        if (rule is PeriodRule.CustomOnce) null else periodContaining(rule, period.endInclusive.plusDays(1))

    /** All periods overlapping [from]..[to] (inclusive), in order. */
    public fun periodsOverlapping(rule: PeriodRule, from: LocalDate, to: LocalDate): List<Period> {
        require(!to.isBefore(from)) { "to must not be before from" }
        val result = mutableListOf<Period>()
        var current = periodContaining(rule, from)
            ?: firstPeriod(rule).takeIf { it.start.isAfter(from) && !it.start.isAfter(to) }
            ?: return result
        while (!current.start.isAfter(to)) {
            result += current
            current = next(rule, current) ?: break
        }
        return result
    }

    internal fun clampDay(day: Int, month: YearMonth): LocalDate = month.atDay(minOf(day, month.lengthOfMonth()))

    private fun fixedLength(anchor: LocalDate, length: Long, date: LocalDate): Period? {
        val offset = ChronoUnit.DAYS.between(anchor, date)
        if (offset < 0) return null
        val start = anchor.plusDays(Math.floorDiv(offset, length) * length)
        return Period(start, start.plusDays(length - 1))
    }

    private fun firstMonthlyStart(rule: PeriodRule.Monthly): LocalDate {
        val month = YearMonth.from(rule.anchorDate)
        val candidate = clampDay(rule.anchorDay, month)
        return if (candidate.isBefore(rule.anchorDate)) clampDay(rule.anchorDay, month.plusMonths(1)) else candidate
    }

    private fun monthly(rule: PeriodRule.Monthly, date: LocalDate): Period? {
        val month = YearMonth.from(date)
        var start = clampDay(rule.anchorDay, month)
        if (date.isBefore(start)) start = clampDay(rule.anchorDay, month.minusMonths(1))
        if (start.isBefore(firstMonthlyStart(rule))) return null
        val nextStart = clampDay(rule.anchorDay, YearMonth.from(start).plusMonths(1))
        return Period(start, nextStart.minusDays(1))
    }

    private fun semiMonthlyStarts(rule: PeriodRule.SemiMonthly, month: YearMonth): List<LocalDate> =
        listOf(clampDay(rule.day1, month), clampDay(rule.day2, month))

    private fun firstSemiMonthlyStart(rule: PeriodRule.SemiMonthly): LocalDate {
        val month = YearMonth.from(rule.anchorDate)
        return (semiMonthlyStarts(rule, month) + semiMonthlyStarts(rule, month.plusMonths(1)))
            .first { !it.isBefore(rule.anchorDate) }
    }

    private fun semiMonthly(rule: PeriodRule.SemiMonthly, date: LocalDate): Period? {
        val month = YearMonth.from(date)
        val starts = (-1L..1L).flatMap { semiMonthlyStarts(rule, month.plusMonths(it)) }
        val index = starts.indexOfLast { !it.isAfter(date) }
        val start = starts[index]
        if (start.isBefore(firstSemiMonthlyStart(rule))) return null
        return Period(start, starts[index + 1].minusDays(1))
    }
}
