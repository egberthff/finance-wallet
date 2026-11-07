package ph.financewallet.app.ui.common

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Dates shown to the user (UX-06, Philippine English): "7 Nov 2026", never ISO "2026-11-07".
 * ISO stays the storage and file-name format only.
 */

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("d", Locale.ENGLISH)
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
private val WEEKDAY_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.ENGLISH)

/** "7 Nov 2026". */
fun LocalDate.display(): String = format(DAY)

/** "Nov" (chart axis). */
fun LocalDate.monthShort(): String = format(MONTH)

/** "Saturday, 7 Nov 2026" (list day headers). */
fun LocalDate.displayWithWeekday(): String = format(WEEKDAY_DAY)

/**
 * A compact inclusive range: "1–30 Nov 2026", "5 Nov – 4 Dec 2026", "28 Dec 2026 – 3 Jan 2027".
 * A whole calendar month reads "Nov 2026".
 */
fun dateRange(
    start: LocalDate,
    end: LocalDate,
): String =
    when {
        start == end -> start.display()
        start.dayOfMonth == 1 && end == start.withDayOfMonth(start.lengthOfMonth()) -> start.format(MONTH_YEAR)
        start.year == end.year && start.month == end.month -> "${start.format(DAY_ONLY)}–${end.display()}"
        start.year == end.year -> "${start.format(DAY_MONTH)} – ${end.display()}"
        else -> "${start.display()} – ${end.display()}"
    }
