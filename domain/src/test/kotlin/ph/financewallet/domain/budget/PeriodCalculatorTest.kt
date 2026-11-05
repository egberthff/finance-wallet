package ph.financewallet.domain.budget

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.localDate
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PeriodCalculatorTest {
    private fun d(s: String) = LocalDate.parse(s)

    private fun p(
        a: String,
        b: String,
    ) = Period(d(a), d(b))

    @Test
    fun `EX-11 monthly on the 5th is Nov 5 to Dec 4`() {
        val rule = PeriodRule.Monthly(d("2026-01-05"), 5)
        assertEquals(p("2026-11-05", "2026-12-04"), PeriodCalculator.periodContaining(rule, d("2026-12-04")))
        assertEquals(p("2026-12-05", "2027-01-04"), PeriodCalculator.periodContaining(rule, d("2026-12-05")))
    }

    @Test
    fun `EX-12 monthly on the 31st clamps to short months and leap years`() {
        val rule = PeriodRule.Monthly(d("2026-01-31"), 31)
        val periods = PeriodCalculator.periodsOverlapping(rule, d("2026-01-31"), d("2026-05-15"))
        assertEquals(
            listOf(
                p("2026-01-31", "2026-02-27"),
                p("2026-02-28", "2026-03-30"),
                p("2026-03-31", "2026-04-29"),
                p("2026-04-30", "2026-05-30"),
            ),
            periods,
        )
        assertEquals(p("2028-01-31", "2028-02-28"), PeriodCalculator.periodContaining(rule, d("2028-02-15")))
        assertEquals(p("2028-02-29", "2028-03-30"), PeriodCalculator.periodContaining(rule, d("2028-03-01")))
    }

    @Test
    fun `EX-13 period attribution uses the local date in Asia-Manila`() {
        val zone = ZoneId.of("Asia/Manila")
        val rule = PeriodRule.Monthly(d("2026-01-05"), 5)
        val lateNight = LocalDateTime.parse("2026-12-04T23:59").atZone(zone).toLocalDate()
        val midnight = LocalDateTime.parse("2026-12-05T00:00").atZone(zone).toLocalDate()
        assertEquals(p("2026-11-05", "2026-12-04"), PeriodCalculator.periodContaining(rule, lateNight))
        assertEquals(p("2026-12-05", "2027-01-04"), PeriodCalculator.periodContaining(rule, midnight))
    }

    @Test
    fun `EX-14 weekly crosses the year boundary`() {
        val rule = PeriodRule.Weekly(d("2026-12-28"))
        assertEquals(p("2026-12-28", "2027-01-03"), PeriodCalculator.periodContaining(rule, d("2027-01-01")))
    }

    @Test
    fun `biweekly, every-N-days and custom periods`() {
        assertEquals(
            p("2026-11-19", "2026-12-02"),
            PeriodCalculator.periodContaining(PeriodRule.Biweekly(d("2026-11-05")), d("2026-11-30")),
        )
        assertEquals(
            p("2026-11-15", "2026-11-24"),
            PeriodCalculator.periodContaining(PeriodRule.EveryNDays(d("2026-11-05"), 10), d("2026-11-20")),
        )
        val custom = PeriodRule.CustomOnce(d("2026-12-20"), d("2027-01-10"))
        assertEquals(p("2026-12-20", "2027-01-10"), PeriodCalculator.periodContaining(custom, d("2027-01-01")))
        assertNull(PeriodCalculator.periodContaining(custom, d("2027-01-11")))
        assertNull(PeriodCalculator.next(custom, p("2026-12-20", "2027-01-10")))
        assertEquals(emptyList<Period>(), PeriodCalculator.periodsOverlapping(custom, d("2027-02-01"), d("2027-03-01")))
        assertEquals(22L, p("2026-12-20", "2027-01-10").lengthInDays)
    }

    @Test
    fun `semi-monthly 15th and end of month`() {
        val rule = PeriodRule.SemiMonthly(d("2026-01-01"), 15, 31)
        val periods = PeriodCalculator.periodsOverlapping(rule, d("2027-01-20"), d("2027-03-20"))
        assertEquals(
            listOf(
                p("2027-01-15", "2027-01-30"),
                p("2027-01-31", "2027-02-14"),
                p("2027-02-15", "2027-02-27"),
                p("2027-02-28", "2027-03-14"),
                p("2027-03-15", "2027-03-30"),
            ),
            periods,
        )
        // First period starts on the first start date on or after the anchor.
        assertEquals(p("2026-01-15", "2026-01-30"), PeriodCalculator.firstPeriod(rule))
    }

    @Test
    fun `dates before the first period belong to no period`() {
        assertNull(PeriodCalculator.periodContaining(PeriodRule.Weekly(d("2026-11-05")), d("2026-11-04")))
        val monthly = PeriodRule.Monthly(d("2026-11-10"), 5) // first start is Dec 5
        assertEquals(p("2026-12-05", "2027-01-04"), PeriodCalculator.firstPeriod(monthly))
        assertNull(PeriodCalculator.periodContaining(monthly, d("2026-11-20")))
        assertNull(PeriodCalculator.periodContaining(PeriodRule.SemiMonthly(d("2026-11-16"), 1, 16), d("2026-11-16").minusDays(1)))
        assertEquals(
            p("2026-12-05", "2027-01-04"),
            PeriodCalculator.periodsOverlapping(monthly, d("2026-11-01"), d("2026-12-31")).single(),
        )
    }

    @Test
    fun `invalid rules are rejected`() {
        assertThrows<IllegalArgumentException> { PeriodRule.Monthly(d("2026-01-01"), 0) }
        assertThrows<IllegalArgumentException> { PeriodRule.Monthly(d("2026-01-01"), 32) }
        assertThrows<IllegalArgumentException> { PeriodRule.SemiMonthly(d("2026-01-01"), 28, 31) }
        assertThrows<IllegalArgumentException> { PeriodRule.SemiMonthly(d("2026-01-01"), 15, 15) }
        assertThrows<IllegalArgumentException> { PeriodRule.EveryNDays(d("2026-01-01"), 0) }
        assertThrows<IllegalArgumentException> { PeriodRule.CustomOnce(d("2026-01-02"), d("2026-01-01")) }
        assertThrows<IllegalArgumentException> {
            PeriodCalculator.periodsOverlapping(PeriodRule.Weekly(d("2026-01-01")), d("2026-02-01"), d("2026-01-01"))
        }
    }

    private fun assertContiguous(
        rule: PeriodRule,
        years: Long,
    ) {
        val first = PeriodCalculator.firstPeriod(rule)
        val end = first.start.plusYears(years)
        var current = first
        var day = first.start
        while (day.isBefore(end)) {
            val p = checkNotNull(PeriodCalculator.periodContaining(rule, day)) { "no period for $day in $rule" }
            assertTrue(day in p, "$day not in $p")
            if (p != current) {
                assertEquals(current.endInclusive.plusDays(1), p.start, "gap/overlap after $current in $rule")
                assertEquals(p, PeriodCalculator.next(rule, current))
                current = p
            }
            day = day.plusDays(1)
        }
    }

    @Test
    fun `EX-12 property - monthly periods for every anchor day are contiguous over 10 years`() {
        for (anchorDay in 1..31) assertContiguous(PeriodRule.Monthly(d("2026-01-01"), anchorDay), 10)
    }

    @Test
    fun `property - semi-monthly periods are contiguous for all valid day pairs over 4 years`() {
        for (d1 in 1..PeriodRule.MAX_SEMI_MONTHLY_DAY1) {
            for (d2 in (d1 + 1)..31) assertContiguous(PeriodRule.SemiMonthly(d("2027-01-01"), d1, d2), 4)
        }
    }

    @Test
    fun `property - fixed-length periods contain the date and have the right length`() =
        runBlocking {
            val dates = Arb.localDate(LocalDate.of(2000, 1, 1), LocalDate.of(2060, 12, 31))
            checkAll(3_000, dates, dates, Arb.int(1..PeriodRule.MAX_EVERY_N_DAYS)) { anchor, date, n ->
                val period = PeriodCalculator.periodContaining(PeriodRule.EveryNDays(anchor, n), date)
                if (date.isBefore(anchor)) {
                    assertNull(period)
                } else {
                    checkNotNull(period)
                    assertTrue(date in period)
                    assertEquals(n.toLong(), period.lengthInDays)
                }
            }
        }
}
