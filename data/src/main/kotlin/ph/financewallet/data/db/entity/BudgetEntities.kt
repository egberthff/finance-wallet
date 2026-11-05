package ph.financewallet.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v2 tables (docs/11-database-schema.md §3.3–3.4): reconciliation, budgets, budget periods, savings goals and
 * the allocation ledger. CHECK constraints are triggers ([ph.financewallet.data.db.SchemaConstraints.v2Statements]).
 */

@Entity(
    tableName = "reconciliation",
    foreignKeys = [
        ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["account_id"]),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["adjustment_txn_id"]),
    ],
    indices = [
        Index(value = ["account_id", "as_of_date"], name = "ix_recon_account"),
        Index(value = ["adjustment_txn_id"], unique = true),
    ],
)
data class ReconciliationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "as_of_date") val asOfDate: String,
    @ColumnInfo(name = "statement_balance_minor") val statementBalanceMinor: Long,
    @ColumnInfo(name = "calculated_balance_minor") val calculatedBalanceMinor: Long,
    @ColumnInfo(name = "difference_minor") val differenceMinor: Long,
    @ColumnInfo(name = "adjustment_txn_id") val adjustmentTxnId: String?,
    val source: String,
    // FK to import_batch is added with that table's migration (Phase 6).
    @ColumnInfo(name = "import_batch_id") val importBatchId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(tableName = "budget")
data class BudgetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    @ColumnInfo(name = "period_kind") val periodKind: String,
    @ColumnInfo(name = "anchor_date") val anchorDate: String,
    @ColumnInfo(name = "anchor_day") val anchorDay: Int?,
    @ColumnInfo(name = "anchor_day2") val anchorDay2: Int?,
    @ColumnInfo(name = "every_n_days") val everyNDays: Int?,
    @ColumnInfo(name = "custom_end_date") val customEndDate: String?,
    @ColumnInfo(name = "rule_effective_from") val ruleEffectiveFrom: String,
    @ColumnInfo(name = "income_mode") val incomeMode: String,
    @ColumnInfo(name = "fixed_income_minor") val fixedIncomeMinor: Long?,
    @ColumnInfo(name = "account_filter", defaultValue = "0") val accountFilter: Boolean,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "budget_category",
    primaryKeys = ["budget_id", "category_id"],
    foreignKeys = [
        ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budget_id"]),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["category_id"]),
        ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["sweep_goal_id"]),
    ],
    indices = [Index(value = ["category_id"]), Index(value = ["sweep_goal_id"])],
)
data class BudgetCategoryEntity(
    @ColumnInfo(name = "budget_id") val budgetId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "allocation_mode") val allocationMode: String,
    @ColumnInfo(name = "default_amount_minor") val defaultAmountMinor: Long?,
    @ColumnInfo(name = "percent_bp") val percentBp: Int?,
    @ColumnInfo(name = "rollover_policy", defaultValue = "'EXPIRE'") val rolloverPolicy: String,
    @ColumnInfo(name = "carry_negative", defaultValue = "0") val carryNegative: Boolean,
    @ColumnInfo(name = "sweep_goal_id") val sweepGoalId: String?,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "budget_account",
    primaryKeys = ["budget_id", "ledger_account_id"],
    foreignKeys = [
        ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budget_id"]),
        ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["ledger_account_id"]),
    ],
    indices = [Index(value = ["ledger_account_id"])],
)
data class BudgetAccountEntity(
    @ColumnInfo(name = "budget_id") val budgetId: String,
    @ColumnInfo(name = "ledger_account_id") val ledgerAccountId: String,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "budget_period",
    foreignKeys = [ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budget_id"])],
    indices = [
        Index(value = ["budget_id", "start_date"], unique = true),
        Index(value = ["budget_id", "end_date"], name = "ix_period_lookup"),
    ],
)
data class BudgetPeriodEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "budget_id") val budgetId: String,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String,
    @ColumnInfo(name = "closed_at") val closedAt: Long?,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(tableName = "savings_goal")
data class SavingsGoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    @ColumnInfo(name = "target_minor") val targetMinor: Long,
    @ColumnInfo(name = "target_date") val targetDate: String?,
    val status: String,
    val notes: String?,
    val icon: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "goal_account",
    primaryKeys = ["goal_id", "ledger_account_id"],
    foreignKeys = [
        ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["goal_id"]),
        ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["ledger_account_id"]),
    ],
    indices = [Index(value = ["ledger_account_id"])],
)
data class GoalAccountEntity(
    @ColumnInfo(name = "goal_id") val goalId: String,
    @ColumnInfo(name = "ledger_account_id") val ledgerAccountId: String,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "allocation_group",
    foreignKeys = [ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["related_txn_id"])],
    indices = [Index(value = ["idempotency_key"], unique = true), Index(value = ["related_txn_id"])],
)
data class AllocationGroupEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "effective_date") val effectiveDate: String,
    val reason: String?,
    @ColumnInfo(name = "related_txn_id") val relatedTxnId: String?,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
    @ColumnInfo(name = "confirmed_at") val confirmedAt: Long?,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "allocation_entry",
    foreignKeys = [
        ForeignKey(
            entity = AllocationGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = BudgetPeriodEntity::class, parentColumns = ["id"], childColumns = ["budget_period_id"]),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["category_id"]),
        ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["goal_id"]),
    ],
    indices = [
        Index(value = ["budget_period_id", "bucket", "category_id"], name = "ix_alloc_period"),
        Index(value = ["goal_id"], name = "ix_alloc_goal"),
        Index(value = ["group_id"], name = "ix_alloc_group"),
        Index(value = ["category_id"]),
    ],
)
data class AllocationEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    val bucket: String,
    @ColumnInfo(name = "budget_period_id") val budgetPeriodId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "goal_id") val goalId: String?,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
)
