package ph.financewallet.importers

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A parsed sheet or CSV: raw cell text, row by row. Rows may have different lengths. */
public data class Table(
    val rows: List<List<String>>,
    /** Cells that cannot be trusted (e.g. an XLSX formula without a cached value), as (row index, column index). */
    val badCells: Set<Pair<Int, Int>> = emptySet(),
    /** How text was decoded (CSV only), shown in the preview (docs/13-import-pipeline.md §4.1). */
    val encoding: String? = null,
)

/** Decoded text plus the encoding that was used. */
public data class DecodedText(
    val text: String,
    val encoding: String,
)

/** Text decoding for CSV: BOM first, then strict UTF-8, otherwise Windows-1252 (docs/13-import-pipeline.md §4.1). */
public object TextDecoding {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val FF = 0xFF.toByte()
    private const val FE = 0xFE.toByte()

    public fun decode(bytes: ByteArray): DecodedText =
        when {
            bytes.startsWith(UTF8_BOM) -> {
                DecodedText(String(bytes, UTF8_BOM.size, bytes.size - UTF8_BOM.size, Charsets.UTF_8), "UTF-8")
            }

            bytes.startsWith(byteArrayOf(FF, FE)) -> {
                DecodedText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
            }

            bytes.startsWith(byteArrayOf(FE, FF)) -> {
                DecodedText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
            }

            else -> {
                strictUtf8(bytes)?.let { DecodedText(it, "UTF-8") }
                    ?: DecodedText(String(bytes, Charset.forName("windows-1252")), "windows-1252")
            }
        }

    private fun strictUtf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

/**
 * RFC 4180 CSV reader (in-house instead of Apache Commons CSV; see docs/milestones/phase-6-statement-import.md §3).
 * Supports quoted fields with doubled quotes, delimiters and line breaks inside quotes, CRLF/LF/CR line ends, and a
 * sniffed delimiter. A quote that is never closed fails the whole file: reading on would shift every later row.
 */
public object CsvReader {
    private val CANDIDATES = listOf(',', ';', '\t', '|')
    private const val SNIFF_RECORDS = 20

    public fun read(
        bytes: ByteArray,
        limits: FileLimits = FileLimits(),
        delimiter: Char? = null,
    ): Table {
        val decoded = TextDecoding.decode(bytes)
        val d = delimiter ?: sniff(decoded.text)
        return Table(parse(decoded.text, d, limits.maxRows), encoding = decoded.encoding)
    }

    /**
     * The candidate whose first records split most consistently into more than one field (most records sharing one field
     * count, ties broken by more fields, then by candidate order). Falls back to a comma.
     */
    public fun sniff(text: String): Char {
        val scored =
            CANDIDATES.mapNotNull { c ->
                val counts =
                    try {
                        parse(text, c, SNIFF_RECORDS, stopAtLimit = true).filter { r -> r.any { it.isNotBlank() } }.map { it.size }
                    } catch (_: ImportFileException) {
                        return@mapNotNull null
                    }
                val (fields, agreeing) =
                    counts
                        .groupingBy { it }
                        .eachCount()
                        .maxWithOrNull(compareBy({ it.value }, { it.key }))
                        ?.toPair()
                        ?: return@mapNotNull null
                if (fields < 2) null else Triple(c, agreeing, fields)
            }
        return scored.maxWithOrNull(compareBy({ it.second }, { it.third }))?.first ?: ','
    }

    /** Parses [text] into records. A blank line becomes a record with one empty field; mapping skips it later. */
    public fun parse(
        text: String,
        delimiter: Char,
        maxRows: Int,
        stopAtLimit: Boolean = false,
    ): List<List<String>> = Scanner(text, delimiter, maxRows, stopAtLimit).run()

    /** RFC 4180 state machine: one character at a time, quote state, current field and record. */
    private class Scanner(
        private val text: String,
        private val delimiter: Char,
        private val maxRows: Int,
        private val stopAtLimit: Boolean,
    ) {
        private val rows = mutableListOf<List<String>>()
        private val row = mutableListOf<String>()
        private val cell = StringBuilder()
        private var quoted = false
        private var fieldStarted = false
        private var i = 0

        fun run(): List<List<String>> {
            while (i < text.length) {
                if (rows.size >= maxRows) {
                    if (stopAtLimit) return rows
                    throw ImportFileException(FileError.TOO_MANY_ROWS, "> $maxRows")
                }
                if (quoted) quotedChar(text[i]) else plainChar(text[i])
                i++
            }
            if (quoted) throw ImportFileException(FileError.CSV_UNTERMINATED_QUOTE, "record ${rows.size + 1}")
            if (cell.isNotEmpty() || row.isNotEmpty() || fieldStarted) endRecord()
            return rows
        }

        private fun quotedChar(c: Char) {
            when {
                c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }

                c == '"' -> {
                    quoted = false
                }

                else -> {
                    cell.append(c)
                }
            }
        }

        private fun plainChar(c: Char) {
            when {
                c == '"' && !fieldStarted && cell.isEmpty() -> {
                    quoted = true
                    fieldStarted = true
                }

                c == delimiter -> {
                    endField()
                }

                c == '\r' || c == '\n' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    endRecord()
                }

                else -> {
                    cell.append(c)
                    fieldStarted = true
                }
            }
        }

        private fun endField() {
            row += cell.toString()
            cell.clear()
            fieldStarted = false
        }

        private fun endRecord() {
            endField()
            rows += row.toList()
            row.clear()
        }
    }
}
