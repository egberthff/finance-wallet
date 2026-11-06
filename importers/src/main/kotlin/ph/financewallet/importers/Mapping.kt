package ph.financewallet.importers

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/** How the amount of a row is read (docs/13-import-pipeline.md §4.3). Debit = money out of the account. */
public sealed interface AmountColumns {
    /** One signed column. [outflowNegative] = true when money out is shown as a negative number (the usual case). */
    public data class Signed(
        val column: Int,
        val outflowNegative: Boolean = true,
    ) : AmountColumns

    /** Separate debit (out) and credit (in) columns; exactly one may be filled per row. */
    public data class DebitCredit(
        val debit: Int,
        val credit: Int,
    ) : AmountColumns

    /** An unsigned amount plus a type column whose values say the direction (e.g. "DR"/"CR"). */
    public data class WithType(
        val amount: Int,
        val type: Int,
        val debitValues: Set<String>,
        val creditValues: Set<String>,
    ) : AmountColumns
}

/** A full column mapping; saved as a user profile (import_profile.mapping_json) once confirmed. */
public data class ColumnMapping(
    /** Zero-based index of the header row in the table; data starts on the next row. */
    val headerRow: Int,
    val date: Int,
    val description: Int,
    val amount: AmountColumns,
    /** A pattern from [DatePatterns.SUPPORTED]. */
    val datePattern: String,
    val postingDate: Int? = null,
    val merchant: Int? = null,
    val reference: Int? = null,
    val balance: Int? = null,
    val status: Int? = null,
    /** Values of the [status] column that mean "pending" (normalized, case-insensitive). */
    val pendingValues: Set<String> = emptySet(),
    /** Decimal comma (1.234,56) instead of decimal point (1,234.56). */
    val decimalComma: Boolean = false,
    /** Footer rows to ignore (totals, notes). */
    val skipBottomRows: Int = 0,
    /** When the statement's reference numbers are unique per transaction, fingerprints use them (§5). */
    val referenceIsUnique: Boolean = false,
)

/** Date patterns the importer understands, and detection that never guesses between day-first and month-first. */
public object DatePatterns {
    public val SUPPORTED: List<String> =
        listOf(
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "MM/dd/yyyy",
            "dd/MM/yyyy",
            "M/d/yyyy",
            "d/M/yyyy",
            "MM-dd-yyyy",
            "dd-MM-yyyy",
            "MM/dd/yy",
            "dd/MM/yy",
            "MMM d, yyyy",
            "MMM dd, yyyy",
            "MMMM d, yyyy",
            "d MMM yyyy",
            "dd MMM yyyy",
            "dd-MMM-yyyy",
            "d-MMM-yy",
            "dd-MMM-yy",
            "MMM d yyyy",
        )

    private val TIME_SUFFIX = Regex("""[ T]\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?( ?[AaPp][Mm])?$""")
    private val formatters = HashMap<String, DateTimeFormatter>()

    private fun formatter(pattern: String): DateTimeFormatter =
        synchronized(formatters) {
            formatters.getOrPut(pattern) {
                DateTimeFormatter
                    .ofPattern(
                        pattern.replace("yyyy", "uuuu").replace("yy", "uu"),
                        Locale.ENGLISH,
                    ).withResolverStyle(ResolverStyle.STRICT)
            }
        }

