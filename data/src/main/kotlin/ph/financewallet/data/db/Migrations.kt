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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

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
}
