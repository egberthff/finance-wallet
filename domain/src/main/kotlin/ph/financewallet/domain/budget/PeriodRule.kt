package ph.financewallet.domain.budget

import java.time.LocalDate

/** An inclusive date range. */
public data class Period(
    public val start: LocalDate,
    public val endInclusive: LocalDate,
) {
    init {
        require(!endInclusive.isBefore(start)) { "Period end $endInclusive is before start $start" }
    }

    public operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    /** Number of days in the period, inclusive. */
    public val lengthInDays: Long get() =
        java.time.temporal.ChronoUnit.DAYS
            .between(start, endInclusive) + 1
}

/**
 * How a budget's periods are generated (docs/10-domain-model.md §5.1, PRD BUD-01/02).
 * Periods start at the first period start on or after [anchorDate].
 */
public sealed interface PeriodRule {
    public val anchorDate: LocalDate

    public data class Weekly(
        override val anchorDate: LocalDate,
    ) : PeriodRule

    public data class Biweekly(
        override val anchorDate: LocalDate,
    ) : PeriodRule

    public data class EveryNDays(
        override val anchorDate: LocalDate,
        val days: Int,
    ) : PeriodRule {
        init {
            require(days in 1..MAX_EVERY_N_DAYS) { "days must be in 1..$MAX_EVERY_N_DAYS" }
        }
    }

    /** Monthly on [anchorDay]; days 29–31 clamp to the month's last day. */
    public data class Monthly(
        override val anchorDate: LocalDate,
        val anchorDay: Int,
    ) : PeriodRule {
        init {
            require(anchorDay in 1..MAX_DAY_OF_MONTH) { "anchorDay must be in 1..$MAX_DAY_OF_MONTH" }
        }
    }

    /**
     * Two periods per month, starting on [day1] and [day2] (e.g., 15 and 31 = "15th and end of month").
     * [day1] must be <= 27 so that both starts stay distinct even in a 28-day February.
     */
    public data class SemiMonthly(
        override val anchorDate: LocalDate,
        val day1: Int,
        val day2: Int,
    ) : PeriodRule {
        init {
            require(day1 in 1..MAX_SEMI_MONTHLY_DAY1) { "day1 must be in 1..$MAX_SEMI_MONTHLY_DAY1" }
            require(day2 in (day1 + 1)..MAX_DAY_OF_MONTH) { "day2 must be in ${day1 + 1}..$MAX_DAY_OF_MONTH" }
        }
    }

    public data class CustomOnce(
        val start: LocalDate,
        val endInclusive: LocalDate,
    ) : PeriodRule {
        init {
            require(!endInclusive.isBefore(start)) { "end must not be before start" }
        }

        override val anchorDate: LocalDate get() = start
    }

    public companion object {
        public const val MAX_EVERY_N_DAYS: Int = 366
        public const val MAX_SEMI_MONTHLY_DAY1: Int = 27
        public const val MAX_DAY_OF_MONTH: Int = 31
    }
}
