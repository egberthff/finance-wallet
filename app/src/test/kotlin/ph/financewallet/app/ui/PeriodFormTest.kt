package ph.financewallet.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ph.financewallet.app.R
import ph.financewallet.app.ui.budgets.PeriodChoice
import ph.financewallet.app.ui.budgets.PeriodForm
import ph.financewallet.app.ui.budgets.previewPeriods
import ph.financewallet.app.ui.budgets.toForm
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.budget.PeriodRule
import java.time.LocalDate

/** The budget period selector (blueprint I): every type maps to the right rule and previews real dates. */
class PeriodFormTest {
    private fun d(
        y: Int,
        m: Int,
        day: Int,
    ) = LocalDate.of(y, m, day)

    private fun preview(
        form: PeriodForm,
        today: LocalDate,
        newBudget: Boolean = true,
    ): List<Period> {
        val (rule, error) = form.toRule(today, newBudget)
        assertNull("error $error", error)
        return previewPeriods(checkNotNull(rule), from = today)
    }

    @Test
    fun `monthly from the 5th across a year boundary`() {
        val p = preview(PeriodForm(PeriodChoice.MONTHLY, day1 = "5"), d(2026, 12, 20))
        assertEquals(
            listOf(d(2026, 12, 5) to d(2027, 1, 4), d(2027, 1, 5) to d(2027, 2, 4), d(2027, 2, 5) to d(2027, 3, 4)),
            p.map { it.start to it.endInclusive },
        )
    }

    @Test
    fun `monthly on the 31st clamps to short months and 29 February in a leap year`() {
        val p = preview(PeriodForm(PeriodChoice.MONTHLY, day1 = "31"), d(2028, 2, 10))
        assertEquals(listOf(d(2028, 1, 31), d(2028, 2, 29), d(2028, 3, 31)), p.map { it.start })
        assertEquals(d(2028, 2, 28), p.first().endInclusive)
    }

    @Test
    fun `twice a month on the 15th and the end of the month`() {
        val p = preview(PeriodForm(PeriodChoice.SEMI_MONTHLY, day1 = "15", day2 = "31"), d(2027, 2, 20))
        assertEquals(
            listOf(d(2027, 2, 15) to d(2027, 2, 27), d(2027, 2, 28) to d(2027, 3, 14), d(2027, 3, 15) to d(2027, 3, 30)),
            p.map {
                it.start to
                    it.endInclusive
            },
        )
    }

    @Test
    fun `weekly, every two weeks and every N days start on the chosen date`() {
        val start = d(2026, 12, 28)
        assertEquals(
            listOf(d(2026, 12, 28) to d(2027, 1, 3), d(2027, 1, 4) to d(2027, 1, 10), d(2027, 1, 11) to d(2027, 1, 17)),
            preview(PeriodForm(PeriodChoice.WEEKLY, start = start), start).map { it.start to it.endInclusive },
        )
        assertEquals(
            listOf(d(2026, 12, 28), d(2027, 1, 11), d(2027, 1, 25)),
            preview(PeriodForm(PeriodChoice.BIWEEKLY, start = start), start).map { it.start },
        )
        val leap = d(2028, 2, 27)
        assertEquals(
            listOf(d(2028, 2, 27) to d(2028, 3, 7), d(2028, 3, 8) to d(2028, 3, 17), d(2028, 3, 18) to d(2028, 3, 27)),
            preview(PeriodForm(PeriodChoice.EVERY_N_DAYS, everyNDays = "10", start = leap), leap).map { it.start to it.endInclusive },
        )
    }

    @Test
    fun `one custom period has no next period`() {
        val p = preview(PeriodForm(PeriodChoice.CUSTOM_ONCE, start = d(2026, 11, 5), end = d(2026, 12, 4)), d(2026, 11, 20))
        assertEquals(listOf(d(2026, 11, 5) to d(2026, 12, 4)), p.map { it.start to it.endInclusive })
    }

    @Test
    fun `invalid input gives a message, never a rule`() {
        val today = d(2026, 11, 20)
        assertEquals(R.string.err_start_day, PeriodForm(PeriodChoice.MONTHLY, day1 = "32").toRule(today, true).second)
        assertEquals(
            R.string.err_semi_monthly_days,
            PeriodForm(PeriodChoice.SEMI_MONTHLY, day1 = "15", day2 = "10").toRule(today, true).second,
        )
        assertEquals(R.string.err_every_n_days, PeriodForm(PeriodChoice.EVERY_N_DAYS, everyNDays = "0").toRule(today, true).second)
        assertEquals(
            R.string.err_custom_end,
            PeriodForm(PeriodChoice.CUSTOM_ONCE, start = today, end = today.minusDays(1)).toRule(today, true).second,
        )
        assertEquals(R.string.err_rule_start_future, PeriodForm(PeriodChoice.WEEKLY, start = today.plusDays(1)).toRule(today, true).second)
        // A rule change may not start before the first allowed day.
        assertEquals(
            R.string.err_rule_start_too_early,
            PeriodForm(PeriodChoice.WEEKLY, start = today.minusDays(1)).toRule(today, false).second,
        )
    }

    @Test
    fun `an existing rule turns back into the same form`() {
        val rules =
            listOf(
                PeriodRule.Monthly(d(2026, 11, 5), 5),
                PeriodRule.SemiMonthly(d(2026, 11, 15), 15, 31),
                PeriodRule.Weekly(d(2026, 11, 2)),
                PeriodRule.Biweekly(d(2026, 11, 2)),
                PeriodRule.EveryNDays(d(2026, 11, 2), 10),
                PeriodRule.CustomOnce(d(2026, 11, 5), d(2026, 12, 4)),
            )
        rules.forEach { r ->
            val back = r.toForm().toRule(r.anchorDate, forNewBudget = false).first
            assertEquals(r::class, checkNotNull(back)::class)
        }
    }
}
