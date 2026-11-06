package ph.financewallet.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v3 tables (docs/11-database-schema.md §3.5–3.6): debts and schedules, user-entered assets and valuations,
 * recurring rules and their occurrences. CHECK constraints are triggers (SchemaConstraints.v3Statements).
 */

@Entity(
    tableName = "debt",
    foreignKeys = [
        ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["ledger_account_id"]),
    ],
)
data class DebtEntity(
    @PrimaryKey @ColumnInfo(name = "ledger_account_id") val ledgerAccountId: String,
    val kind: String,
    val counterparty: String?,
    @ColumnInfo(name = "original_minor") val originalMinor: Long,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "interest_rate_bp") val interestRateBp: Int?,
    @ColumnInfo(name = "interest_basis") val interestBasis: String?,
    @ColumnInfo(name = "payment_frequency") val paymentFrequency: String?,
    @ColumnInfo(name = "installment_minor") val installmentMinor: Long?,
    @ColumnInfo(name = "installments_total") val installmentsTotal: Int?,
    @ColumnInfo(name = "due_day") val dueDay: Int?,
    val status: String,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "debt_schedule_item",
    foreignKeys = [
        ForeignKey(entity = DebtEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["debt_id"]),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["paid_txn_id"]),
    ],
    indices = [Index(value = ["debt_id", "seq"], unique = true), Index(value = ["paid_txn_id"])],
)
data class DebtScheduleItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "debt_id") val debtId: String,
    val seq: Int,
    @ColumnInfo(name = "due_date") val dueDate: String,
    @ColumnInfo(name = "amount_due_minor") val amountDueMinor: Long,
    @ColumnInfo(name = "paid_txn_id") val paidTxnId: String?,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(tableName = "asset")
data class AssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val currency: String,
    @ColumnInfo(name = "include_in_net_worth", defaultValue = "1") val includeInNetWorth: Boolean,
    val notes: String?,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "asset_valuation",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["asset_id"])],
    indices = [Index(value = ["asset_id", "as_of_date"], name = "ix_valuation")],
)
data class AssetValuationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "as_of_date") val asOfDate: String,
    @ColumnInfo(name = "value_minor") val valueMinor: Long,
    val note: String?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "recurring_rule",
    foreignKeys = [
        ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["account_id"]),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["category_id"]),
    ],
    indices = [Index(value = ["account_id"]), Index(value = ["category_id"])],
)
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val name: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "amount_is_estimate", defaultValue = "0") val amountIsEstimate: Boolean,
    val frequency: String,
    @ColumnInfo(name = "interval_n") val intervalN: Int?,
    val day1: Int?,
    val day2: Int?,
    @ColumnInfo(name = "month_of_year") val monthOfYear: Int?,
    @ColumnInfo(name = "anchor_date") val anchorDate: String,
    @ColumnInfo(name = "end_date") val endDate: String?,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "template_json") val templateJson: String?,
    @ColumnInfo(name = "auto_post", defaultValue = "0") val autoPost: Boolean,
    @ColumnInfo(name = "reminder_days_before") val reminderDaysBefore: Int?,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "occurrence",
    foreignKeys = [
        ForeignKey(entity = RecurringRuleEntity::class, parentColumns = ["id"], childColumns = ["rule_id"]),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["paid_txn_id"]),
    ],
    indices = [
        Index(value = ["rule_id", "due_date"], unique = true),
        Index(value = ["due_date", "status"], name = "ix_occurrence_due"),
        Index(value = ["paid_txn_id"]),
    ],
)
data class OccurrenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "due_date") val dueDate: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val status: String,
    @ColumnInfo(name = "paid_txn_id") val paidTxnId: String?,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)
