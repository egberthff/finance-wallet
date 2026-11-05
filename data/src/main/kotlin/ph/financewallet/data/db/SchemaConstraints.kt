package ph.financewallet.data.db

/**
 * Database-level constraints that Room entities cannot declare (Room has no CHECK support).
 *
 * The reference DDL (docs/11-database-schema.md) expresses these as CHECK constraints. Here they are
 * BEFORE INSERT / BEFORE UPDATE triggers that abort with a stable error code, giving the same guarantee:
 * an invalid row can never be stored, whatever code path writes it.
 *
 * Triggers are created in [FinanceDatabase] onCreate for new databases. Every future migration that
 * adds a table must also add that table's constraint triggers.
 */
internal object SchemaConstraints {

    private data class Check(val table: String, val code: String, val violation: String)

    private fun enumIn(column: String, vararg values: String) =
        "NEW.$column NOT IN (${values.joinToString(",") { "'$it'" }})"

    private val V1_CHECKS = listOf(
        Check("ledger_account", "CK_LEDGER_CLASS", enumIn("class", "ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY")),
        Check("ledger_account", "CK_LEDGER_NAME", "length(trim(NEW.name)) NOT BETWEEN 1 AND 80"),
        Check("ledger_account", "CK_LEDGER_CURRENCY", "NEW.currency IS NOT NULL AND length(NEW.currency) <> 3"),
        Check(
            "ledger_account",
            "CK_LEDGER_REAL_NEEDS_CURRENCY",
            "NEW.class IN ('ASSET','LIABILITY') AND NEW.currency IS NULL",
        ),
        Check(
            "financial_account",
            "CK_FA_TYPE",
            enumIn("type", "CASH", "BANK", "EWALLET", "SAVINGS", "CREDIT_CARD", "LOAN", "BORROWED", "OTHER"),
        ),
        Check(
            "financial_account",
            "CK_FA_LAST4",
            "NEW.account_number_last4 IS NOT NULL AND length(NEW.account_number_last4) > 4",
        ),
        Check(
            "financial_account",
            "CK_FA_REMINDER",
            "NEW.reconcile_reminder_days IS NOT NULL AND NEW.reconcile_reminder_days <= 0",
        ),
        Check(
            "txn",
            "CK_TXN_TYPE",
            enumIn(
                "type", "INCOME", "EXPENSE", "TRANSFER", "SAVINGS_CONTRIBUTION", "SAVINGS_WITHDRAWAL", "REFUND",
                "REVERSAL", "BALANCE_ADJUSTMENT", "DEBT_PAYMENT", "DEBT_DISBURSEMENT", "OPENING_BALANCE",
            ),
        ),
        Check("txn", "CK_TXN_STATUS", enumIn("status", "PENDING", "POSTED")),
        Check("txn", "CK_TXN_SOURCE", enumIn("source", "MANUAL", "STATEMENT_IMPORT", "SYSTEM")),
        Check("txn", "CK_TXN_CONFLICT", enumIn("conflict_state", "NONE", "CONFLICTED")),
        Check("txn", "CK_TXN_DATE", "NEW.txn_date NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'"),
        Check("txn", "CK_TXN_DESCRIPTION", "length(NEW.description) > 200"),
        Check("txn", "CK_TXN_MERCHANT", "NEW.merchant IS NOT NULL AND length(NEW.merchant) > 120"),
        Check("txn", "CK_TXN_NOTES", "NEW.notes IS NOT NULL AND length(NEW.notes) > 2000"),
        Check("txn", "CK_TXN_REFERENCE", "NEW.reference_no IS NOT NULL AND length(NEW.reference_no) > 64"),
        Check("entry", "CK_ENTRY_AMOUNT_NONZERO", "NEW.amount_minor = 0"),
        Check("entry", "CK_ENTRY_AMOUNT_INTEGER", "typeof(NEW.amount_minor) <> 'integer'"),
        Check("entry", "CK_ENTRY_CURRENCY", "length(NEW.currency) <> 3"),
        Check("entry", "CK_ENTRY_LINE", "NEW.line_no < 0"),
        Check("entry", "CK_ENTRY_MEMO", "NEW.memo IS NOT NULL AND length(NEW.memo) > 200"),
        Check(
            "txn_revision",
            "CK_REV_KIND",
            enumIn("change_kind", "CREATE", "UPDATE", "DELETE", "RESTORE", "IMPORT_ROLLBACK", "SYNC_MERGE"),
        ),
        Check("txn_revision", "CK_REV_NO", "NEW.revision_no < 1"),
    )

    /** Append-only tables: updates and deletes are rejected (SEC-04, TXN-06). */
    private val APPEND_ONLY_V1 = listOf("txn_revision", "audit_event")

    /** All statements needed for schema v1, in order. */
    val v1Statements: List<String> = buildList {
        V1_CHECKS.forEach { c ->
            for (op in listOf("INSERT", "UPDATE")) {
                val name = "trg_${c.code.lowercase()}_${op.lowercase()}"
                add(
                    "CREATE TRIGGER IF NOT EXISTS $name BEFORE $op ON ${c.table} " +
                        "WHEN ${c.violation} BEGIN SELECT RAISE(ABORT, '${c.code}'); END",
                )
            }
        }
        APPEND_ONLY_V1.forEach { t ->
            add("CREATE TRIGGER IF NOT EXISTS trg_${t}_no_update BEFORE UPDATE ON $t BEGIN SELECT RAISE(ABORT, 'APPEND_ONLY'); END")
            add("CREATE TRIGGER IF NOT EXISTS trg_${t}_no_delete BEFORE DELETE ON $t BEGIN SELECT RAISE(ABORT, 'APPEND_ONLY'); END")
        }
    }
}
