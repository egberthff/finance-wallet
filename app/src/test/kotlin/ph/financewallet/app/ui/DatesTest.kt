package ph.financewallet.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ph.financewallet.app.ui.common.dateRange
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.common.displayWithWeekday
import java.time.LocalDate

/** Dates shown to users are Philippine English ("7 Nov 2026"), never ISO; ranges are compact but unambiguous. */
class DatesTest {
    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `single dates use day month year`() {
        assertEquals("7 Nov 2026", d("2026-11-07").display())
        assertEquals("Saturday, 7 Nov 2026", d("2026-11-07").displayWithWeekday())
    }

    @Test
    fun `a whole calendar month reads as the month`() {
        assertEquals("Nov 2026", dateRange(d("2026-11-01"), d("2026-11-30")))
        // Leap-year February is still a whole month.
        assertEquals("Feb 2028", dateRange(d("2028-02-01"), d("2028-02-29")))
    }

    @Test
    fun `ranges share the parts they have in common`() {
        assertEquals("1–7 Nov 2026", dateRange(d("2026-11-01"), d("2026-11-07")))
        assertEquals("5 Nov – 4 Dec 2026", dateRange(d("2026-11-05"), d("2026-12-04")))
        assertEquals("28 Dec 2026 – 3 Jan 2027", dateRange(d("2026-12-28"), d("2027-01-03")))
        assertEquals("7 Nov 2026", dateRange(d("2026-11-07"), d("2026-11-07")))
    }

    @Test
    fun `a month-long range that does not start on the 1st is not shown as a month`() {
        assertEquals("2–30 Nov 2026", dateRange(d("2026-11-02"), d("2026-11-30")))
    }
}
