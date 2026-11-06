package ph.financewallet.importers

import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds synthetic XLSX files for tests (no real statement data in the repository). */
internal class XlsxBuilder {
    /** A cell: shared string, inline string, number, styled number (date), formula with/without cached value, boolean. */
    sealed interface Cell {
        data class Shared(
            val text: String,
        ) : Cell

        data class Inline(
            val text: String,
        ) : Cell

        data class Number(
            val v: String,
            val style: Int = 0,
        ) : Cell

        data class Formula(
            val cached: String?,
        ) : Cell

        data class Bool(
            val value: Boolean,
        ) : Cell

        data object Error : Cell
    }

    private val sheets = mutableListOf<Pair<String, List<List<Cell?>>>>()
    private val extra = mutableListOf<Pair<String, ByteArray>>()
    var date1904 = false
    var customDateFormat: String? = "dd/mm/yyyy"
    var richSharedString = false
    var doctypeInSheet = false

    fun sheet(
        name: String,
        rows: List<List<Cell?>>,
    ) = apply { sheets += name to rows }

    fun extraEntry(
        name: String,
        bytes: ByteArray,
    ) = apply { extra += name to bytes }

    // Test fixture writer: one block per XML part of the package; splitting it would only add indirection.
    @Suppress("LongMethod")
    fun build(): ByteArray {
        val shared = LinkedHashMap<String, Int>()
        sheets.forEach { (_, rows) -> rows.flatten().filterIsInstance<Cell.Shared>().forEach { shared.getOrPut(it.text) { shared.size } } }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(
                name: String,
                text: String,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types/>")
            val pr = if (date1904) "<workbookPr date1904=\"1\"/>" else "<workbookPr/>"
            put(
                "xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook xmlns:r=\"rel\">$pr<sheets>" +
                    sheets.indices.joinToString(
                        "",
                    ) { "<sheet name=\"${sheets[it].first}\" sheetId=\"${it + 1}\" r:id=\"rId${it + 1}\"/>" } +
                    "</sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\"?><Relationships>" +
                    // Reverse file names prove that sheets are resolved through relationships, not by number.
                    sheets.indices.joinToString(
                        "",
                    ) { "<Relationship Id=\"rId${it + 1}\" Target=\"worksheets/s${sheets.size - it}.xml\"/>" } +
                    "</Relationships>",
            )
            val fmt = customDateFormat?.let { "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"$it\"/></numFmts>" }.orEmpty()
            // style 0 = General, 1 = built-in date 14, 2 = custom date 164, 3 = money "#,##0.00" (not a date)
            put(
                "xl/styles.xml",
                "<?xml version=\"1.0\"?><styleSheet>$fmt<cellXfs count=\"4\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/>" +
                    "<xf numFmtId=\"164\"/><xf numFmtId=\"4\"/></cellXfs></styleSheet>",
            )
            put(
                "xl/sharedStrings.xml",
                "<?xml version=\"1.0\"?><sst>" +
                    shared.keys.joinToString("") { s ->
                        if (richSharedString) {
                            val half = s.length / 2
                            "<si><r><t>${esc(
                                s.take(half),
                            )}</t></r><r><t xml:space=\"preserve\">${esc(s.drop(half))}</t></r><rPh><t>PHONETIC</t></rPh></si>"
                        } else {
                            "<si><t>${esc(s)}</t></si>"
                        }
                    } + "</sst>",
            )
            sheets.forEachIndexed { i, (_, rows) ->
                val doc = if (doctypeInSheet) "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" else ""
                val body =
                    rows.withIndex().joinToString("") { (r, cells) ->
                        "<row r=\"${r + 1}\">" +
                            cells.withIndex().filter { it.value != null }.joinToString(
                                "",
                            ) { (c, cell) -> cellXml("${col(c)}${r + 1}", checkNotNull(cell), shared) } +
                            "</row>"
                    }
                put(
                    "xl/worksheets/s${sheets.size - i}.xml",
                    "<?xml version=\"1.0\"?>$doc<worksheet><sheetData>$body</sheetData></worksheet>",
                )
            }
            extra.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun cellXml(
        ref: String,
        cell: Cell,
        shared: Map<String, Int>,
    ): String =
        when (cell) {
            is Cell.Shared -> "<c r=\"$ref\" t=\"s\"><v>${shared.getValue(cell.text)}</v></c>"
            is Cell.Inline -> "<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(cell.text)}</t></is></c>"
            is Cell.Number -> "<c r=\"$ref\" s=\"${cell.style}\"><v>${cell.v}</v></c>"
            is Cell.Formula -> "<c r=\"$ref\"><f>SUM(A1:A2)</f>${cell.cached?.let { "<v>$it</v>" }.orEmpty()}</c>"
            is Cell.Bool -> "<c r=\"$ref\" t=\"b\"><v>${if (cell.value) 1 else 0}</v></c>"
            Cell.Error -> "<c r=\"$ref\" t=\"e\"><v>#DIV/0!</v></c>"
        }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        fun reader(limits: FileLimits = FileLimits()) = XlsxReader({ KXmlParser() }, limits)
    }
}
