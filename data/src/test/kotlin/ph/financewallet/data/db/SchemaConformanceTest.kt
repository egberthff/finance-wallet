package ph.financewallet.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps code and documentation in sync (Phase 3 acceptance criterion 4): every table in the Room-exported
 * schema must exist in the reference DDL of docs/11-database-schema.md with exactly the same columns.
 * Tables of later schema versions may exist only in the document.
 */
class SchemaConformanceTest {
    private val moduleDir = File(checkNotNull(System.getProperty("user.dir")) { "user.dir not set" })
    private val referenceDoc = File(moduleDir, "../docs/11-database-schema.md")
    private val exportedSchema =
        File(moduleDir, "schemas/ph.financewallet.data.db.FinanceDatabase/${FinanceDatabase.SCHEMA_VERSION}.json")

    private val syncCols = listOf("version", "updated_at", "updated_by_device", "hlc")

    /** Column names per table from the `CREATE TABLE` blocks in the reference document. */
    private fun referenceTables(): Map<String, Set<String>> {
        val sqlBlock = Regex("```sql\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val docText = referenceDoc.readText().replace("\r\n", "\n")
        val sql = sqlBlock.findAll(docText).joinToString("\n") { it.groupValues[1] }
        val tableBlock = Regex("""CREATE TABLE (\w+) \((.*?)\n\);""", RegexOption.DOT_MATCHES_ALL)
        val constraintKeywords = setOf("PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "CONSTRAINT")
        return tableBlock.findAll(sql).associate { m ->
            val columns =
                m.groupValues[2].lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("--") }.flatMap { line ->
                    val first = line.substringBefore(' ').trimEnd(',')
                    when {
                        first == "SYNC_COLS" -> syncCols
                        first.uppercase() in constraintKeywords || first.startsWith("(") || first.startsWith("OR") -> emptyList()
                        Regex("[a-z_][a-z0-9_]*").matches(first) -> listOf(first)
                        else -> emptyList()
                    }
                }
            m.groupValues[1] to columns.toSet()
        }
    }

    /** Column names per table from Room's exported schema JSON. */
    private fun exportedTables(): Map<String, Set<String>> {
        val json = exportedSchema.readText()
        val tableName = Regex(""""tableName"\s*:\s*"(\w+)"""")
        val columnName = Regex(""""columnName"\s*:\s*"(\w+)"""")
        val starts = tableName.findAll(json).toList()
        return starts
            .mapIndexed { i, m ->
                val end = starts.getOrNull(i + 1)?.range?.first ?: json.length
                val section = json.substring(m.range.last, end).substringBefore("\"primaryKey\"")
                m.groupValues[1] to columnName.findAll(section).map { it.groupValues[1] }.toSet()
            }.toMap()
    }

    @Test
    fun `exported Room schema matches the reference DDL column for column`() {
        assertTrue("reference doc missing: $referenceDoc", referenceDoc.exists())
        assertTrue("exported schema missing: $exportedSchema", exportedSchema.exists())
        val reference = referenceTables()
        val exported = exportedTables()
        assertTrue("parsed too few reference tables: ${reference.keys}", reference.size >= 20)
        val v1 = "ledger_account financial_account category txn entry txn_revision audit_event app_meta"
        val v2 =
            "reconciliation budget budget_category budget_account budget_period savings_goal goal_account " +
                "allocation_group allocation_entry"
        val v3 = "debt debt_schedule_item asset asset_valuation recurring_rule occurrence"
        assertEquals("tables of schema v${FinanceDatabase.SCHEMA_VERSION}", "$v1 $v2 $v3".split(' ').toSet(), exported.keys)
        exported.forEach { (table, columns) ->
            val expected = reference[table] ?: error("table '$table' is not in the reference DDL")
            assertEquals("columns of '$table'", expected, columns)
        }
    }
}
