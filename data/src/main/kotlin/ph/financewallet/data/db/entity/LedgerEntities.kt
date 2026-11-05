package ph.financewallet.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v1 core ledger tables (docs/11-database-schema.md §3.1–3.2, §3.8).
 * Column names and types mirror the reference DDL. CHECK constraints that Room cannot declare are
 * enforced by triggers in [ph.financewallet.data.db.SchemaConstraints].
 *
 * Enum-like columns are stored as TEXT; mapping to domain enums happens in repositories (Phase 4).
 * Every user-data row carries the sync-ready columns version/updated_at/updated_by_device/hlc (ADR-0007).
 */

@Entity(tableName = "ledger_account", indices = [Index(value = ["system_key"], unique = true)])
data class LedgerAccountEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "class") val accountClass: String,
    val name: String,
    val currency: String?,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "financial_account",
    foreignKeys = [ForeignKey(entity = LedgerAccountEntity::class, parentColumns = ["id"], childColumns = ["ledger_account_id"])],
)
data class FinancialAccountEntity(
    @PrimaryKey @ColumnInfo(name = "ledger_account_id") val ledgerAccountId: String,
    val type: String,
    val institution: String?,
    @ColumnInfo(name = "account_number_last4") val accountNumberLast4: String?,
    @ColumnInfo(name = "opening_date") val openingDate: String,
    @ColumnInfo(name = "include_in_net_worth", defaultValue = "1") val includeInNetWorth: Boolean,
    @ColumnInfo(defaultValue = "1") val spendable: Boolean,
    @ColumnInfo(name = "credit_limit_minor") val creditLimitMinor: Long?,
    // FK to import_profile is added with that table's migration (Phase 6).
    @ColumnInfo(name = "default_import_profile_id") val defaultImportProfileId: String?,
    @ColumnInfo(name = "last_imported_at") val lastImportedAt: Long?,
    @ColumnInfo(name = "reconcile_reminder_days") val reconcileReminderDays: Int?,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "category",
    foreignKeys = [
        ForeignKey(entity = LedgerAccountEntity::class, parentColumns = ["id"], childColumns = ["ledger_account_id"]),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["parent_id"]),
    ],
    indices = [Index(value = ["parent_id", "sort_order"], name = "ix_category_parent")],
)
data class CategoryEntity(
    @PrimaryKey @ColumnInfo(name = "ledger_account_id") val ledgerAccountId: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val icon: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int,
    @ColumnInfo(name = "is_default_seed", defaultValue = "0") val isDefaultSeed: Boolean,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "txn",
    foreignKeys = [
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["reverses_txn_id"]),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["refund_of_txn_id"]),
    ],
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["reverses_txn_id"], unique = true),
        Index(value = ["txn_date", "id"], name = "ix_txn_date"),
        Index(value = ["import_batch_id"], name = "ix_txn_batch"),
        Index(value = ["refund_of_txn_id"], name = "ix_txn_refund_of"),
        Index(value = ["recurring_occurrence_id"], unique = true),
    ],
)
data class TxnEntity(
    @PrimaryKey val id: String,
    val type: String,
    val status: String,
    @ColumnInfo(name = "txn_date") val txnDate: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long?,
    @ColumnInfo(name = "posting_date") val postingDate: String?,
    @ColumnInfo(defaultValue = "''") val description: String,
    @ColumnInfo(name = "description_norm", defaultValue = "''") val descriptionNorm: String,
    val merchant: String?,
    @ColumnInfo(name = "merchant_norm") val merchantNorm: String?,
    val notes: String?,
    @ColumnInfo(name = "reference_no") val referenceNo: String?,
    val source: String,
    // FKs to import_batch / occurrence are added with those tables' migrations (Phases 5–6).
    @ColumnInfo(name = "import_batch_id") val importBatchId: String?,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "reverses_txn_id") val reversesTxnId: String?,
    @ColumnInfo(name = "refund_of_txn_id") val refundOfTxnId: String?,
    @ColumnInfo(name = "recurring_occurrence_id") val recurringOccurrenceId: String?,
    @ColumnInfo(name = "fx_rate_display") val fxRateDisplay: String?,
    @ColumnInfo(name = "conflict_state", defaultValue = "'NONE'") val conflictState: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "entry",
    foreignKeys = [
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txn_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LedgerAccountEntity::class, parentColumns = ["id"], childColumns = ["ledger_account_id"]),
    ],
    indices = [
        Index(value = ["txn_id", "line_no"], unique = true),
        Index(value = ["ledger_account_id", "txn_id"], name = "ix_entry_account"),
    ],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "txn_id") val txnId: String,
    @ColumnInfo(name = "line_no") val lineNo: Int,
    @ColumnInfo(name = "ledger_account_id") val ledgerAccountId: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    val memo: String?,
)

@Entity(
    tableName = "txn_revision",
    foreignKeys = [ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txn_id"])],
    indices = [Index(value = ["txn_id", "revision_no"], unique = true)],
)
data class TxnRevisionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "txn_id") val txnId: String,
    @ColumnInfo(name = "revision_no") val revisionNo: Int,
    @ColumnInfo(name = "change_kind") val changeKind: String,
    @ColumnInfo(name = "before_json") val beforeJson: String?,
    @ColumnInfo(name = "after_json") val afterJson: String?,
    val reason: String?,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "audit_event", indices = [Index(value = ["created_at"], name = "ix_audit_time")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "entity_type") val entityType: String?,
    @ColumnInfo(name = "entity_id") val entityId: String?,
    @ColumnInfo(name = "detail_json") val detailJson: String?,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
