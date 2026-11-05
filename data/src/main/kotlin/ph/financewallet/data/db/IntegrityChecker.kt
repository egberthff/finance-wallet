package ph.financewallet.data.db

/** Result of a full integrity run. [failures] maps check id (e.g. "IC-1") to offending rows. */
data class IntegrityReport(
    val failures: Map<String, List<String>>,
) {
    val isHealthy: Boolean get() = failures.isEmpty()
}

/**
 * Runs all integrity checks (docs/11-database-schema.md §5): in tests, after migrations, after restore,
 * and on demand. Never modifies data.
 */
class IntegrityChecker(
    private val db: FinanceDatabase,
) {
    suspend fun run(): IntegrityReport {
        val dao = db.integrityDao()
        val results =
            linkedMapOf(
                "IC-1" to dao.ic1UnbalancedTransactions().map { it.detail },
                "IC-2" to dao.ic2CurrencyMismatches().map { it.detail },
                "IC-3" to dao.ic3TransactionsWithTooFewEntries().map { it.detail },
                "IC-4" to dao.ic4DuplicateOpeningBalances().map { it.detail },
                "IC-5" to dao.ic5UnbalancedAllocations().map { it.detail },
                "IC-7" to dao.ic7OverRefunded().map { it.detail },
                "IC-8" to pragmaChecks(),
            )
        return IntegrityReport(results.filterValues { it.isNotEmpty() })
    }

    private fun pragmaChecks(): List<String> {
        val problems = mutableListOf<String>()
        val sqlite = db.openHelper.readableDatabase
        sqlite.query("PRAGMA foreign_key_check").use { c ->
            while (c.moveToNext()) problems += "foreign_key_check: table=${c.getString(0)} rowid=${c.getLong(1)} parent=${c.getString(2)}"
        }
        sqlite.query("PRAGMA integrity_check").use { c ->
            while (c.moveToNext()) {
                val line = c.getString(0)
                if (line != "ok") problems += "integrity_check: $line"
            }
        }
        return problems
    }
}