    /** Parses [text] with [pattern]; a trailing time of day is ignored. Null when it does not match exactly. */
    public fun parse(
        text: String,
        pattern: String,
    ): LocalDate? {
        val clean =
            text
                .trim()
                .replace(TIME_SUFFIX, "")
                .trim()
                .let(::capitalizeMonth)
        if (clean.isEmpty()) return null
        return try {
            LocalDate.parse(clean, formatter(pattern))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** English month names in any case ("NOV", "nov") are accepted. */
    private fun capitalizeMonth(s: String): String =
        s.replace(Regex("[A-Za-z]{3,}")) { m -> m.value.lowercase().replaceFirstChar { it.uppercase() } }

    /** Result of trying every supported pattern on a column. */
    public data class Detection(
        /** Patterns that parse every non-blank value. */
        val candidates: List<String>,
        /** True when the candidates disagree on at least one value (e.g. 03/04 as March 4 or 3 April): the user must choose. */
        val ambiguous: Boolean,
    ) {
        /** The pattern to use without asking, or null when there is none or it is ambiguous. */
        public val chosen: String? get() = if (ambiguous) null else candidates.firstOrNull()
    }

    public fun detect(values: List<String>): Detection {
        val sample = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (sample.isEmpty()) return Detection(emptyList(), ambiguous = false)
        val candidates = SUPPORTED.filter { p -> sample.all { parse(it, p) != null } }
        val ambiguous =
            candidates.size > 1 &&
                sample.any { v -> candidates.map { parse(v, it) }.distinct().size > 1 }
        return Detection(candidates, ambiguous)
    }
}

/** A suggested mapping, or the fields that could not be recognized and need the user. */
public data class MappingSuggestion(
    val mapping: ColumnMapping?,
    val header: List<String>,
    val missing: List<String>,
    val dates: DatePatterns.Detection?,
)

/**
 * Suggests a mapping from header names (generic CSV/XLSX, docs/13-import-pipeline.md §3–4.3). It only suggests: the
 * user confirms or corrects every mapping in the preview before anything is imported.
 */
public object MappingSuggester {
    private const val HEADER_SEARCH_ROWS = 15
    private const val DATE_SAMPLE_ROWS = 200

    private val DATE = listOf("transaction date", "txn date", "trans date", "date")
    private val POSTING = listOf("posting date", "post date", "value date")
    private val DESCRIPTION =
        listOf("description", "transaction details", "details", "particulars", "narrative", "remarks", "memo", "transaction description")
    private val MERCHANT = listOf("merchant", "payee", "counterparty")
    private val REFERENCE = listOf("reference no", "reference number", "reference", "ref no", "ref", "transaction id", "trace no")
    private val DEBIT = listOf("debit", "debits", "withdrawal", "withdrawals", "money out", "debit amount", "paid out", "amount out")
    private val CREDIT = listOf("credit", "credits", "deposit", "deposits", "money in", "credit amount", "paid in", "amount in")
    private val AMOUNT = listOf("amount", "transaction amount", "amount php")
    private val TYPE = listOf("dr cr", "debit credit", "type", "transaction type")
    private val BALANCE = listOf("running balance", "balance", "available balance", "ending balance")
    private val STATUS = listOf("status")

    internal fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** [datePattern], when given, is the user's choice and is used instead of detection (needed for ambiguous dates). */
    public fun suggest(
        table: Table,
        datePattern: String? = null,
    ): MappingSuggestion {
        val headerRow = findHeader(table) ?: return MappingSuggestion(null, emptyList(), listOf("header"), null)
        val header = table.rows[headerRow]
        val c = columns(header.map(::norm))
        val amountColumns = amountColumnsOf(c, table, headerRow)
        val dates =
            c.date?.let { d ->
                DatePatterns.detect(
                    table.rows
                        .drop(headerRow + 1)
                        .take(DATE_SAMPLE_ROWS)
                        .map { it.getOrElse(d) { "" } },
                )
            }
        val pattern = datePattern ?: dates?.chosen
        val missing =
            listOfNotNull(
                "date".takeIf { c.date == null },
                "description".takeIf { c.description == null },
                "amount".takeIf { amountColumns == null },
                "date_format".takeIf { c.date != null && pattern == null },
            )
        val mapping =
            if (missing.isEmpty()) {
                ColumnMapping(
                    headerRow = headerRow,
                    date = checkNotNull(c.date),
                    description = checkNotNull(c.description),
                    amount = checkNotNull(amountColumns),
                    datePattern = checkNotNull(pattern),
                    postingDate = c.posting,
                    merchant = c.merchant,
                    reference = c.reference,
                    balance = c.balance,
                    status = c.status,
                )
            } else {
                null
            }
        return MappingSuggestion(mapping, header, missing, dates)
    }

    /** Recognized column indexes; each column is used for at most one field, in the order below. */
    private class Columns(
        private val names: List<String>,
    ) {
        private val used = HashSet<Int>()

        private fun find(synonyms: List<String>): Int? =
            synonyms.firstNotNullOfOrNull { s -> names.indices.firstOrNull { it !in used && names[it] == s } }?.also { used += it }

        val date = find(DATE)
        val posting = find(POSTING)
        val description = find(DESCRIPTION)
        val debit = find(DEBIT)
        val credit = find(CREDIT)
        val amount = find(AMOUNT)
        val type = find(TYPE)
        val balance = find(BALANCE)
        val reference = find(REFERENCE)
        val merchant = find(MERCHANT)
        val status = find(STATUS)
    }

    private fun columns(names: List<String>) = Columns(names)

    private fun amountColumnsOf(
        c: Columns,
        table: Table,
        headerRow: Int,
    ): AmountColumns? {
        val debit = c.debit
        val credit = c.credit
        val amount = c.amount
        val type = c.type
        return when {
            debit != null && credit != null -> AmountColumns.DebitCredit(debit, credit)
            amount != null && type != null -> typeColumns(table, headerRow, amount, type)
            amount != null -> AmountColumns.Signed(amount)
            else -> null
        }
    }

    /** The first row (within the first 15) with at least a date-like and two other known headers. */
    private fun findHeader(table: Table): Int? {
        val all = DATE + POSTING + DESCRIPTION + MERCHANT + REFERENCE + DEBIT + CREDIT + AMOUNT + TYPE + BALANCE + STATUS
        return table.rows
            .take(HEADER_SEARCH_ROWS)
            .indexOfFirst { row ->
                val names = row.map(::norm)
                names.any { it in DATE } && names.count { it in all } >= MIN_HEADER_MATCHES
            }.takeIf { it >= 0 }
    }

    private fun typeColumns(
        table: Table,
        headerRow: Int,
        amount: Int,
        type: Int,
    ): AmountColumns? {
        val values =
            table.rows
                .drop(headerRow + 1)
                .map { norm(it.getOrElse(type) { "" }) }
                .filter { it.isNotEmpty() }
                .toSet()
        val debit = values.filter { it in DEBIT_WORDS }.toSet()
        val credit = values.filter { it in CREDIT_WORDS }.toSet()
        return if (debit.isNotEmpty() || credit.isNotEmpty()) AmountColumns.WithType(amount, type, debit, credit) else null
    }

    private const val MIN_HEADER_MATCHES = 3
    private val DEBIT_WORDS = setOf("dr", "debit", "d", "withdrawal", "out", "payment", "sent")
    private val CREDIT_WORDS = setOf("cr", "credit", "c", "deposit", "in", "received")
}
