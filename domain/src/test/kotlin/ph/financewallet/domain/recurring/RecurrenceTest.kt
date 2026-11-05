package ph.financewallet.domain.recurring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Due dates for bills, subscriptions and expected income (docs/10-domain-model.md §9, AC-ADV-4). */
class RecurrenceTest {
    private fun d(s: String) = LocalDate.parse(s)

    private fun dates(
        r: Recurrence,
        from: String,
        to: String,
        end: String? = null,
    ) = RecurrenceCalculator.dueDates(r, d(from), d(to), end?.let(::d)).map { it.toString() }

    @Test
    fun `monthly on the 31st clamps to each month's last day, including leap February`() {
        assertEquals(
            listOf("2028-01-31", "2028-02-29", "2028-03-31", "2028-04-30"),
            dates(Recurrence.Monthly(d("2028-01-31"), 31), "2028-01-01", "2028-04-30"),
        )
        assertEquals(listOf("2027-02-28"), dates(Recurrence.Monthly(d("2027-01-01"), 31), "2027-02-01", "2027-02-28"))
    }

    @Test
    fun `the first due date is on or after the anchor`() {
        assertEquals(listOf("2026-12-05", "2027-01-05"), dates(Recurrence.Monthly(d("2026-11-20"), 5), "2026-11-01", "2027-01-31"))
        assertEquals(listOf("2026-11-20", "2026-11-27"), dates(Recurrence.Weekly(d("2026-11-20")), "2026-01-01", "2026-11-30"))
    }

    @Test
    fun `semi-monthly 15th and end of month (PH paydays)`() {
        assertEquals(
            listOf("2027-02-15", "2027-02-28", "2027-03-15", "2027-03-31"),
            dates(Recurrence.SemiMonthly(d("2027-01-01"), 15, 31), "2027-02-01", "2027-03-31"),
        )
    }

    @Test
    fun `quarterly counts from the anchor month, yearly on a fixed date`() {
        assertEquals(
            listOf("2026-11-10", "2027-02-10", "2027-05-10", "2027-08-10"),
            dates(Recurrence.Quarterly(d("2026-11-10"), 10), "2026-11-01", "2027-08-31"),
        )
        assertEquals(
            listOf("2027-02-28", "2028-02-29"),
            dates(Recurrence.Yearly(d("2026-03-01"), 2, 29), "2026-01-01", "2028-12-31"),
        )
    }

    @Test
    fun `fixed-length rules and the end date`() {
        assertEquals(listOf("2026-12-25", "2027-01-08"), dates(Recurrence.Biweekly(d("2026-12-11")), "2026-12-20", "2027-01-10"))
        assertEquals(
            listOf("2026-11-01", "2026-11-11"),
            dates(Recurrence.EveryNDays(d("2026-11-01"), 10), "2026-11-01", "2026-12-31", "2026-11-15"),
        )
        assertEquals(emptyList<String>(), dates(Recurrence.Weekly(d("2027-01-01")), "2026-11-01", "2026-12-31"))
        assertEquals(d("2026-11-27"), RecurrenceCalculator.nextAfter(Recurrence.Weekly(d("2026-11-20")), d("2026-11-20")))
        assertNull(RecurrenceCalculator.nextAfter(Recurrence.Weekly(d("2026-11-20")), d("2026-11-20"), endDate = d("2026-11-26")))
    }

    @Test
    fun `invalid rules are rejected`() {
        assertThrows<IllegalArgumentException> { Recurrence.Monthly(d("2026-01-01"), 32) }
        assertThrows<IllegalArgumentException> { Recurrence.SemiMonthly(d("2026-01-01"), 28, 31) }
        assertThrows<IllegalArgumentException> { Recurrence.SemiMonthly(d("2026-01-01"), 15, 15) }
        assertThrows<IllegalArgumentException> { Recurrence.Yearly(d("2026-01-01"), 13, 1) }
        assertThrows<IllegalArgumentException> { Recurrence.EveryNDays(d("2026-01-01"), 0) }
        assertThrows<IllegalArgumentException> {
            RecurrenceCalculator.dueDates(Recurrence.Weekly(d("2026-01-01")), d("2026-02-01"), d("2026-01-01"))
        }
    }

    /** AC-ADV-4: 10 years, every day 1–31: strictly increasing, exactly one per month (two for semi-monthly), clamped. */
    @Test
    fun `ten years of monthly-family dates are well formed for every day of month`() {
        val from = d("2026-01-01")
        val to = d("2035-12-31")
        val months = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1
        for (day in 1..31) {
            val monthly = RecurrenceCalculator.dueDates(Recurrence.Monthly(from, day), from, to)
            assertEquals(months, monthly.size.toLong(), "monthly day $day")
            assertTrue(monthly.zipWithNext().all { (a, b) -> a.isBefore(b) })
            assertTrue(monthly.all { it.dayOfMonth == minOf(day, YearMonth.from(it).lengthOfMonth()) })
            assertEquals(months / 3, RecurrenceCalculator.dueDates(Recurrence.Quarterly(from, day), from, to).size.toLong())
            assertEquals(10, RecurrenceCalculator.dueDates(Recurrence.Yearly(from, 2, day), from, to).size)
            if (day <= Recurrence.MAX_SEMI_DAY1) {
                val semi = RecurrenceCalculator.dueDates(Recurrence.SemiMonthly(from, day, 31), from, to)
                assertEquals(months * 2, semi.size.toLong(), "semi-monthly $day")
                assertTrue(semi.zipWithNext().all { (a, b) -> a.isBefore(b) })
            }
        }
    }

    @Test
    fun `fixed-length dates have constant spacing and windows agree with one long range`() {
        val r = Recurrence.EveryNDays(d("2026-01-03"), 9)
        val all = RecurrenceCalculator.dueDates(r, d("2026-01-01"), d("2035-12-31"))
        assertTrue(all.zipWithNext().all { (a, b) -> ChronoUnit.DAYS.between(a, b) == 9L })
        val windowed =
            (0 until 120).flatMap { m ->
                val ym = YearMonth.of(2026, 1).plusMonths(m.toLong())
                RecurrenceCalculator.dueDates(r, ym.atDay(1), ym.atEndOfMonth())
            }
        assertEquals(all, windowed)
    }
}
