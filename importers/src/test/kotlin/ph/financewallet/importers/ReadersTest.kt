package ph.financewallet.importers

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ph.financewallet.importers.XlsxBuilder.Cell
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** File guard, CSV and XLSX readers, including hostile files (IMP-07, AC-6 of the Phase 6 plan). */
class ReadersTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun expectError(
        error: FileError,
        block: () -> Unit,
    ) = assertEquals(error, assertThrows<ImportFileException> { block() }.error)

    // ---- guard ------------------------------------------------------------------------------------------------

    @Test
    fun `formats are detected from content, never from the file name`() {
        assertEquals(FileFormat.CSV, FileGuard.check(bytes("Date,Amount\n")))
        assertEquals(FileFormat.XLSX, FileGuard.check(XlsxBuilder().sheet("S", listOf(listOf(Cell.Inline("x")))).build()))
        assertEquals(FileFormat.PDF, FileGuard.check(bytes("%PDF-1.7\n...")))
        expectError(FileError.XLS_UNSUPPORTED) {
            FileGuard.check(
                byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0),
            )
        }
        expectError(FileError.FILE_EMPTY) { FileGuard.check(ByteArray(0)) }
        expectError(FileError.FILE_TOO_LARGE) { FileGuard.check(ByteArray(11), FileLimits(maxFileBytes = 10)) }
        expectError(FileError.PDF_LAYOUT_UNSUPPORTED) { PdfSupport.reject() }
        assertTrue(PdfSupport.verifiedLayouts.isEmpty())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileGuard.sha256(bytes("abc")))
    }

    // ---- CSV --------------------------------------------------------------------------------------------------

    @Test
    fun `csv quoting, embedded delimiters and line breaks, CRLF`() {
        val t =
            CsvReader.read(
                bytes(
                    "Date,Description,Amount\r\n2026-11-05,\"Jollibee, SM \"\"North\"\"\",\"-1,234.50\"\r\n2026-11-06,\"two\nlines\",5\n",
                ),
            )
        assertEquals(listOf("2026-11-05", "Jollibee, SM \"North\"", "-1,234.50"), t.rows[1])
        assertEquals("two\nlines", t.rows[2][1])
        assertEquals(3, t.rows.size)
        assertEquals("UTF-8", t.encoding)
    }

    @Test
    fun `delimiters are sniffed by consistency`() {
        assertEquals(';', CsvReader.sniff("a;b;c\n1;2,5;3\n4;5,1;6\n"))
        assertEquals('\t', CsvReader.sniff("a\tb\n1\t2\n"))
        assertEquals('|', CsvReader.sniff("a|b|c\n1|2|3\n"))
        assertEquals(',', CsvReader.sniff("just one column\nmore\n"))
        assertEquals(listOf("1", "2,5", "3"), CsvReader.read(bytes("a;b;c\n1;2,5;3\n")).rows[1])
    }

    @Test
    fun `encodings - BOMs, strict UTF-8, Windows-1252 fallback`() {
        assertEquals("UTF-8", CsvReader.read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("a,b\n")).encoding)
        assertEquals(listOf("a", "b"), CsvReader.read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("a,b\n")).rows[0])
        val cp1252 = CsvReader.read(byteArrayOf('P'.code.toByte(), 0xF1.toByte(), 'a'.code.toByte(), ','.code.toByte(), '1'.code.toByte()))
        assertEquals("windows-1252", cp1252.encoding)
        assertEquals("Pña", cp1252.rows[0][0])
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "x,y".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("x", "y"), CsvReader.read(utf16).rows[0])
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "x,y".toByteArray(Charsets.UTF_16BE)
        assertEquals("UTF-16BE", CsvReader.read(be).encoding)
    }

    @Test
    fun `an unterminated quote or too many rows fails the whole file`() {
        expectError(FileError.CSV_UNTERMINATED_QUOTE) { CsvReader.read(bytes("a,b\n\"open,1\n2,3\n")) }
        expectError(FileError.TOO_MANY_ROWS) { CsvReader.read(bytes("a\n1\n2\n3\n"), FileLimits(maxRows = 2)) }
        assertEquals(listOf(listOf("a", "")), CsvReader.parse("a,", ',', 10))
        assertEquals(listOf(listOf("")), CsvReader.parse("\"\"", ',', 10))
    }

    /** Whatever an RFC 4180 writer produces, the reader returns exactly the same cells (quotes, delimiters, CR/LF inside). */
    @Test
    fun `round-trips any table written as RFC 4180`() =
        runBlocking<Unit> {
            val chars = listOf('a', 'Z', '1', ' ', ',', ';', '"', '\n', '\r', 'ñ', '₱', '.', '-')
            val cell = Arb.list(Arb.element(chars), 0..10).map { it.joinToString("") }
            var checked = 0
            checkAll(500, Arb.int(1..6), Arb.list(Arb.list(cell, 6..6), 1..20)) { cols, raw ->
                val table = raw.map { it.take(cols) }
                val text = table.joinToString("\r\n") { r -> r.joinToString(",") { quote(it) } } + "\r\n"
                assertEquals(table, CsvReader.parse(text, ',', 1000))
                checked++
            }
            assertEquals(500, checked)
        }

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    // ---- XLSX -------------------------------------------------------------------------------------------------

    private val statement =
        listOf(
            listOf(Cell.Shared("Date"), Cell.Shared("Description"), Cell.Shared("Amount"), Cell.Shared("Balance")),
            listOf(Cell.Number("46331", style = 1), Cell.Shared("Salary"), Cell.Number("30000", style = 3), Cell.Number("30000")),
            listOf(Cell.Number("46332", style = 2), Cell.Inline("Grocery & more"), Cell.Number("-1234.5"), Cell.Formula("28765.5")),
            listOf(Cell.Number("46333.75", style = 1), null, Cell.Number("0.30000000000000004"), Cell.Formula(null)),
        )

    @Test
    fun `xlsx cells - shared and inline strings, exact numbers, dates, cached formulas, gaps`() {
        val t = XlsxBuilder.reader().read(XlsxBuilder().sheet("Statement", statement).build())
        assertEquals(listOf("Date", "Description", "Amount", "Balance"), t.rows[0])
        assertEquals(listOf("2026-11-05", "Salary", "30000", "30000"), t.rows[1])
        assertEquals(listOf("2026-11-06", "Grocery & more", "-1234.5", "28765.5"), t.rows[2])
        assertEquals(listOf("2026-11-07", "", "0.3", ""), t.rows[3]) // time of day dropped; double artifact removed
        assertEquals(setOf(3 to 3), t.badCells) // formula without a cached value
    }

    @Test
    fun `rich shared strings ignore phonetic runs, booleans and errors`() {
        val b =
            XlsxBuilder()
                .apply {
                    richSharedString = true
                }.sheet("S", listOf(listOf(Cell.Shared("Meralco bill"), Cell.Bool(true), Cell.Bool(false), Cell.Error)))
        assertEquals(listOf("Meralco bill", "TRUE", "FALSE", ""), XlsxBuilder.reader().read(b.build()).rows[0])
    }

    @Test
    fun `1904 date system and sheet selection`() {
        val b =
            XlsxBuilder()
                .apply { date1904 = true }
                .sheet("Empty", listOf(listOf(null, Cell.Inline("  "))))
                .sheet("Data", listOf(listOf(Cell.Number("0", style = 1), Cell.Number("1", style = 1))))
        val bytes = b.build()
        val reader = XlsxBuilder.reader()
        assertEquals(listOf(SheetInfo(0, "Empty"), SheetInfo(1, "Data")), reader.sheets(bytes))
        assertEquals(listOf("1904-01-01", "1904-01-02"), reader.read(bytes).rows[0]) // first non-empty sheet
        assertEquals(listOf("", "  "), reader.read(bytes, 0).rows[0])
    }

    @Test
    fun `excel 1900 dates including the fictitious leap day`() {
        assertEquals("1900-01-01", XlsxReader.serialToIsoDate("1", false))
        assertEquals("1900-02-28", XlsxReader.serialToIsoDate("59", false))
        assertEquals("1900-03-01", XlsxReader.serialToIsoDate("61", false))
        assertEquals("2026-11-05", XlsxReader.serialToIsoDate("46331", false))
        assertTrue(XlsxReader.isDateFormat("dd/mm/yyyy"))
        assertTrue(XlsxReader.isDateFormat("[\$-409]mmm d, yyyy"))
        assertTrue(!XlsxReader.isDateFormat("#,##0.00\" days\""))
        assertTrue(!XlsxReader.isDateFormat("[Red]0.00"))
        assertEquals(27, XlsxReader.columnOf("AB12"))
        assertEquals("123456789012346", XlsxReader.exactNumber("123456789012345.67"))
    }

    @Test
    fun `hostile xlsx - DTD, zip bomb, too many entries, not a zip, no workbook`() {
        expectError(FileError.XML_DTD_FORBIDDEN) {
            XlsxBuilder.reader().read(XlsxBuilder().apply { doctypeInSheet = true }.sheet("S", listOf(listOf(Cell.Inline("x")))).build())
        }
        val bomb = XlsxBuilder().sheet("S", listOf(listOf(Cell.Inline("x")))).extraEntry("xl/media/zeros.bin", ByteArray(5_000_000)).build()
        expectError(FileError.ZIP_RATIO) { XlsxBuilder.reader().read(bomb) }
        expectError(FileError.ZIP_TOO_LARGE) {
            XlsxBuilder.reader(FileLimits(maxUncompressedBytes = 1_000, maxCompressionRatio = 1_000_000)).read(bomb)
        }
        val many = XlsxBuilder().sheet("S", listOf(listOf(Cell.Inline("x"))))
        repeat(10) { many.extraEntry("x$it.bin", byteArrayOf(1)) }
        expectError(FileError.ZIP_TOO_MANY_ENTRIES) { XlsxBuilder.reader(FileLimits(maxZipEntries = 8)).read(many.build()) }
        expectError(FileError.XLSX_NO_SHEETS) { XlsxBuilder.reader().read(zipOf("other.xml" to "<a/>")) }
        expectError(FileError.ZIP_INVALID) { XlsxBuilder.reader().read(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)) }
        expectError(FileError.XML_INVALID) { XlsxBuilder.reader().read(zipOf("xl/workbook.xml" to "<workbook><sheets><sheet")) }
        expectError(FileError.XLSX_NO_SHEETS) { XlsxBuilder.reader().read(zipOf("xl/workbook.xml" to "<workbook><sheets/></workbook>")) }
        expectError(
            FileError.TOO_MANY_ROWS,
        ) { XlsxBuilder.reader(FileLimits(maxRows = 2)).read(XlsxBuilder().sheet("S", statement).build()) }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (n, t) ->
                z.putNextEntry(ZipEntry(n))
                z.write(t.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
