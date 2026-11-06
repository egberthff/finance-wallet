package ph.financewallet.importers

import java.security.MessageDigest

/** Statement file formats the pipeline recognizes (docs/13-import-pipeline.md §2–4). */
public enum class FileFormat { CSV, XLSX, PDF }

/**
 * Why a whole file was refused before or while parsing. Codes are stable and mapped to user messages by the UI.
 * Row-level problems are [RowError]s instead.
 */
public enum class FileError {
    FILE_EMPTY,
    FILE_TOO_LARGE,
    TOO_MANY_ROWS,
    ZIP_TOO_MANY_ENTRIES,
    ZIP_TOO_LARGE,
    ZIP_RATIO,
    ZIP_INVALID,
    XML_DTD_FORBIDDEN,
    XML_INVALID,
    XLSX_NO_SHEETS,

    /** Old binary Excel (.xls) or a password-protected .xlsx (both are OLE containers). */
    XLS_UNSUPPORTED,

    /** No verified PDF layout exists yet (IMP-03): the user is asked for CSV or XLSX instead. */
    PDF_LAYOUT_UNSUPPORTED,
    CSV_UNTERMINATED_QUOTE,
    NO_HEADER_ROW,
}

/** A file-level failure. [detail] is for logs and diagnostics only and never contains file contents. */
public class ImportFileException(
    public val error: FileError,
    public val detail: String = "",
    cause: Throwable? = null,
) : Exception(error.name + if (detail.isEmpty()) "" else ": $detail", cause)

/** Fails the file with [error]; a single throw site keeps callers simple. */
internal fun fail(
    error: FileError,
    detail: String = "",
    cause: Throwable? = null,
): Nothing = throw ImportFileException(error, detail, cause)

/** Limits from docs/13-import-pipeline.md §2 (IMP-07). */
public data class FileLimits(
    val maxFileBytes: Long = DEFAULT_MAX_FILE_MB * MB,
    val maxRows: Int = DEFAULT_MAX_ROWS,
    val maxZipEntries: Int = DEFAULT_MAX_ZIP_ENTRIES,
    val maxUncompressedBytes: Long = DEFAULT_MAX_UNCOMPRESSED_MB * MB,
    val maxCompressionRatio: Long = DEFAULT_MAX_RATIO,
)

private const val MB = 1024L * 1024L
private const val DEFAULT_MAX_FILE_MB = 20L
private const val DEFAULT_MAX_ROWS = 50_000
private const val DEFAULT_MAX_ZIP_ENTRIES = 200
private const val DEFAULT_MAX_UNCOMPRESSED_MB = 100L
private const val DEFAULT_MAX_RATIO = 100L

/** Size check, format sniffing by content (never by file name) and the file hash used to detect re-imports. */
public object FileGuard {
    private val ZIP = hex("504B0304")
    private val PDF = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val OLE = hex("D0CF11E0A1B11AE1")

    /** Returns the format of [bytes], or throws for empty, oversized, or unsupported files. */
    public fun check(
        bytes: ByteArray,
        limits: FileLimits = FileLimits(),
    ): FileFormat {
        val error =
            when {
                bytes.isEmpty() -> FileError.FILE_EMPTY
                bytes.size > limits.maxFileBytes -> FileError.FILE_TOO_LARGE
                bytes.startsWith(OLE) -> FileError.XLS_UNSUPPORTED
                else -> null
            }
        if (error != null) throw ImportFileException(error, "${bytes.size} bytes")
        return when {
            bytes.startsWith(ZIP) -> FileFormat.XLSX
            bytes.startsWith(PDF) -> FileFormat.PDF
            else -> FileFormat.CSV
        }
    }

    /** SHA-256 of the file, lower-case hex (import_batch.file_sha256). */
    public fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private const val HEX = 16

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()
}

/**
 * PDF statements (IMP-03): only layouts verified against real redacted samples may be parsed. None is verified yet
 * (docs/integrations/README.md), so every PDF is refused with a message that asks for a CSV or Excel export.
 */
public object PdfSupport {
    /** Layout ids with a verified parser. Empty until the first profile passes the verification checklist. */
    public val verifiedLayouts: Set<String> = emptySet()

    public fun reject(): Nothing = throw ImportFileException(FileError.PDF_LAYOUT_UNSUPPORTED)
}
