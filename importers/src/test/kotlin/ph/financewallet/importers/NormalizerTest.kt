package ph.financewallet.importers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.importers.XlsxBuilder.Cell
import java.time.LocalDate

/** Mapping suggestion, date detection, exact amounts, row validation, fingerprints, balance checks (§4–6, AC-IMP-2/5). */
class NormalizerTest {
    private val php = CurrencyCode.PHP
    private val today = LocalDate.of(2026, 11, 6)

    private fun p(minor: Long) = Money(minor, php)

    private fun csv(text: String) = CsvReader.read(text.toByteArray())

    private fun amount(
        text: String,
        comma: Boolean = false,
    ) = AmountText.parse(text, php, comma)

    // ---- amounts -----------------------------------------------------------------------------------------------

    @Test
    fun `amount text is parsed exactly in every common notation`() {
        assertEquals(-123_450L, amount("-1,234.50").minor)
        assertEquals(-123_450L, amount("(1,234.50)").minor)
        assertEquals(-123_450L, amount("1,234.50-").minor)
        assertEquals(123_450L, amount("₱ 1,234.50").minor)
        assertEquals(123_450L, amount("PHP1234.5").minor)
        assertEquals(123_450L, amount("+1234.50").minor)
        assertEquals(-123_450L, amount("−1234.50").minor) // U+2212
        assertEquals(123_450L, amount("1.234,50", comma = true).minor)
        assertEquals(50L, amount(",5", comma = true).minor)
        assertEquals(50L, amount(".50").minor)
        assertEquals(Pair(null, null), amount("  ").let { it.minor to it.error })
        assertEquals(RowError.AMOUNT_PRECISION, amount("1.005").error)
        assertEquals(100L, amount("1.000").minor) // trailing zeros are not extra precision
        assertEquals(RowError.AMOUNT_UNPARSEABLE, amount("1,23").error) // bad grouping is never guessed
        assertEquals(RowError.AMOUNT_UNPARSEABLE, amount("12a").error)
        assertEquals(RowError.AMOUNT_UNPARSEABLE, amount("99999999999999999999").error)
    }

    // ---- dates -------------------------------------------------------------------------------------------------

    @Test
    fun `dates are detected and day versus month is never guessed`() {
        assertEquals(DatePatterns.Detection(listOf("yyyy-MM-dd"), false), DatePatterns.detect(listOf("2026-11-05", "2026-11-06")))
        val ambiguous = DatePatterns.detect(listOf("03/04/2026", "05/06/2026"))
        assertTrue(ambiguous.ambiguous)
        assertNull(ambiguous.chosen)
        assertTrue("MM/dd/yyyy" in ambiguous.candidates && "dd/MM/yyyy" in ambiguous.candidates)
        // one value with a day > 12 settles it
        assertEquals("dd/MM/yyyy", DatePatterns.detect(listOf("03/04/2026", "25/06/2026")).chosen)
        assertEquals("MMM d, yyyy", DatePatterns.detect(listOf("NOV 5, 2026", "Dec 25, 2026")).chosen)
        assertEquals(LocalDate.of(2026, 11, 5), DatePatterns.parse("2026-11-05 14:33:10", "yyyy-MM-dd"))
        assertEquals(LocalDate.of(2026, 11, 5), DatePatterns.parse("11/05/2026 2:33 PM", "MM/dd/yyyy"))
        assertNull(DatePatterns.parse("2026-02-30", "yyyy-MM-dd"))
        assertNull(DatePatterns.parse("", "yyyy-MM-dd"))
        assertEquals(DatePatterns.Detection(emptyList(), false), DatePatterns.detect(listOf(" ", "")))
    }

    // ---- mapping suggestion ------------------------------------------------------------------------------------

    @Test
    fun `debit and credit columns are recognized below a title block`() {
        val t =
            csv(
                "ACME Bank Statement\n" +
                    "Account: ****1234\n" +
                    "Transaction Date,Particulars,Withdrawals,Deposits,Running Balance,Ref No\n" +
                    "2026-11-01,Salary,,30000.00,30000.00,A1\n",
            )
        val s = MappingSuggester.suggest(t)
        val m = checkNotNull(s.mapping) { s.missing.toString() }
        assertEquals(2, m.headerRow)
        assertEquals(AmountColumns.DebitCredit(2, 3), m.amount)
        assertEquals(4, m.balance)
        assertEquals(5, m.reference)
        assertEquals("yyyy-MM-dd", m.datePattern)
    }

