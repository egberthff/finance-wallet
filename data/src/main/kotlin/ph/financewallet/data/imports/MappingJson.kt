package ph.financewallet.data.imports

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import ph.financewallet.importers.AmountColumns
import ph.financewallet.importers.ColumnMapping

/**
 * `import_profile.mapping_json` (docs/11-database-schema.md §3.7): a saved column mapping plus the header signature used
 * to recognize the same layout again. Uses the platform org.json, so no serialization library is added.
 */
object MappingJson {
    private const val VERSION = 1

    fun encode(
        mapping: ColumnMapping,
        header: List<String>,
    ): String =
        JSONObject()
            .put("v", VERSION)
            .put("header", JSONArray(header))
            .put("headerRow", mapping.headerRow)
            .put("date", mapping.date)
            .put("description", mapping.description)
            .put("datePattern", mapping.datePattern)
            .putOpt("postingDate", mapping.postingDate)
            .putOpt("merchant", mapping.merchant)
            .putOpt("reference", mapping.reference)
            .putOpt("balance", mapping.balance)
            .putOpt("status", mapping.status)
            .put("pendingValues", JSONArray(mapping.pendingValues.sorted()))
            .put("decimalComma", mapping.decimalComma)
            .put("skipBottomRows", mapping.skipBottomRows)
            .put("referenceIsUnique", mapping.referenceIsUnique)
            .put("amount", amountJson(mapping.amount))
            .toString()

    /** Mapping and header signature, or null if the JSON is not a valid version-1 mapping. */
    fun decode(json: String): Pair<ColumnMapping, List<String>>? =
        try {
            val o = JSONObject(json)
            if (o.getInt("v") != VERSION) {
                null
            } else {
                val mapping =
                    ColumnMapping(
                        headerRow = o.getInt("headerRow"),
                        date = o.getInt("date"),
                        description = o.getInt("description"),
                        amount = amountOf(o.getJSONObject("amount")),
                        datePattern = o.getString("datePattern"),
                        postingDate = o.optIntOrNull("postingDate"),
                        merchant = o.optIntOrNull("merchant"),
                        reference = o.optIntOrNull("reference"),
                        balance = o.optIntOrNull("balance"),
                        status = o.optIntOrNull("status"),
                        pendingValues = o.getJSONArray("pendingValues").strings().toSet(),
                        decimalComma = o.getBoolean("decimalComma"),
                        skipBottomRows = o.getInt("skipBottomRows"),
                        referenceIsUnique = o.getBoolean("referenceIsUnique"),
                    )
                mapping to o.getJSONArray("header").strings()
            }
        } catch (_: JSONException) {
            null
        }

    private fun amountJson(a: AmountColumns): JSONObject =
        when (a) {
            is AmountColumns.Signed -> {
                JSONObject().put("mode", "SIGNED").put("column", a.column).put("outflowNegative", a.outflowNegative)
            }

            is AmountColumns.DebitCredit -> {
                JSONObject().put("mode", "DEBIT_CREDIT").put("debit", a.debit).put("credit", a.credit)
            }

            is AmountColumns.WithType -> {
                JSONObject()
                    .put("mode", "WITH_TYPE")
                    .put("amount", a.amount)
                    .put("type", a.type)
                    .put("debitValues", JSONArray(a.debitValues.sorted()))
                    .put("creditValues", JSONArray(a.creditValues.sorted()))
            }
        }

    private fun amountOf(o: JSONObject): AmountColumns =
        when (o.getString("mode")) {
            "SIGNED" -> {
                AmountColumns.Signed(o.getInt("column"), o.getBoolean("outflowNegative"))
            }

            "DEBIT_CREDIT" -> {
                AmountColumns.DebitCredit(o.getInt("debit"), o.getInt("credit"))
            }

            "WITH_TYPE" -> {
                AmountColumns.WithType(
                    o.getInt("amount"),
                    o.getInt("type"),
                    o.getJSONArray("debitValues").strings().toSet(),
                    o.getJSONArray("creditValues").strings().toSet(),
                )
            }

            else -> {
                throw JSONException("unknown amount mode")
            }
        }

    private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
