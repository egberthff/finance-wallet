package ph.financewallet.importers

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** One worksheet of a workbook, in workbook order. */
public data class SheetInfo(
    val index: Int,
    val name: String,
)

/**
 * Minimal streaming XLSX reader (docs/13-import-pipeline.md §4.2) on the XmlPullParser API (platform on Android,
 * kxml2 in tests). It reads only what statements need: shared and inline strings, numbers, booleans, cached formula
 * values, and date-formatted numbers (converted to ISO `yyyy-MM-dd`).
 *
 * Security (IMP-07): ZIP entry count, total decompressed size and compression ratio are enforced on the bytes actually
 * decompressed, not on header claims; any document type declaration is refused (no DTDs, no external entities).
 *
 * Numbers are kept as exact decimal text. Excel stores IEEE doubles, so a value is rounded to Excel's own 15 significant
 * digits (what Excel displays) and trailing zeros are removed: "0.30000000000000004" becomes "0.3". Whether the result
 * has too many decimals for the currency is decided later by the amount parser, which rejects rather than rounds.
 */
public class XlsxReader(
    private val newParser: () -> XmlPullParser,
    private val limits: FileLimits = FileLimits(),
) {
    public fun sheets(bytes: ByteArray): List<SheetInfo> = sheetTargets(unzip(bytes)).mapIndexed { i, (name, _) -> SheetInfo(i, name) }

    /** Reads sheet [sheetIndex], or the first sheet that has any non-blank cell when null. */
    public fun read(
        bytes: ByteArray,
        sheetIndex: Int? = null,
    ): Table {
        val entries = unzip(bytes)
        val targets = sheetTargets(entries)
        if (targets.isEmpty()) fail(FileError.XLSX_NO_SHEETS)
        val context = CellContext(sharedStrings(entries), dateStyles(entries), date1904(entries))

        fun load(i: Int): Table {
            val data = entries[targets[i].second] ?: fail(FileError.XLSX_NO_SHEETS, "missing ${targets[i].second}")
            return SheetHandler(context, limits.maxRows).also { parse(data, it::onEvent) }.table()
        }
        if (sheetIndex != null) {
            require(sheetIndex in targets.indices) { "no sheet $sheetIndex" }
            return load(sheetIndex)
        }
        return targets.indices
            .asSequence()
            .map(::load)
            .firstOrNull { t -> t.rows.any { r -> r.any { it.isNotBlank() } } } ?: load(0)
    }

    // ---- zip ------------------------------------------------------------------------------------------------------

    /** Decompresses the XML parts, counting every byte against the limits while reading. */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val budget = ZipBudget(limits, bytes.size.toLong())
        val entries = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    budget.entry()
                    val keep = entry.name.endsWith(".xml") || entry.name.endsWith(".rels")
                    val data = budget.drain(zip, keep)
                    if (data != null) entries[entry.name.removePrefix("/")] = data
                    entry = zip.nextEntry
                }
            }
        } catch (e: ZipException) {
            fail(FileError.ZIP_INVALID, e.message.orEmpty(), e)
        } catch (e: IOException) {
            fail(FileError.ZIP_INVALID, e.message.orEmpty(), e)
        }
        if (budget.entries == 0) fail(FileError.ZIP_INVALID, "no readable entries")
        if ("xl/workbook.xml" !in entries) fail(FileError.XLSX_NO_SHEETS, "no workbook")
        return entries
    }

    private class ZipBudget(
        private val limits: FileLimits,
        private val fileSize: Long,
    ) {
        var entries = 0
            private set
        private var total = 0L
        private val buffer = ByteArray(BUFFER)

        fun entry() {
            if (++entries > limits.maxZipEntries) fail(FileError.ZIP_TOO_MANY_ENTRIES)
        }

        /** Reads the current entry to its end; returns its bytes when [keep], otherwise discards them (still counted). */
        fun drain(
            zip: ZipInputStream,
            keep: Boolean,
        ): ByteArray? {
            val out = if (keep) ByteArrayOutputStream() else null
            var n = zip.read(buffer)
            while (n >= 0) {
                total += n
                if (total > limits.maxUncompressedBytes) fail(FileError.ZIP_TOO_LARGE)
                if (total > fileSize * limits.maxCompressionRatio) fail(FileError.ZIP_RATIO)
                out?.write(buffer, 0, n)
                n = zip.read(buffer)
            }
            return out?.toByteArray()
        }
    }

    // ---- xml ------------------------------------------------------------------------------------------------------

    /** Feeds START_TAG, END_TAG and text events of [data] to [onEvent]; any DOCTYPE fails the file. */
    private fun parse(
        data: ByteArray,
        onEvent: (XmlPullParser, Int) -> Unit,
    ) {
        val p = newParser()
        try {
            // DTD processing is off by default in the XmlPullParser spec; some implementations (kxml2) refuse to set
            // the feature at all, so it is only switched off if a parser reports it on. DOCTYPEs are refused below anyway.
            if (p.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            p.setInput(ByteArrayInputStream(data), null)
            var event = p.nextToken()
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.DOCDECL) fail(FileError.XML_DTD_FORBIDDEN)
                if (event in DELIVERED) onEvent(p, event)
                event = p.nextToken()
            }
        } catch (e: XmlPullParserException) {
            fail(FileError.XML_INVALID, e.message.orEmpty(), e)
        } catch (e: IOException) {
            fail(FileError.XML_INVALID, e.message.orEmpty(), e)
        }
    }

    /** (sheet name, entry path) in workbook order, resolved through workbook relationships. */
    private fun sheetTargets(entries: Map<String, ByteArray>): List<Pair<String, String>> {
        val rels = HashMap<String, String>()
        entries["xl/_rels/workbook.xml.rels"]?.let { data ->
            parse(data) { p, e ->
                if (e == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.attr("Id")
                    val target = p.attr("Target")
                    if (id != null && target != null) rels[id] = target
                }
            }
        }
        val sheets = mutableListOf<Pair<String, String>>()
        parse(checkNotNull(entries["xl/workbook.xml"])) { p, e ->
            if (e == XmlPullParser.START_TAG && p.name == "sheet") {
                val target = p.attr("r:id")?.let(rels::get)
                val path =
                    when {
                        target == null -> "xl/worksheets/sheet${sheets.size + 1}.xml"
                        target.startsWith("/") -> target.removePrefix("/")
                        else -> "xl/" + target
                    }
                sheets += (p.attr("name") ?: "Sheet${sheets.size + 1}") to path
            }
        }
        return sheets
    }

    private fun date1904(entries: Map<String, ByteArray>): Boolean {
        var flag = false
        parse(checkNotNull(entries["xl/workbook.xml"])) { p, e ->
            if (e == XmlPullParser.START_TAG && p.name == "workbookPr") flag = p.attr("date1904").let { it == "1" || it == "true" }
        }
        return flag
    }

    private fun sharedStrings(entries: Map<String, ByteArray>): List<String> {
        val data = entries["xl/sharedStrings.xml"] ?: return emptyList()
        return SharedStringsHandler().also { parse(data, it::onEvent) }.strings
    }

    private fun dateStyles(entries: Map<String, ByteArray>): Set<Int> {
        val data = entries["xl/styles.xml"] ?: return emptySet()
        return StylesHandler().also { parse(data, it::onEvent) }.dateStyles()
    }

    /** `<si>` entries; text of all `<t>` runs, skipping phonetic `<rPh>` runs. */
    private class SharedStringsHandler {
        val strings = mutableListOf<String>()
        private val current = StringBuilder()
        private var inT = false
        private var inPhonetic = false

        fun onEvent(
            p: XmlPullParser,
            e: Int,
        ) {
            val start = e == XmlPullParser.START_TAG
            when {
                e != XmlPullParser.START_TAG && e != XmlPullParser.END_TAG -> if (inT && !inPhonetic) current.append(p.text)
                p.name == "si" -> if (start) current.clear() else strings += current.toString()
                p.name == "rPh" -> inPhonetic = start
                p.name == "t" -> inT = start
            }
        }
    }

    /** Custom number formats that are dates, and the number format of each cell style (cellXfs). */
    private class StylesHandler {
        private val customDate = HashSet<Int>()
        private val styleFormats = mutableListOf<Int>()
        private var inCellXfs = false

        fun onEvent(
            p: XmlPullParser,
            e: Int,
        ) {
            if (e == XmlPullParser.END_TAG && p.name == "cellXfs") inCellXfs = false
            if (e != XmlPullParser.START_TAG) return
            when (p.name) {
                "numFmt" -> {
                    p
                        .attr("numFmtId")
                        ?.toIntOrNull()
                        ?.takeIf { isDateFormat(p.attr("formatCode").orEmpty()) }
                        ?.let(customDate::add)
                }

                "cellXfs" -> {
                    inCellXfs = true
                }

                "xf" -> {
                    if (inCellXfs) styleFormats += p.attr("numFmtId")?.toIntOrNull() ?: 0
                }
            }
        }

        fun dateStyles(): Set<Int> =
            styleFormats.indices
                .filter {
                    styleFormats[it] in BUILTIN_DATE_FORMATS ||
                        styleFormats[it] in customDate
                }.toSet()
    }

    private class CellContext(
        val shared: List<String>,
        val dateStyles: Set<Int>,
        val date1904: Boolean,
    )

    /** Collects `<row>`/`<c>` cells into a dense table; formulas without a cached value are marked as bad cells. */
    private class SheetHandler(
        private val context: CellContext,
        private val maxRows: Int,
    ) {
        private val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        private val bad = HashSet<Pair<Int, Int>>()
        private var rowIndex = -1
        private var col = 0
        private var type = ""
        private var style = 0
        private var hasFormula = false
        private var value: StringBuilder? = null
        private var inline: StringBuilder? = null
        private var target: StringBuilder? = null

        fun onEvent(
            p: XmlPullParser,
            e: Int,
        ) {
            when (e) {
                XmlPullParser.START_TAG -> start(p)
                XmlPullParser.END_TAG -> end(p.name)
                else -> target?.append(p.text)
            }
        }

        private fun start(p: XmlPullParser) {
            when (p.name) {
                "row" -> {
                    rowIndex = p.attr("r")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1)
                    if (rows.size >= maxRows) fail(FileError.TOO_MANY_ROWS, "> $maxRows")
                    col = 0
                }

                "c" -> {
                    col = p.attr("r")?.let(::columnOf) ?: col
                    type = p.attr("t").orEmpty()
                    style = p.attr("s")?.toIntOrNull() ?: 0
                    value = null
                    inline = null
                    hasFormula = false
                }

                "f" -> {
                    hasFormula = true
                }

                "v" -> {
                    target = StringBuilder().also { value = it }
                }

                "is" -> {
                    inline = StringBuilder()
                }

                "t" -> {
                    if (inline != null) target = inline
                }
            }
        }

        private fun end(name: String) {
            when (name) {
                "v", "t" -> {
                    target = null
                }

                "c" -> {
                    val text = cellText(type, value?.toString(), inline?.toString(), style in context.dateStyles, context)
                    if (text == null && hasFormula) bad += rowIndex to col
                    rows.getOrPut(rowIndex) { HashMap() }[col] = text.orEmpty()
                    col++
                }
            }
        }

        fun table(): Table {
            if (rows.isEmpty()) return Table(emptyList(), bad)
            val dense =
                (0..rows.lastKey()).map { r ->
                    rows[r]?.let { cells -> (0..(cells.keys.maxOrNull() ?: -1)).map { cells[it].orEmpty() } }
                        ?: emptyList()
                }
            return Table(dense, bad)
        }
    }

    public companion object {
        private const val BUFFER = 64 * 1024
        private const val EXCEL_DIGITS = 15
        private const val LETTERS = 26
        private const val LEAP_BUG_SERIAL = 61L

        private val DELIVERED =
            setOf(XmlPullParser.START_TAG, XmlPullParser.END_TAG, XmlPullParser.TEXT, XmlPullParser.ENTITY_REF, XmlPullParser.CDSECT)

        /** Built-in Excel number formats that are dates or date-times (ECMA-376 §18.8.30). */
        private val BUILTIN_DATE_FORMATS = (14..22).toSet() + setOf(27, 30, 36, 45, 46, 47, 50, 57)

        /** Excel's 1900 system counts the fictitious 1900-02-29; serials ≥ 61 are shifted by it. */
        private val EPOCH_1900: LocalDate = LocalDate.of(1899, 12, 30)
        private val EPOCH_1904: LocalDate = LocalDate.of(1904, 1, 1)

        private fun XmlPullParser.attr(name: String): String? =
            (0 until attributeCount)
                .firstOrNull {
                    getAttributeName(it) == name
                }?.let(::getAttributeValue)

        /** Text of one cell, or null when it has no usable value. */
        private fun cellText(
            type: String,
            v: String?,
            inline: String?,
            isDate: Boolean,
            context: CellContext,
        ): String? =
            when (type) {
                "s" -> {
                    v?.trim()?.toIntOrNull()?.let(context.shared::getOrNull)
                }

                "inlineStr" -> {
                    inline
                }

                "str" -> {
                    v
                }

                "b" -> {
                    v?.let { if (it.trim() == "1") "TRUE" else "FALSE" }
                }

                "e" -> {
                    null
                }

                else -> {
                    v?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        if (isDate) {
                            serialToIsoDate(
                                it,
                                context.date1904,
                            )
                        } else {
                            exactNumber(it)
                        }
                    }
                }
            }

        /** Zero-based column of a cell reference such as "AB12". */
        internal fun columnOf(ref: String): Int =
            ref.takeWhile { it.isLetter() }.uppercase().fold(0) { acc, c -> acc * LETTERS + (c - 'A' + 1) } - 1

        /** A number format code is a date if it has d, m or y outside quotes, brackets and escapes. */
        internal fun isDateFormat(code: String): Boolean =
            code.replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\."), "").any {
                it.lowercaseChar() in
                    "dmy"
            }

        internal fun exactNumber(text: String): String {
            val d = BigDecimal(text)
            val rounded = if (d.precision() > EXCEL_DIGITS) d.round(MathContext(EXCEL_DIGITS, RoundingMode.HALF_EVEN)) else d
            return rounded.stripTrailingZeros().toPlainString()
        }

        /** Whole-day part of an Excel serial date as ISO text (the time of day, if any, is ignored). */
        internal fun serialToIsoDate(
            text: String,
            date1904: Boolean,
        ): String {
            val days = BigDecimal(text).setScale(0, RoundingMode.FLOOR).toLong()
            return if (date1904) {
                EPOCH_1904.plusDays(days).toString()
            } else {
                EPOCH_1900
                    .plusDays(
                        if (days <
                            LEAP_BUG_SERIAL
                        ) {
                            days + 1
                        } else {
                            days
                        },
                    ).toString()
            }
        }
    }
}
