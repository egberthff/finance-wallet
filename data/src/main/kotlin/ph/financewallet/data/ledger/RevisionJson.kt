package ph.financewallet.data.ledger

import ph.financewallet.data.db.entity.EntryEntity
import ph.financewallet.data.db.entity.TxnEntity

/**
 * Full before/after snapshots for `txn_revision` (TXN-06). Hand-written JSON so no serialization dependency is
 * needed; amounts are integer minor units written as JSON strings (no floating point anywhere).
 */
object RevisionJson {
    fun of(
        txn: TxnEntity,
        entries: List<EntryEntity>,
    ): String =
        buildString {
            append('{')
            field("id", txn.id).append(',')
            field("type", txn.type).append(',')
            field("status", txn.status).append(',')
            field("txnDate", txn.txnDate).append(',')
            field("description", txn.description).append(',')
            field("merchant", txn.merchant).append(',')
            field("notes", txn.notes).append(',')
            field("referenceNo", txn.referenceNo).append(',')
            field("version", txn.version.toString()).append(',')
            field("deleted", (txn.deletedAt != null).toString()).append(',')
            append("\"entries\":[")
            entries.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append('{')
                field("account", e.ledgerAccountId).append(',')
                field("amountMinor", e.amountMinor.toString()).append(',')
                field("currency", e.currency)
                append('}')
            }
            append("]}")
        }

    private fun StringBuilder.field(
        name: String,
        value: String?,
    ): StringBuilder {
        append('"').append(name).append("\":")
        if (value == null) append("null") else append('"').append(escape(value)).append('"')
        return this
    }

    internal fun escape(s: String): String =
        buildString(s.length) {
            s.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
        }
}
