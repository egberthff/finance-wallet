package ph.financewallet.importers

import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.text.TextNormalizer
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

/** Why one row was excluded (docs/13-import-pipeline.md §6). The row is shown with its row number, never partly imported. */
public enum class RowError {
    DATE_UNPARSEABLE,
    DATE_OUT_OF_RANGE,
    AMOUNT_UNPARSEABLE,
    AMOUNT_PRECISION,
    AMOUNT_ZERO,
    DEBIT_AND_CREDIT_BOTH_SET,
    TYPE_UNKNOWN,
    DESCRIPTION_EMPTY,
    CELL_UNREADABLE,
}

/** One parsed statement row (docs/13-import-pipeline.md §5). [amount] is + money in, − money out of the account. */
public data class NormalizedRow(
    /** 1-based row number in the file, for messages. */
    val rowNo: Int,
    val txnDate: LocalDate,
    val postingDate: LocalDate?,
    val amount: Money,
    val description: String,
    val descriptionNorm: String,
    val merchant: String?,
    val referenceNo: String?,
    val runningBalance: Money?,
    val pending: Boolean,
    val fingerprint: String,
)

public sealed interface RowOutcome {
    public val rowNo: Int

    public data class Valid(
        val row: NormalizedRow,
    ) : RowOutcome {
        override val rowNo: Int get() = row.rowNo
    }

    public data class Rejected(
        override val rowNo: Int,
        val error: RowError,
        /** The offending cell text, shown to the user so they can see what was misread. */
        val cell: String,
    ) : RowOutcome
}

public data class NormalizedFile(
    val outcomes: List<RowOutcome>,
    /** Row numbers where the running balance does not follow from the previous row (rows may be missing or misread). */
    val balanceBreaks: List<Int>,
) {
    val valid: List<NormalizedRow> get() = outcomes.filterIsInstance<RowOutcome.Valid>().map { it.row }
    val rejected: List<RowOutcome.Rejected> get() = outcomes.filterIsInstance<RowOutcome.Rejected>()
}

