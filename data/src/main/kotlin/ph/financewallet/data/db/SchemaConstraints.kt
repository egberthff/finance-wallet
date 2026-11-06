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
    private data class Check(
        val table: String,
        val code: String,
        val violation: String,
    )

    private fun enumIn(
        column: String,
        vararg values: String,
    ) = "NEW.$column NOT IN (${values.joinToString(",") { "'$it'" }})"

    private val V1_CHECKS =
        listOf(
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
                    "type",
                    "INCOME",
                    "EXPENSE",
                    "TRANSFER",
                    "SAVINGS_CONTRIBUTION",
                    "SAVINGS_WITHDRAWAL",
                    "REFUND",
                    "REVERSAL",
                    "BALANCE_ADJUSTMENT",
                    "DEBT_PAYMENT",
                    "DEBT_DISBURSEMENT",
                    "OPENING_BALANCE",
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

    private const val ISO_DATE = "'[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'"

    private fun bool(column: String) = "NEW.$column NOT IN (0,1)"

    private fun optionalRange(
        column: String,
        min: Int,
        max: Int,
    ) = "NEW.$column IS NOT NULL AND NEW.$column NOT BETWEEN $min AND $max"

    /** Schema v2: reconciliation, budgets, goals, allocation ledger (docs/11-database-schema.md §3.3–3.4). */
    private val V2_CHECKS =
        listOf(
            Check("reconciliation", "CK_RECON_SOURCE", enumIn("source", "MANUAL", "IMPORT")),
            Check("reconciliation", "CK_RECON_DATE", "NEW.as_of_date NOT GLOB $ISO_DATE"),
            Check(
                "reconciliation",
                "CK_RECON_DIFFERENCE",
                "NEW.difference_minor <> NEW.statement_balance_minor - NEW.calculated_balance_minor",
            ),
            Check("budget", "CK_BUDGET_NAME", "length(trim(NEW.name)) NOT BETWEEN 1 AND 80"),
            Check("budget", "CK_BUDGET_CURRENCY", "length(NEW.currency) <> 3"),
            Check(
                "budget",
                "CK_BUDGET_PERIOD_KIND",
                enumIn("period_kind", "WEEKLY", "BIWEEKLY", "EVERY_N_DAYS", "MONTHLY", "SEMI_MONTHLY", "CUSTOM_ONCE"),
            ),
            Check("budget", "CK_BUDGET_ANCHOR_DATE", "NEW.anchor_date NOT GLOB $ISO_DATE"),
            Check("budget", "CK_BUDGET_EFFECTIVE_DATE", "NEW.rule_effective_from NOT GLOB $ISO_DATE"),
            Check("budget", "CK_BUDGET_ANCHOR_DAY", optionalRange("anchor_day", 1, 31)),
            Check("budget", "CK_BUDGET_ANCHOR_DAY2", optionalRange("anchor_day2", 1, 31)),
            Check("budget", "CK_BUDGET_EVERY_N", optionalRange("every_n_days", 1, 366)),
            Check("budget", "CK_BUDGET_INCOME_MODE", enumIn("income_mode", "TRACKED", "FIXED", "MANUAL")),
            Check("budget", "CK_BUDGET_FIXED_INCOME", "NEW.income_mode = 'FIXED' AND NEW.fixed_income_minor IS NULL"),
            Check("budget", "CK_BUDGET_ACCOUNT_FILTER", bool("account_filter")),
            Check("budget_category", "CK_BC_MODE", enumIn("allocation_mode", "NONE", "FIXED", "PERCENT")),
            Check("budget_category", "CK_BC_PERCENT", optionalRange("percent_bp", 0, 10000)),
            Check("budget_category", "CK_BC_ROLLOVER", enumIn("rollover_policy", "EXPIRE", "CARRY", "SWEEP_TO_GOAL", "MANUAL")),
            Check("budget_category", "CK_BC_CARRY_NEGATIVE", bool("carry_negative")),
            Check("budget_category", "CK_BC_SWEEP_GOAL", "NEW.rollover_policy = 'SWEEP_TO_GOAL' AND NEW.sweep_goal_id IS NULL"),
            Check("budget_period", "CK_PERIOD_DATES", "NEW.end_date < NEW.start_date"),
            Check("budget_period", "CK_PERIOD_DATE_FORMAT", "NEW.start_date NOT GLOB $ISO_DATE OR NEW.end_date NOT GLOB $ISO_DATE"),
            Check("savings_goal", "CK_GOAL_NAME", "length(trim(NEW.name)) NOT BETWEEN 1 AND 80"),
            Check("savings_goal", "CK_GOAL_CURRENCY", "length(NEW.currency) <> 3"),
            Check("savings_goal", "CK_GOAL_TARGET", "NEW.target_minor <= 0"),
            Check("savings_goal", "CK_GOAL_STATUS", enumIn("status", "ACTIVE", "PAUSED", "ACHIEVED", "ARCHIVED")),
            Check("savings_goal", "CK_GOAL_NOTES", "NEW.notes IS NOT NULL AND length(NEW.notes) > 2000"),
            Check(
                "allocation_group",
                "CK_ALLOC_KIND",
                enumIn(
                    "kind",
                    "FUND",
                    "ALLOCATE",
                    "REALLOCATE",
                    "GOAL_CONTRIBUTE",
                    "GOAL_WITHDRAW",
                    "GOAL_DIRECT",
                    "GOAL_TRANSFER",
                    "ROLLOVER",
                    "SWEEP",
                ),
            ),
            Check("allocation_group", "CK_ALLOC_REASON", "NEW.reason IS NOT NULL AND length(NEW.reason) > 500"),
            Check("allocation_group", "CK_ALLOC_DATE", "NEW.effective_date NOT GLOB $ISO_DATE"),
            Check(
                "allocation_group",
                "CK_ALLOC_CONFIRMATION",
                "NEW.kind IN ('GOAL_WITHDRAW','GOAL_TRANSFER') AND NEW.confirmed_at IS NULL",
            ),
            Check("allocation_entry", "CK_ALLOC_BUCKET", enumIn("bucket", "UNALLOCATED", "CATEGORY", "GOAL")),
            Check("allocation_entry", "CK_ALLOC_AMOUNT_NONZERO", "NEW.amount_minor = 0"),
            Check("allocation_entry", "CK_ALLOC_AMOUNT_INTEGER", "typeof(NEW.amount_minor) <> 'integer'"),
            Check("allocation_entry", "CK_ALLOC_CURRENCY", "length(NEW.currency) <> 3"),
            Check(
                "allocation_entry",
                "CK_ALLOC_BUCKET_SHAPE",
                "NOT ((NEW.bucket = 'UNALLOCATED' AND NEW.budget_period_id IS NOT NULL AND NEW.category_id IS NULL " +
                    "AND NEW.goal_id IS NULL) " +
                    "OR (NEW.bucket = 'CATEGORY' AND NEW.budget_period_id IS NOT NULL AND NEW.category_id IS NOT NULL " +
                    "AND NEW.goal_id IS NULL) " +
                    "OR (NEW.bucket = 'GOAL' AND NEW.goal_id IS NOT NULL AND NEW.category_id IS NULL))",
            ),
        )

    /** Schema v3: debts, assets, recurring rules and occurrences (docs/11-database-schema.md §3.5–3.6). */
    private val V3_CHECKS =
        listOf(
            Check("debt", "CK_DEBT_KIND", enumIn("kind", "PERSONAL_LOAN", "CREDIT_CARD", "INSTALLMENT", "BORROWED", "OTHER")),
            Check("debt", "CK_DEBT_ORIGINAL", "NEW.original_minor <= 0"),
            Check("debt", "CK_DEBT_START", "NEW.start_date NOT GLOB $ISO_DATE"),
            Check("debt", "CK_DEBT_RATE", "NEW.interest_rate_bp IS NOT NULL AND NEW.interest_rate_bp < 0"),
            Check("debt", "CK_DEBT_BASIS", "NEW.interest_basis IS NOT NULL AND NEW.interest_basis NOT IN ('ANNUAL','MONTHLY','ADD_ON')"),
            Check(
                "debt",
                "CK_DEBT_FREQUENCY",
                "NEW.payment_frequency IS NOT NULL AND NEW.payment_frequency NOT IN " +
                    "('WEEKLY','BIWEEKLY','SEMI_MONTHLY','MONTHLY','QUARTERLY','YEARLY','IRREGULAR')",
            ),
            Check("debt", "CK_DEBT_INSTALLMENT", "NEW.installment_minor IS NOT NULL AND NEW.installment_minor <= 0"),
            Check("debt", "CK_DEBT_INSTALLMENTS_TOTAL", "NEW.installments_total IS NOT NULL AND NEW.installments_total <= 0"),
            Check("debt", "CK_DEBT_DUE_DAY", optionalRange("due_day", 1, 31)),
            Check("debt", "CK_DEBT_STATUS", enumIn("status", "ACTIVE", "PAID_OFF", "DEFAULTED", "CLOSED")),
            Check("debt_schedule_item", "CK_SCHED_SEQ", "NEW.seq < 1"),
            Check("debt_schedule_item", "CK_SCHED_AMOUNT", "NEW.amount_due_minor <= 0"),
            Check("debt_schedule_item", "CK_SCHED_DATE", "NEW.due_date NOT GLOB $ISO_DATE"),
            Check("asset", "CK_ASSET_NAME", "length(trim(NEW.name)) NOT BETWEEN 1 AND 80"),
            Check("asset", "CK_ASSET_KIND", enumIn("kind", "INVESTMENT", "PROPERTY", "VEHICLE", "RECEIVABLE", "OTHER")),
            Check("asset", "CK_ASSET_CURRENCY", "length(NEW.currency) <> 3"),
            Check("asset", "CK_ASSET_NET_WORTH", bool("include_in_net_worth")),
            Check("asset", "CK_ASSET_NOTES", "NEW.notes IS NOT NULL AND length(NEW.notes) > 2000"),
            Check("asset_valuation", "CK_VALUATION_VALUE", "NEW.value_minor < 0"),
            Check("asset_valuation", "CK_VALUATION_DATE", "NEW.as_of_date NOT GLOB $ISO_DATE"),
            Check("recurring_rule", "CK_RULE_KIND", enumIn("kind", "BILL", "SUBSCRIPTION", "RECURRING_TXN", "EXPECTED_INCOME")),
            Check("recurring_rule", "CK_RULE_NAME", "length(trim(NEW.name)) NOT BETWEEN 1 AND 80"),
            Check("recurring_rule", "CK_RULE_AMOUNT", "NEW.amount_minor <= 0"),
            Check("recurring_rule", "CK_RULE_CURRENCY", "length(NEW.currency) <> 3"),
            Check("recurring_rule", "CK_RULE_ESTIMATE", bool("amount_is_estimate")),
            Check(
                "recurring_rule",
                "CK_RULE_FREQUENCY",
                enumIn("frequency", "WEEKLY", "BIWEEKLY", "EVERY_N_DAYS", "MONTHLY", "SEMI_MONTHLY", "QUARTERLY", "YEARLY"),
            ),
            Check("recurring_rule", "CK_RULE_MONTH", optionalRange("month_of_year", 1, 12)),
            Check("recurring_rule", "CK_RULE_ANCHOR", "NEW.anchor_date NOT GLOB $ISO_DATE"),
            Check("recurring_rule", "CK_RULE_AUTO_POST", bool("auto_post")),
            // Only a recurring transaction template may post by itself, and only when the user opts in (INC-03, BILL-02).
            Check("recurring_rule", "CK_RULE_AUTO_POST_KIND", "NEW.auto_post = 1 AND NEW.kind <> 'RECURRING_TXN'"),
            Check("recurring_rule", "CK_RULE_REMINDER", optionalRange("reminder_days_before", 0, 30)),
            Check("recurring_rule", "CK_RULE_STATUS", enumIn("status", "ACTIVE", "PAUSED", "ENDED")),
            Check("occurrence", "CK_OCC_STATUS", enumIn("status", "UPCOMING", "PAID", "SKIPPED")),
            Check("occurrence", "CK_OCC_DATE", "NEW.due_date NOT GLOB $ISO_DATE"),
            Check("occurrence", "CK_OCC_AMOUNT", "NEW.amount_minor <= 0"),
            // BILL-02: PAID exactly when a real transaction is linked.
            Check("occurrence", "CK_OCC_PAID_LINK", "(NEW.status = 'PAID') <> (NEW.paid_txn_id IS NOT NULL)"),
            // Reference check for txn.recurring_occurrence_id (a v1 column; see Migrations.MIGRATION_2_3).
            Check(
                "txn",
                "CK_TXN_OCCURRENCE_REF",
                "NEW.recurring_occurrence_id IS NOT NULL AND " +
                    "NOT EXISTS (SELECT 1 FROM occurrence o WHERE o.id = NEW.recurring_occurrence_id)",
            ),
        )

    /** Schema v4: statement import and categorization rules (docs/11-database-schema.md §3.7–3.8). */
    private val V4_CHECKS =
        listOf(
            Check("import_profile", "CK_PROFILE_NAME", "length(trim(NEW.name)) NOT BETWEEN 1 AND 80"),
            Check("import_profile", "CK_PROFILE_FORMAT", enumIn("format", "CSV", "XLSX", "PDF")),
            Check("import_profile", "CK_PROFILE_BUILTIN", bool("builtin")),
            Check("import_batch", "CK_BATCH_FORMAT", enumIn("format", "CSV", "XLSX", "PDF")),
            Check("import_batch", "CK_BATCH_STATUS", enumIn("status", "COMMITTED", "ROLLED_BACK")),
            Check("import_batch", "CK_BATCH_SHA256", "length(NEW.file_sha256) <> 64"),
            Check(
                "import_batch",
                "CK_BATCH_COUNTS",
                "NEW.rows_total <> NEW.rows_imported + NEW.rows_duplicate + NEW.rows_rejected + NEW.rows_skipped_by_user " +
                    "OR NEW.rows_imported < 0 OR NEW.rows_duplicate < 0 OR NEW.rows_rejected < 0 OR NEW.rows_skipped_by_user < 0",
            ),
            Check("import_batch", "CK_BATCH_ROLLBACK", "(NEW.status = 'ROLLED_BACK') <> (NEW.rolled_back_at IS NOT NULL)"),
            Check("import_row", "CK_IMPORT_ROW_OUTCOME", enumIn("outcome", "IMPORTED", "DUPLICATE", "REJECTED", "SKIPPED")),
            Check("import_row", "CK_IMPORT_ROW_TXN", "NEW.outcome = 'IMPORTED' AND NEW.txn_id IS NULL"),
            Check("category_rule", "CK_CATRULE_FIELD", enumIn("match_field", "DESCRIPTION", "MERCHANT", "ANY_TEXT")),
            Check("category_rule", "CK_CATRULE_OP", enumIn("match_op", "EQUALS", "CONTAINS", "STARTS_WITH")),
            Check("category_rule", "CK_CATRULE_PATTERN", "length(NEW.pattern_norm) NOT BETWEEN 1 AND 120"),
            Check("category_rule", "CK_CATRULE_MODE", enumIn("mode", "SUGGEST", "AUTO")),
            Check("category_rule", "CK_CATRULE_ENABLED", bool("enabled")),
            Check(
                "category_rule",
                "CK_CATRULE_RANGE",
                "NEW.min_minor IS NOT NULL AND NEW.max_minor IS NOT NULL AND NEW.min_minor > NEW.max_minor",
            ),
            // Deferred references from v1/v2 columns (see Migrations.MIGRATION_3_4).
            Check(
                "txn",
                "CK_TXN_IMPORT_BATCH_REF",
                "NEW.import_batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch b WHERE b.id = NEW.import_batch_id)",
            ),
            Check(
                "reconciliation",
                "CK_RECON_IMPORT_BATCH_REF",
                "NEW.import_batch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM import_batch b WHERE b.id = NEW.import_batch_id)",
            ),
        )

    /** Append-only tables: updates and deletes are rejected (SEC-04, TXN-06). */
    private val APPEND_ONLY_V1 = listOf("txn_revision", "audit_event")

    /** Allocation entries are immutable once written; corrections are new allocation groups (SAV-04). */
    private const val IMMUTABLE_V2 = "allocation_entry"

    private fun checkTriggers(checks: List<Check>): List<String> =
        checks.flatMap { c ->
            listOf("INSERT", "UPDATE").map { op ->
                "CREATE TRIGGER IF NOT EXISTS trg_${c.code.lowercase()}_${op.lowercase()} BEFORE $op ON ${c.table} " +
                    "WHEN ${c.violation} BEGIN SELECT RAISE(ABORT, '${c.code}'); END"
            }
        }

    /** All statements needed for schema v1, in order. */
    val v1Statements: List<String> =
        buildList {
            addAll(checkTriggers(V1_CHECKS))
            APPEND_ONLY_V1.forEach { t ->
                add("CREATE TRIGGER IF NOT EXISTS trg_${t}_no_update BEFORE UPDATE ON $t BEGIN SELECT RAISE(ABORT, 'APPEND_ONLY'); END")
                add("CREATE TRIGGER IF NOT EXISTS trg_${t}_no_delete BEFORE DELETE ON $t BEGIN SELECT RAISE(ABORT, 'APPEND_ONLY'); END")
            }
        }

    /** Triggers added by migration 1→2 (and created directly for new databases). */
    val v2Statements: List<String> =
        checkTriggers(V2_CHECKS) +
            listOf(
                "CREATE TRIGGER IF NOT EXISTS trg_${IMMUTABLE_V2}_no_update BEFORE UPDATE ON $IMMUTABLE_V2 " +
                    "BEGIN SELECT RAISE(ABORT, 'IMMUTABLE'); END",
            )

    /** Triggers added by migration 2→3. */
    val v3Statements: List<String> = checkTriggers(V3_CHECKS)

    /** Triggers added by migration 3→4. */
    val v4Statements: List<String> = checkTriggers(V4_CHECKS)

    /** Every trigger statement of the current schema, for new databases. */
    val allStatements: List<String> get() = v1Statements + v2Statements + v3Statements + v4Statements
}
