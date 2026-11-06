package ph.financewallet.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Forward-only, hand-written migrations (docs/11-database-schema.md §6). Each statement is the `createSql` Room
 * exported for that version (data/schemas/…/<n>.json), so the migrated schema is identical to a fresh install;
 * MigrationTestHelper verifies this. Constraint triggers come from [SchemaConstraints].
 */
object Migrations {
    /**
     * 1 → 2: reconciliation (UC-13), budgets and periods (UC-16..19), savings goals (UC-20/21) and the allocation
     * ledger. Additive only: no existing table or row changes.
     */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                V2_TABLES.forEach(db::execSQL)
                SchemaConstraints.v2Statements.forEach(db::execSQL)
            }
        }

    /**
     * 2 → 3: debts and schedules (UC-11/12), assets and valuations (UC-25), recurring rules and occurrences (UC-22..24).
     * Additive only. `txn.recurring_occurrence_id` gets its reference check as a trigger (adding a foreign key to an
     * existing column would need the 12-step table rebuild of `txn`, which is avoided).
     */
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                V3_TABLES.forEach(db::execSQL)
                SchemaConstraints.v3Statements.forEach(db::execSQL)
            }
        }

    /**
     * 3 → 4: statement import (import_profile, import_batch, import_row, txn_fingerprint) and categorization rules
     * (category_rule). Additive only. The deferred references txn.import_batch_id and reconciliation.import_batch_id are
     * enforced by triggers, because adding foreign keys to existing columns would need table rebuilds.
     */
    val MIGRATION_3_4: Migration =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                V4_TABLES.forEach(db::execSQL)
                SchemaConstraints.v4Statements.forEach(db::execSQL)
            }
        }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

    private val V2_TABLES =
        listOf(
            "CREATE TABLE `reconciliation` (`id` TEXT NOT NULL, `account_id` TEXT NOT NULL, `as_of_date` TEXT NOT NULL, " +
                "`statement_balance_minor` INTEGER NOT NULL, `calculated_balance_minor` INTEGER NOT NULL, " +
                "`difference_minor` INTEGER NOT NULL, `adjustment_txn_id` TEXT, `source` TEXT NOT NULL, `import_batch_id` TEXT, " +
                "`created_at` INTEGER NOT NULL, `version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, " +
                "`updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`account_id`) REFERENCES `financial_account`(`ledger_account_id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`adjustment_txn_id`) REFERENCES `txn`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `ix_recon_account` ON `reconciliation` (`account_id`, `as_of_date`)",
            "CREATE UNIQUE INDEX `index_reconciliation_adjustment_txn_id` ON `reconciliation` (`adjustment_txn_id`)",
            "CREATE TABLE `budget` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `currency` TEXT NOT NULL, `period_kind` TEXT NOT NULL, " +
                "`anchor_date` TEXT NOT NULL, `anchor_day` INTEGER, `anchor_day2` INTEGER, `every_n_days` INTEGER, " +
                "`custom_end_date` TEXT, `rule_effective_from` TEXT NOT NULL, `income_mode` TEXT NOT NULL, " +
                "`fixed_income_minor` INTEGER, `account_filter` INTEGER NOT NULL DEFAULT 0, `archived_at` INTEGER, " +
                "`created_at` INTEGER NOT NULL, `version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, " +
                "`updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `budget_category` (`budget_id` TEXT NOT NULL, `category_id` TEXT NOT NULL, `allocation_mode` TEXT NOT NULL, " +
                "`default_amount_minor` INTEGER, `percent_bp` INTEGER, `rollover_policy` TEXT NOT NULL DEFAULT 'EXPIRE', " +
                "`carry_negative` INTEGER NOT NULL DEFAULT 0, `sweep_goal_id` TEXT, `sort_order` INTEGER NOT NULL DEFAULT 0, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, " +
                "`hlc` TEXT NOT NULL, PRIMARY KEY(`budget_id`, `category_id`), " +
                "FOREIGN KEY(`budget_id`) REFERENCES `budget`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`ledger_account_id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`sweep_goal_id`) REFERENCES `savings_goal`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `index_budget_category_category_id` ON `budget_category` (`category_id`)",
            "CREATE INDEX `index_budget_category_sweep_goal_id` ON `budget_category` (`sweep_goal_id`)",
            "CREATE TABLE `budget_account` (`budget_id` TEXT NOT NULL, `ledger_account_id` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, " +
                "`hlc` TEXT NOT NULL, PRIMARY KEY(`budget_id`, `ledger_account_id`), " +
                "FOREIGN KEY(`budget_id`) REFERENCES `budget`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`ledger_account_id`) REFERENCES `financial_account`(`ledger_account_id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `index_budget_account_ledger_account_id` ON `budget_account` (`ledger_account_id`)",
            "CREATE TABLE `budget_period` (`id` TEXT NOT NULL, `budget_id` TEXT NOT NULL, `start_date` TEXT NOT NULL, " +
                "`end_date` TEXT NOT NULL, `closed_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, " +
                "`updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`budget_id`) REFERENCES `budget`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE UNIQUE INDEX `index_budget_period_budget_id_start_date` ON `budget_period` (`budget_id`, `start_date`)",
            "CREATE INDEX `ix_period_lookup` ON `budget_period` (`budget_id`, `end_date`)",
            "CREATE TABLE `savings_goal` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `currency` TEXT NOT NULL, " +
                "`target_minor` INTEGER NOT NULL, `target_date` TEXT, `status` TEXT NOT NULL, `notes` TEXT, `icon` TEXT NOT NULL, " +
                "`color_argb` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `version` INTEGER NOT NULL DEFAULT 1, " +
                "`updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `goal_account` (`goal_id` TEXT NOT NULL, `ledger_account_id` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, " +
                "`hlc` TEXT NOT NULL, PRIMARY KEY(`goal_id`, `ledger_account_id`), " +
                "FOREIGN KEY(`goal_id`) REFERENCES `savings_goal`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`ledger_account_id`) REFERENCES `financial_account`(`ledger_account_id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `index_goal_account_ledger_account_id` ON `goal_account` (`ledger_account_id`)",
            "CREATE TABLE `allocation_group` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `effective_date` TEXT NOT NULL, " +
                "`reason` TEXT, `related_txn_id` TEXT, `snapshot_json` TEXT NOT NULL, `confirmed_at` INTEGER, " +
                "`idempotency_key` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `deleted_at` INTEGER, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, " +
                "`hlc` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`related_txn_id`) REFERENCES `txn`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE UNIQUE INDEX `index_allocation_group_idempotency_key` ON `allocation_group` (`idempotency_key`)",
            "CREATE INDEX `index_allocation_group_related_txn_id` ON `allocation_group` (`related_txn_id`)",
            "CREATE TABLE `allocation_entry` (`id` TEXT NOT NULL, `group_id` TEXT NOT NULL, `bucket` TEXT NOT NULL, " +
                "`budget_period_id` TEXT, `category_id` TEXT, `goal_id` TEXT, `amount_minor` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`group_id`) REFERENCES `allocation_group`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`budget_period_id`) REFERENCES `budget_period`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`ledger_account_id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`goal_id`) REFERENCES `savings_goal`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `ix_alloc_period` ON `allocation_entry` (`budget_period_id`, `bucket`, `category_id`)",
            "CREATE INDEX `ix_alloc_goal` ON `allocation_entry` (`goal_id`)",
            "CREATE INDEX `ix_alloc_group` ON `allocation_entry` (`group_id`)",
            "CREATE INDEX `index_allocation_entry_category_id` ON `allocation_entry` (`category_id`)",
        )

    private val V3_TABLES =
        listOf(
            "CREATE TABLE `debt` (`ledger_account_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `counterparty` TEXT, " +
                "`original_minor` INTEGER NOT NULL, `start_date` TEXT NOT NULL, `interest_rate_bp` INTEGER, " +
                "`interest_basis` TEXT, `payment_frequency` TEXT, `installment_minor` INTEGER, " +
                "`installments_total` INTEGER, `due_day` INTEGER, `status` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, " +
                "`hlc` TEXT NOT NULL, PRIMARY KEY(`ledger_account_id`), " +
                "FOREIGN KEY(`ledger_account_id`) REFERENCES `financial_account`(`ledger_account_id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE TABLE `debt_schedule_item` (`id` TEXT NOT NULL, `debt_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, " +
                "`due_date` TEXT NOT NULL, `amount_due_minor` INTEGER NOT NULL, `paid_txn_id` TEXT, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, " +
                "`hlc` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`debt_id`) REFERENCES `debt`(`ledger_account_id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`paid_txn_id`) REFERENCES `txn`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE UNIQUE INDEX `index_debt_schedule_item_debt_id_seq` ON `debt_schedule_item` (`debt_id`, `seq`)",
            "CREATE INDEX `index_debt_schedule_item_paid_txn_id` ON `debt_schedule_item` (`paid_txn_id`)",
            "CREATE TABLE `asset` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`currency` TEXT NOT NULL, `include_in_net_worth` INTEGER NOT NULL DEFAULT 1, `notes` TEXT, " +
                "`archived_at` INTEGER, `created_at` INTEGER NOT NULL, `version` INTEGER NOT NULL DEFAULT 1, " +
                "`updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `asset_valuation` (`id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, `as_of_date` TEXT NOT NULL, " +
                "`value_minor` INTEGER NOT NULL, `note` TEXT, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, " +
                "`updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`asset_id`) REFERENCES `asset`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `ix_valuation` ON `asset_valuation` (`asset_id`, `as_of_date`)",
            "CREATE TABLE `recurring_rule` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                "`amount_is_estimate` INTEGER NOT NULL DEFAULT 0, `frequency` TEXT NOT NULL, `interval_n` INTEGER, " +
                "`day1` INTEGER, `day2` INTEGER, `month_of_year` INTEGER, `anchor_date` TEXT NOT NULL, `end_date` TEXT, " +
                "`account_id` TEXT, `category_id` TEXT, `template_json` TEXT, `auto_post` INTEGER NOT NULL DEFAULT 0, " +
                "`reminder_days_before` INTEGER, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, " +
                "`hlc` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`account_id`) REFERENCES `financial_account`(`ledger_account_id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`ledger_account_id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `index_recurring_rule_account_id` ON `recurring_rule` (`account_id`)",
            "CREATE INDEX `index_recurring_rule_category_id` ON `recurring_rule` (`category_id`)",
            "CREATE TABLE `occurrence` (`id` TEXT NOT NULL, `rule_id` TEXT NOT NULL, `due_date` TEXT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, `status` TEXT NOT NULL, `paid_txn_id` TEXT, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, " +
                "`hlc` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`rule_id`) REFERENCES `recurring_rule`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`paid_txn_id`) REFERENCES `txn`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE UNIQUE INDEX `index_occurrence_rule_id_due_date` ON `occurrence` (`rule_id`, `due_date`)",
            "CREATE INDEX `ix_occurrence_due` ON `occurrence` (`due_date`, `status`)",
            "CREATE INDEX `index_occurrence_paid_txn_id` ON `occurrence` (`paid_txn_id`)",
        )

    private val V4_TABLES =
        listOf(
            "CREATE TABLE `import_profile` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `institution` TEXT, " +
                "`format` TEXT NOT NULL, `layout_version` INTEGER NOT NULL DEFAULT 1, `builtin` INTEGER NOT NULL, " +
                "`detection_json` TEXT, `mapping_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`version` INTEGER NOT NULL DEFAULT 1, `updated_at` INTEGER NOT NULL, " +
                "`updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `import_batch` (`id` TEXT NOT NULL, `account_id` TEXT NOT NULL, `profile_id` TEXT, " +
                "`file_name` TEXT NOT NULL, `file_sha256` TEXT NOT NULL, `file_size_bytes` INTEGER NOT NULL, " +
                "`format` TEXT NOT NULL, `status` TEXT NOT NULL, `statement_start_date` TEXT, " +
                "`statement_end_date` TEXT, `statement_closing_minor` INTEGER, `rows_total` INTEGER NOT NULL, " +
                "`rows_imported` INTEGER NOT NULL, `rows_duplicate` INTEGER NOT NULL, " +
                "`rows_rejected` INTEGER NOT NULL, `rows_skipped_by_user` INTEGER NOT NULL, " +
                "`committed_at` INTEGER NOT NULL, `rolled_back_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, " +
                "`updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`account_id`) REFERENCES `financial_account`(`ledger_account_id`) ON UPDATE NO ACTION " +
                "ON DELETE NO ACTION , " +
                "FOREIGN KEY(`profile_id`) REFERENCES `import_profile`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `ix_batch_account` ON `import_batch` (`account_id`, `committed_at`)",
            "CREATE INDEX `index_import_batch_profile_id` ON `import_batch` (`profile_id`)",
            "CREATE TABLE `import_row` (`batch_id` TEXT NOT NULL, `row_no` INTEGER NOT NULL, " +
                "`outcome` TEXT NOT NULL, `txn_id` TEXT, `matched_txn_id` TEXT, `fingerprint` TEXT, `error_code` TEXT, " +
                "PRIMARY KEY(`batch_id`, `row_no`), " +
                "FOREIGN KEY(`batch_id`) REFERENCES `import_batch`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`txn_id`) REFERENCES `txn`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                "FOREIGN KEY(`matched_txn_id`) REFERENCES `txn`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `index_import_row_txn_id` ON `import_row` (`txn_id`)",
            "CREATE INDEX `index_import_row_matched_txn_id` ON `import_row` (`matched_txn_id`)",
            "CREATE TABLE `txn_fingerprint` (`account_id` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, " +
                "`txn_id` TEXT NOT NULL, PRIMARY KEY(`account_id`, `fingerprint`), " +
                "FOREIGN KEY(`account_id`) REFERENCES `financial_account`(`ledger_account_id`) ON UPDATE NO ACTION " +
                "ON DELETE NO ACTION , " +
                "FOREIGN KEY(`txn_id`) REFERENCES `txn`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX `ix_fp_txn` ON `txn_fingerprint` (`txn_id`)",
            "CREATE TABLE `category_rule` (`id` TEXT NOT NULL, `priority` INTEGER NOT NULL, " +
                "`match_field` TEXT NOT NULL, `match_op` TEXT NOT NULL, `pattern_norm` TEXT NOT NULL, " +
                "`min_minor` INTEGER, `max_minor` INTEGER, `account_id` TEXT, `category_id` TEXT NOT NULL, " +
                "`mode` TEXT NOT NULL, `enabled` INTEGER NOT NULL DEFAULT 1, `version` INTEGER NOT NULL DEFAULT 1, " +
                "`updated_at` INTEGER NOT NULL, `updated_by_device` TEXT NOT NULL, `hlc` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`account_id`) REFERENCES `financial_account`(`ledger_account_id`) ON UPDATE NO ACTION " +
                "ON DELETE NO ACTION , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`ledger_account_id`) ON UPDATE NO ACTION ON DELETE " +
                "NO ACTION )",
            "CREATE INDEX `ix_rule_priority` ON `category_rule` (`enabled`, `priority`)",
            "CREATE INDEX `index_category_rule_account_id` ON `category_rule` (`account_id`)",
            "CREATE INDEX `index_category_rule_category_id` ON `category_rule` (`category_id`)",
        )
}