/** Exact amount text parsing: never rounds, never uses floating point. */
public object AmountText {
    private val POINT_GROUPED = Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""")
    private val COMMA_GROUPED = Regex("""^\d{1,3}(\.\d{3})+(,\d+)?$""")
    private val PLAIN_POINT = Regex("""^\d+(\.\d+)?$|^\.\d+$""")
    private val PLAIN_COMMA = Regex("""^\d+(,\d+)?$|^,\d+$""")
    private val SYMBOLS = Regex("""(?i)php|₱|\s| """)

    /** Parsed result: signed minor units, or the error. Blank text is `null` with no error (an empty cell). */
    public data class Parsed(
        val minor: Long?,
        val error: RowError?,
    )

    public fun parse(
        raw: String,
        currency: CurrencyCode,
        decimalComma: Boolean,
    ): Parsed {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Parsed(null, null)
        val (negative, digits) = sign(trimmed)
        val canonical = canonical(digits, decimalComma) ?: return Parsed(null, RowError.AMOUNT_UNPARSEABLE)
        val parsed = toMinor(BigDecimal(canonical), currency)
        return if (negative && parsed.minor != null) parsed.copy(minor = -parsed.minor) else parsed
    }

    /** Exact conversion to minor units: more decimals than the currency allows is an error, never rounded. */
    private fun toMinor(
        value: BigDecimal,
        currency: CurrencyCode,
    ): Parsed =
        if (value.stripTrailingZeros().scale() > currency.minorDigits) {
            Parsed(null, RowError.AMOUNT_PRECISION)
        } else {
            try {
                Parsed(value.movePointRight(currency.minorDigits).longValueExact(), null)
            } catch (_: ArithmeticException) {
                Parsed(null, RowError.AMOUNT_UNPARSEABLE)
            }
        }

    /** Strips parentheses, currency symbols and a leading/trailing sign; returns (negative, remaining text). */
    private fun sign(text: String): Pair<Boolean, String> {
        var s = text
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        s = s.replace(SYMBOLS, "")
        return when {
            s.startsWith("-") || s.startsWith("−") -> !negative to s.substring(1)
            s.endsWith("-") -> !negative to s.dropLast(1)
            s.startsWith("+") -> negative to s.substring(1)
            else -> negative to s
        }
    }

    /** Plain `123.45` text, or null if the grouping or separators do not match the chosen convention exactly. */
    private fun canonical(
        s: String,
        decimalComma: Boolean,
    ): String? =
        when {
            !decimalComma && POINT_GROUPED.matches(s) -> s.replace(",", "")
            !decimalComma && PLAIN_POINT.matches(s) -> s
            decimalComma && COMMA_GROUPED.matches(s) -> s.replace(".", "").replace(',', '.')
            decimalComma && PLAIN_COMMA.matches(s) -> s.replace(',', '.')
            else -> null
        }
}

/**
 * Stages 5–6 of the pipeline (docs/13-import-pipeline.md): every data row becomes a [NormalizedRow] or a
 * [RowOutcome.Rejected] with its row number. Blank rows are skipped. Nothing here touches the database.
 */
public object Normalizer {
    private val EARLIEST: LocalDate = LocalDate.of(1990, 1, 1)
    private val WHITESPACE = Regex("\\s+")

    public fun normalize(
        table: Table,
        mapping: ColumnMapping,
        currency: CurrencyCode,
        accountId: String,
        today: LocalDate,
    ): NormalizedFile {
        val latest = today.plusYears(1)
        val end = table.rows.size - mapping.skipBottomRows
        val outcomes = mutableListOf<RowOutcome>()
        for (i in (mapping.headerRow + 1) until end) {
            val row = table.rows[i]
            if (row.all { it.isBlank() }) continue
            outcomes += rowOutcome(table, i, row, mapping, currency, latest)
        }
        val valid = outcomes.filterIsInstance<RowOutcome.Valid>().map { it.row }
        val fingerprinted = fingerprint(valid, accountId, mapping.referenceIsUnique).associateBy { it.rowNo }
        val withFingerprints = outcomes.map { o -> if (o is RowOutcome.Valid) RowOutcome.Valid(fingerprinted.getValue(o.rowNo)) else o }
        return NormalizedFile(withFingerprints, balanceBreaks(valid))
    }

    /** Ends the current row as rejected; caught in [rowOutcome]. */
    private class RowReject(
        val error: RowError,
        val cell: String,
    ) : RuntimeException(error.name, null, false, false)

    private fun reject(
        error: RowError,
        cell: String = "",
    ): Nothing = throw RowReject(error, cell)

    private fun rowOutcome(
        table: Table,
        i: Int,
        row: List<String>,
        m: ColumnMapping,
        currency: CurrencyCode,
        latest: LocalDate,
    ): RowOutcome =
        try {
            RowOutcome.Valid(validRow(table, i, row, m, currency, latest))
        } catch (r: RowReject) {
            RowOutcome.Rejected(i + 1, r.error, r.cell)
        }

    private fun validRow(
        table: Table,
        i: Int,
        row: List<String>,
        m: ColumnMapping,
        currency: CurrencyCode,
        latest: LocalDate,
    ): NormalizedRow {
        fun cell(c: Int?) = c?.let { row.getOrElse(it) { "" }.trim() }
        val mapped = listOfNotNull(m.date, m.description, m.postingDate, m.balance) + amountColumns(m.amount)
        if (mapped.any { (i to it) in table.badCells }) reject(RowError.CELL_UNREADABLE)
        val dateText = cell(m.date).orEmpty()
        val date = DatePatterns.parse(dateText, m.datePattern) ?: reject(RowError.DATE_UNPARSEABLE, dateText)
        if (date.isBefore(EARLIEST) || date.isAfter(latest)) reject(RowError.DATE_OUT_OF_RANGE, dateText)
        val description = cell(m.description).orEmpty().replace(WHITESPACE, " ")
        if (description.isEmpty()) reject(RowError.DESCRIPTION_EMPTY)
        val minor = amountOf(row, m, currency)
        if (minor == 0L) reject(RowError.AMOUNT_ZERO, amountText(row, m.amount))
        val balance = cell(m.balance)?.takeIf { it.isNotEmpty() }?.let { Money(parseOrReject(it, currency, m.decimalComma), currency) }
        val status = cell(m.status)?.let(MappingSuggester::norm)
        return NormalizedRow(
            rowNo = i + 1,
            txnDate = date,
            postingDate = cell(m.postingDate)?.takeIf { it.isNotEmpty() }?.let { DatePatterns.parse(it, m.datePattern) },
            amount = Money(minor, currency),
            description = description,
            descriptionNorm = TextNormalizer.normalize(description),
            merchant = cell(m.merchant)?.ifEmpty { null },
            referenceNo = cell(m.reference)?.ifEmpty { null },
            runningBalance = balance,
            pending = status != null && status in m.pendingValues.map(MappingSuggester::norm),
            fingerprint = "",
        )
    }

    private fun amountColumns(a: AmountColumns): List<Int> =
        when (a) {
            is AmountColumns.Signed -> listOf(a.column)
            is AmountColumns.DebitCredit -> listOf(a.debit, a.credit)
            is AmountColumns.WithType -> listOf(a.amount, a.type)
        }

    private fun amountText(
        row: List<String>,
        a: AmountColumns,
    ): String = amountColumns(a).map { row.getOrElse(it) { "" }.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")

    /** Minor units of a non-blank amount cell, or the row is rejected with the parse error. */
    private fun parseOrReject(
        text: String,
        currency: CurrencyCode,
        decimalComma: Boolean,
    ): Long {
        val p = AmountText.parse(text, currency, decimalComma)
        p.error?.let { reject(it, text) }
        return p.minor ?: reject(RowError.AMOUNT_UNPARSEABLE, text)
    }

    /** Signed minor units (+ in, − out) for the row's amount columns. Zero is returned as is and rejected by the caller. */
    private fun amountOf(
        row: List<String>,
        m: ColumnMapping,
        currency: CurrencyCode,
    ): Long {
        fun text(c: Int) = row.getOrElse(c) { "" }.trim()
        return when (val a = m.amount) {
            is AmountColumns.Signed -> parseOrReject(text(a.column), currency, m.decimalComma).let { if (a.outflowNegative) it else -it }
            is AmountColumns.DebitCredit -> debitCredit(text(a.debit), text(a.credit), currency, m.decimalComma)
            is AmountColumns.WithType -> withType(text(a.amount), text(a.type), a, currency, m.decimalComma)
        }
    }

    private fun debitCredit(
        debitText: String,
        creditText: String,
        currency: CurrencyCode,
        decimalComma: Boolean,
    ): Long {
        val debit = debitText.takeIf { it.isNotEmpty() }?.let { parseOrReject(it, currency, decimalComma) }?.takeIf { it != 0L }
        val credit = creditText.takeIf { it.isNotEmpty() }?.let { parseOrReject(it, currency, decimalComma) }?.takeIf { it != 0L }
        if (debit != null && credit != null) reject(RowError.DEBIT_AND_CREDIT_BOTH_SET, "$debitText / $creditText")
        if (debitText.isEmpty() && creditText.isEmpty()) reject(RowError.AMOUNT_UNPARSEABLE)
        return when {
            debit != null -> -kotlin.math.abs(debit)
            credit != null -> kotlin.math.abs(credit)
            else -> 0L
        }
    }

    private fun withType(
        amountText: String,
        typeText: String,
        a: AmountColumns.WithType,
        currency: CurrencyCode,
        decimalComma: Boolean,
    ): Long {
        val magnitude = kotlin.math.abs(parseOrReject(amountText, currency, decimalComma))
        val type = MappingSuggester.norm(typeText)
        return when (type) {
            in a.debitValues.map(MappingSuggester::norm) -> -magnitude
            in a.creditValues.map(MappingSuggester::norm) -> magnitude
            else -> reject(RowError.TYPE_UNKNOWN, typeText)
        }
    }

    /**
     * Fingerprints (§5): SHA-256("v1" | account | date | amountMinor | descriptionNorm | ordinal), where ordinal counts
     * identical keys in file order; or SHA-256("v1ref" | account | reference) when references are unique.
     */
    public fun fingerprint(
        rows: List<NormalizedRow>,
        accountId: String,
        referenceIsUnique: Boolean,
    ): List<NormalizedRow> {
        val seen = HashMap<String, Int>()
        return rows.map { r ->
            val material =
                if (referenceIsUnique && r.referenceNo != null) {
                    "v1ref|$accountId|${r.referenceNo}"
                } else {
                    val key = "$accountId|${r.txnDate}|${r.amount.minor}|${r.descriptionNorm}"
                    val ordinal = (seen[key] ?: 0) + 1
                    seen[key] = ordinal
                    "v1|$key|$ordinal"
                }
            r.copy(fingerprint = sha256(material))
        }
    }

    /**
     * Running-balance continuity (§6): in file order, either bal[i] = bal[i−1] + amt[i] (oldest first) or
     * bal[i−1] = bal[i] + amt[i−1] (newest first). The direction that fits more pairs is used; pairs that fit neither
     * are reported by the later row number.
     */
    internal fun balanceBreaks(rows: List<NormalizedRow>): List<Int> {
        val pairs = rows.zipWithNext().filter { (a, b) -> a.runningBalance != null && b.runningBalance != null }
        if (pairs.isEmpty()) return emptyList()

        fun ascending(
            a: NormalizedRow,
            b: NormalizedRow,
        ) = checkNotNull(b.runningBalance).minor == checkNotNull(a.runningBalance).minor + b.amount.minor

        fun descending(
            a: NormalizedRow,
            b: NormalizedRow,
        ) = checkNotNull(a.runningBalance).minor == checkNotNull(b.runningBalance).minor + a.amount.minor
        val asc = pairs.count { (a, b) -> ascending(a, b) }
        val desc = pairs.count { (a, b) -> descending(a, b) }
        val fits: (NormalizedRow, NormalizedRow) -> Boolean = if (asc >= desc) ::ascending else ::descending
        return pairs.filterNot { (a, b) -> fits(a, b) }.map { it.second.rowNo }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
}