    @Test
    fun `signed amount, type column and missing fields`() {
        val signed = MappingSuggester.suggest(csv("Date,Description,Amount\n11/25/2026,Grab,-250.00\n"))
        assertEquals(AmountColumns.Signed(2), signed.mapping?.amount)
        assertEquals("MM/dd/yyyy", signed.mapping?.datePattern)
        val typed = MappingSuggester.suggest(csv("Date,Details,Amount,DR/CR\n2026-11-01,Fee,15.00,DR\n2026-11-02,Refund,15.00,CR\n"))
        assertEquals(AmountColumns.WithType(2, 3, setOf("dr"), setOf("cr")), typed.mapping?.amount)
        val ambiguous = MappingSuggester.suggest(csv("Date,Description,Amount\n03/04/2026,x,1\n"))
        assertEquals(listOf("date_format"), ambiguous.missing)
        assertNull(ambiguous.mapping)
        assertEquals(listOf("header"), MappingSuggester.suggest(csv("a,b\n1,2\n")).missing)
        assertEquals(listOf("amount"), MappingSuggester.suggest(csv("Date,Description,Balance\n2026-11-01,x,1\n")).missing)
    }

    // ---- rows --------------------------------------------------------------------------------------------------

    private val mapping =
        ColumnMapping(
            headerRow = 0,
            date = 0,
            description = 1,
            amount = AmountColumns.DebitCredit(2, 3),
            datePattern = "yyyy-MM-dd",
            balance = 4,
            status = 5,
            pendingValues = setOf("Pending"),
        )

    @Test
    fun `invalid rows are rejected with their row number, valid rows survive (AC-IMP-2)`() {
        val t =
            csv(
                "Date,Description,Debit,Credit,Balance,Status\n" +
                    "2026-11-01,Salary,,30000.00,30000.00,Posted\n" +
                    "2026-11-02,Bad amount,12..5,,,Posted\n" +
                    "2026-13-01,Bad date,1.00,,,Posted\n" +
                    "1980-01-01,Too old,1.00,,,Posted\n" +
                    "2026-11-03,,1.00,,,Posted\n" +
                    "2026-11-04,Both,1.00,2.00,,Posted\n" +
                    "2026-11-05,Zero,0.00,,,Posted\n" +
                    "2026-11-05,Fraction,0.001,,,Posted\n" +
                    ",,,,,\n" +
                    "2026-11-06,Lunch,250.00,,29750.00,PENDING\n" +
                    "Total,,250.00,30000.00,,\n",
            )
        val n = Normalizer.normalize(t, mapping.copy(skipBottomRows = 1), php, "acct", today)
        assertEquals(
            listOf(
                3 to RowError.AMOUNT_UNPARSEABLE,
                4 to RowError.DATE_UNPARSEABLE,
                5 to RowError.DATE_OUT_OF_RANGE,
                6 to RowError.DESCRIPTION_EMPTY,
                7 to RowError.DEBIT_AND_CREDIT_BOTH_SET,
                8 to RowError.AMOUNT_ZERO,
                9 to RowError.AMOUNT_PRECISION,
            ),
            n.rejected.map { it.rowNo to it.error },
        )
        assertEquals("12..5", n.rejected.first().cell)
        assertEquals(listOf(p(3_000_000), p(-25_000)), n.valid.map { it.amount })
        assertEquals(listOf(false, true), n.valid.map { it.pending })
        assertEquals(listOf(2, 11), n.valid.map { it.rowNo })
        assertEquals(emptyList<Int>(), n.balanceBreaks)
    }

    @Test
    fun `type and signed modes, unreadable cells, future dates`() {
        val typed =
            csv(
                "Date,Details,Amount,Type\n" +
                    "2026-11-01,Fee,15.00,DR\n" +
                    "2026-11-02,Refund,15.00,CR\n" +
                    "2026-11-03,?,15.00,XX\n" +
                    "2027-12-01,Later,1.00,DR\n",
            )
        val m = ColumnMapping(0, 0, 1, AmountColumns.WithType(2, 3, setOf("dr"), setOf("cr")), "yyyy-MM-dd")
        val n = Normalizer.normalize(typed, m, php, "a", today)
        assertEquals(listOf(p(-1_500), p(1_500)), n.valid.map { it.amount })
        assertEquals(listOf(RowError.TYPE_UNKNOWN, RowError.DATE_OUT_OF_RANGE), n.rejected.map { it.error })
        val positiveDebits =
            Normalizer.normalize(
                csv("Date,Description,Amount\n2026-11-01,Fee,15.00\n"),
                ColumnMapping(0, 0, 1, AmountColumns.Signed(2, outflowNegative = false), "yyyy-MM-dd"),
                php,
                "a",
                today,
            )
        assertEquals(p(-1_500), positiveDebits.valid.single().amount)
        val bad = Table(listOf(listOf("Date", "D", "A"), listOf("2026-11-01", "x", "")), badCells = setOf(1 to 2))
        assertEquals(
            RowError.CELL_UNREADABLE,
            Normalizer
                .normalize(
                    bad,
                    ColumnMapping(0, 0, 1, AmountColumns.Signed(2), "yyyy-MM-dd"),
                    php,
                    "a",
                    today,
                ).rejected
                .single()
                .error,
        )
    }

