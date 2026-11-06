package ph.financewallet.data.advanced

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.AuditEventEntity
import ph.financewallet.data.ledger.PageCursor
import ph.financewallet.data.ledger.TransactionQueries
import ph.financewallet.data.ledger.TxnFilter
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.money.MoneyFormatter
import java.io.Writer
import java.time.LocalDate

/**
 * Transaction export to CSV (UC-30, RPT-04). The file is **plaintext**: the UI must warn before export and let the user
 * pick the destination (Storage Access Framework). Amounts are exact decimal text from integer minor units (no floating
 * point). Text fields are RFC 4180-quoted, and cells that a spreadsheet would treat as a formula are prefixed with `'`
 * (CSV injection). Rows stream page by page, so memory does not grow with the history.
 */
class CsvExporter(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val queries = TransactionQueries(db)

    /** Writes live transactions dated [from]..[to] (newest first) and returns the number of rows. */
    suspend fun exportTransactions(
        from: LocalDate,
        to: LocalDate,
        out: Writer,
    ): Int {
        require(!to.isBefore(from)) { "to must not be before from" }
        out.write(HEADER.joinToString(",") + "\r\n")
        var cursor: PageCursor? = null
        var rows = 0
        do {
            val page = queries.page(after = cursor, limit = TransactionQueries.MAX_PAGE, filter = TxnFilter(from = from, to = to))
            page.items.forEach { t ->
                val cells =
                    listOf(
                        t.date.toString(),
                        t.type.name,
                        if (t.pending) "PENDING" else "POSTED",
                        t.description,
                        t.merchant.orEmpty(),
                        t.accountName,
                        t.counterpartyName.orEmpty(),
                        t.categoryNames.joinToString("; "),
                        MoneyFormatter.plain(t.amount),
                        t.amount.currency.code,
                        t.id,
                    )
                out.write(cells.joinToString(",") { escape(it) } + "\r\n")
                rows++
            }
            cursor = page.next
        } while (cursor != null)
        out.flush()
        db.accountDao().insertAudit(
            AuditEventEntity(
                ctx.ids.newId(),
                "EXPORT_CSV",
                "transactions",
                null,
                "{\"rows\":\"$rows\",\"from\":\"$from\",\"to\":\"$to\"}",
                ctx.deviceId,
                ctx.nowMillis(),
            ),
        )
        return rows
    }

    companion object {
        val HEADER =
            listOf(
                "date",
                "type",
                "status",
                "description",
                "merchant",
                "account",
                "counter_account",
                "categories",
                "amount",
                "currency",
                "id",
            )
        private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')
        private val NUMBER = Regex("-?\\d+(\\.\\d+)?")

        /** RFC 4180 quoting plus formula neutralization; plain numbers (our amounts) stay numeric. */
        fun escape(value: String): String {
            val safe = if (value.isNotEmpty() && value[0] in FORMULA_START && !NUMBER.matches(value)) "'$value" else value
            val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || safe != safe.trim()
            return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
    }
}
