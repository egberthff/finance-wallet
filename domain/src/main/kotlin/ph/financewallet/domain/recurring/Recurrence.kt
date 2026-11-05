package ph.financewallet.domain.recurring

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * When a bill, subscription, recurring transaction or expected income falls due (docs/10-domain-model.md §9).
 * The first due date is the first matching date on or after [anchorDate]. Day-of-month values past the end of a
 * month use its last day (the same clamping as budget periods, §5.1), e.g. "31st" is Feb 28/29.
 */
public sealed interface Recurrence {
    public val anchorDate: LocalDate

    public data class Weekly(
        override val anchorDate: LocalDate,
    ) : Recurrence

    public data class Biweekly(
        override val anchorDate: LocalDate,
    ) : Recurrence

    public data class EveryNDays(
        override val anchorDate: LocalDate,
        val days: Int,
    ) : Recurrence {
        init {
            require(days in 1..MAX_EVERY_N_DAYS) { "days must be in 1..$MAX_EVERY_N_DAYS" }
        }
    }

    /** Every month on [day]. */
    public data class Monthly(
        override val anchorDate: LocalDate,
        val day: Int,
    ) : Recurrence {
        init {
            require(day in 1..MAX_DAY) { "day must be in 1..$MAX_DAY" }
        }
    }

    /** Twice a month, e.g. 15 and 31 ("15th and end of month", common PH paydays). [day1] ≤ 27 keeps both dates distinct. */
    public data class SemiMonthly(
        override val anchorDate: LocalDate,
        val day1: Int,
        val day2: Int,
    ) : Recurrence {
        init {
            require(day1 in 1..MAX_SEMI_DAY1) { "day1 must be in 1..$MAX_SEMI_DAY1" }
            require(day2 in (day1 + 1)..MAX_DAY) { "day2 must be in ${day1 + 1}..$MAX_DAY" }
        }
    }

    /** Every 3 months on [day], counting from the anchor's month. */
    public data class Quarterly(
        override val anchorDate: LocalDate,
        val day: Int,
    ) : Recurrence {
        init {
            require(day in 1..MAX_DAY) { "day must be in 1..$MAX_DAY" }
        }
    }

    /** Once a year on [month]/[day]. */
    public data class Yearly(
        override val anchorDate: LocalDate,
        val month: Int,
        val day: Int,
    ) : Recurrence {
        init {
            require(month in 1..MONTHS) { "month must be in 1..$MONTHS" }
            require(day in 1..MAX_DAY) { "day must be in 1..$MAX_DAY" }
        }
    }

    public companion object {
        public const val MAX_EVERY_N_DAYS: Int = 366
        public const val MAX_DAY: Int = 31
        public const val MAX_SEMI_DAY1: Int = 27
        private const val MONTHS = 12
    }
}

/** Due dates of a [Recurrence]. Work is proportional to the requested range, never to the time since the anchor. */
public object RecurrenceCalculator {
    private const val WEEK = 7L
    private const val FORTNIGHT = 14L

    /** Two years: longer than any supported gap between two due dates (yearly). */
    private const val DEFAULT_HORIZON_DAYS = 732L
    private const val QUARTER_MONTHS = 3L

    /** Due dates in [from]..[to] inclusive, not after [endDate] when given, in increasing order. */
    public fun dueDates(
        r: Recurrence,
        from: LocalDate,
        to: LocalDate,
        endDate: LocalDate? = null,
    ): List<LocalDate> {
        require(!to.isBefore(from)) { "to must not be before from" }
        val last = if (endDate != null && endDate.isBefore(to)) endDate else to
        val first = if (from.isBefore(r.anchorDate)) r.anchorDate else from
        if (last.isBefore(first)) return emptyList()
        val candidates =
            when (r) {
                is Recurrence.Weekly -> fixed(r.anchorDate, WEEK, first, last)
                is Recurrence.Biweekly -> fixed(r.anchorDate, FORTNIGHT, first, last)
                is Recurrence.EveryNDays -> fixed(r.anchorDate, r.days.toLong(), first, last)
                is Recurrence.Monthly -> monthStep(r.anchorDate, 1, first, last) { clamp(r.day, it) }
                is Recurrence.Quarterly -> monthStep(r.anchorDate, QUARTER_MONTHS, first, last) { clamp(r.day, it) }
                is Recurrence.Yearly -> yearly(r, first, last)
                is Recurrence.SemiMonthly -> semiMonthly(r, first, last)
            }
        return candidates.filter { !it.isBefore(first) && !it.isAfter(last) }
    }

    /** The first due date strictly after [date], searching at most [horizonDays] ahead. */
    public fun nextAfter(
        r: Recurrence,
        date: LocalDate,
        endDate: LocalDate? = null,
        horizonDays: Long = DEFAULT_HORIZON_DAYS,
    ): LocalDate? = dueDates(r, date.plusDays(1), date.plusDays(horizonDays), endDate).firstOrNull()

    private fun clamp(
        day: Int,
        m: YearMonth,
    ): LocalDate = m.atDay(minOf(day, m.lengthOfMonth()))

    private fun fixed(
        anchor: LocalDate,
        step: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        val offset = ChronoUnit.DAYS.between(anchor, from)
        var k = Math.floorDiv(offset + step - 1, step) // first k with anchor + k·step ≥ from
        val out = mutableListOf<LocalDate>()
        var d = anchor.plusDays(k * step)
        while (!d.isAfter(to)) {
            out += d
            k++
            d = anchor.plusDays(k * step)
        }
        return out
    }

    /** Dates in months anchorMonth + k·[step] (k ≥ 0) that fall in [from]..[to], skipping any before the anchor. */
    private fun monthStep(
        anchor: LocalDate,
        step: Long,
        from: LocalDate,
        to: LocalDate,
        dateIn: (YearMonth) -> LocalDate,
    ): List<LocalDate> {
        val anchorMonth = YearMonth.from(anchor)
        val fromOffset = ChronoUnit.MONTHS.between(anchorMonth, YearMonth.from(from))
        var k = maxOf(0L, Math.floorDiv(fromOffset, step))
        val out = mutableListOf<LocalDate>()
        while (true) {
            val d = dateIn(anchorMonth.plusMonths(k * step))
            if (d.isAfter(to)) break
            if (!d.isBefore(anchor)) out += d
            k++
        }
        return out
    }

    private fun yearly(
        r: Recurrence.Yearly,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> = (from.year..to.year).map { clamp(r.day, YearMonth.of(it, r.month)) }.filter { !it.isBefore(r.anchorDate) }

    private fun semiMonthly(
        r: Recurrence.SemiMonthly,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var m = YearMonth.from(from)
        val end = YearMonth.from(to)
        while (!m.isAfter(end)) {
            listOf(clamp(r.day1, m), clamp(r.day2, m)).filter { !it.isBefore(r.anchorDate) }.forEach { out += it }
            m = m.plusMonths(1)
        }
        return out
    }
}