    // ---- fingerprints (§5, A-IMP-1) -----------------------------------------------------------------------------

    private val simple = ColumnMapping(0, 0, 1, AmountColumns.Signed(2), "yyyy-MM-dd")

    @Test
    fun `identical rows in one file get distinct fingerprints, overlapping statements agree`() {
        val oct = csv("Date,Description,Amount\n2026-10-30,Load,-100\n2026-10-31,Jeep,-13\n2026-10-31,Jeep,-13\n2026-11-01,Coffee,-150\n")
        val nov = csv("Date,Description,Amount\n2026-10-31,JEEP ,-13\n2026-10-31,Jeep,-13\n2026-11-01,Coffee,-150\n2026-11-02,Rice,-500\n")
        val a = Normalizer.normalize(oct, simple, php, "acct", today).valid
        val b = Normalizer.normalize(nov, simple, php, "acct", today).valid
        assertNotEquals(a[1].fingerprint, a[2].fingerprint)
        assertEquals(a.drop(1).map { it.fingerprint }, b.take(3).map { it.fingerprint }) // same day, same order, case/space-insensitive
        val otherAccount = Normalizer.normalize(oct, simple, php, "other", today).valid
        assertNotEquals(a[0].fingerprint, otherAccount[0].fingerprint)
        val byRef = Normalizer.fingerprint(a.map { it.copy(referenceNo = "R1") }, "acct", referenceIsUnique = true)
        assertEquals(1, byRef.map { it.fingerprint }.distinct().size)
        assertEquals(64, a[0].fingerprint.length)
    }

    @Test
    fun `running balance breaks are reported in either file order`() {
        fun rows(vararg pairs: Pair<Long, Long>) =
            pairs.mapIndexed { i, (amt, bal) -> NormalizedRow(i + 2, today, null, p(amt), "x", "x", null, null, p(bal), false, "") }
        assertEquals(emptyList<Int>(), Normalizer.balanceBreaks(rows(100L to 100L, -30L to 70L, 50L to 120L)))
        assertEquals(emptyList<Int>(), Normalizer.balanceBreaks(rows(50L to 120L, -30L to 70L, 100L to 100L))) // newest first
        assertEquals(listOf(4), Normalizer.balanceBreaks(rows(100L to 100L, -30L to 70L, 50L to 999L, 1L to 1_000L)))
        assertEquals(emptyList<Int>(), Normalizer.balanceBreaks(emptyList()))
    }

    // ---- AC-IMP-5: CSV and XLSX of the same statement --------------------------------------------------------

    @Test
    fun `csv and xlsx exports of the same statement normalize identically (AC-IMP-5)`() {
        val csvFile =
            csv(
                "Date,Description,Debit,Credit,Balance\n" +
                    "11/05/2026,Salary,,\"30,000.00\",\"30,000.00\"\n" +
                    "11/06/2026,Grocery,\"1,234.50\",,\"28,765.50\"\n" +
                    "11/06/2026,Grocery,\"1,234.50\",,\"27,531.00\"\n",
            )
        val xlsx =
            XlsxBuilder()
                .sheet(
                    "Statement",
                    listOf(
                        listOf(
                            Cell.Shared("Date"),
                            Cell.Shared("Description"),
                            Cell.Shared("Debit"),
                            Cell.Shared("Credit"),
                            Cell.Shared("Balance"),
                        ),
                        listOf(Cell.Number("46331", 1), Cell.Shared("Salary"), null, Cell.Number("30000", 3), Cell.Number("30000", 3)),
                        listOf(Cell.Number("46332", 2), Cell.Shared("Grocery"), Cell.Number("1234.5", 3), null, Cell.Number("28765.5", 3)),
                        listOf(Cell.Number("46332", 2), Cell.Shared("Grocery"), Cell.Number("1234.5", 3), null, Cell.Formula("27531")),
                    ),
                ).build()
        val xlsxTable = XlsxBuilder.reader().read(xlsx)
        // 11/05 and 11/06 fit both month-first and day-first: the app asks, and the user chooses month-first.
        assertEquals(listOf("date_format"), MappingSuggester.suggest(csvFile).missing)
        val fromCsv = checkNotNull(MappingSuggester.suggest(csvFile, datePattern = "MM/dd/yyyy").mapping)
        val fromXlsx = checkNotNull(MappingSuggester.suggest(xlsxTable).mapping)
        assertEquals(fromCsv.copy(datePattern = "yyyy-MM-dd"), fromXlsx) // only the date text differs
        val a = Normalizer.normalize(csvFile, fromCsv, php, "acct", today)
        val b = Normalizer.normalize(xlsxTable, fromXlsx, php, "acct", today)
        assertEquals(3, a.valid.size)
        assertEquals(a.valid, b.valid)
        assertTrue(a.rejected.isEmpty() && b.rejected.isEmpty())
        assertFalse(a.valid.any { it.pending })
    }
}
