package ph.financewallet.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v4 tables (docs/11-database-schema.md §3.7–3.8): statement import and categorization rules.
 * Previews are never persisted; only committed batches are (docs/13-import-pipeline.md §1).
 */

@Entity(tableName = "import_profile")
data class ImportProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val institution: String?,
    val format: String,
    @ColumnInfo(name = "layout_version", defaultValue = "1") val layoutVersion: Int,
    val builtin: Boolean,
    @ColumnInfo(name = "detection_json") val detectionJson: String?,
    @ColumnInfo(name = "mapping_json") val mappingJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "import_batch",
    foreignKeys = [
        ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["account_id"]),
        ForeignKey(entity = ImportProfileEntity::class, parentColumns = ["id"], childColumns = ["profile_id"]),
    ],
    indices = [Index(value = ["account_id", "committed_at"], name = "ix_batch_account"), Index(value = ["profile_id"])],
)
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "profile_id") val profileId: String?,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_sha256") val fileSha256: String,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    val format: String,
    val status: String,
    @ColumnInfo(name = "statement_start_date") val statementStartDate: String?,
    @ColumnInfo(name = "statement_end_date") val statementEndDate: String?,
    @ColumnInfo(name = "statement_closing_minor") val statementClosingMinor: Long?,
    @ColumnInfo(name = "rows_total") val rowsTotal: Int,
    @ColumnInfo(name = "rows_imported") val rowsImported: Int,
    @ColumnInfo(name = "rows_duplicate") val rowsDuplicate: Int,
    @ColumnInfo(name = "rows_rejected") val rowsRejected: Int,
    @ColumnInfo(name = "rows_skipped_by_user") val rowsSkippedByUser: Int,
    @ColumnInfo(name = "committed_at") val committedAt: Long,
    @ColumnInfo(name = "rolled_back_at") val rolledBackAt: Long?,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)

@Entity(
    tableName = "import_row",
    primaryKeys = ["batch_id", "row_no"],
    foreignKeys = [
        ForeignKey(entity = ImportBatchEntity::class, parentColumns = ["id"], childColumns = ["batch_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txn_id"]),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["matched_txn_id"]),
    ],
    indices = [Index(value = ["txn_id"]), Index(value = ["matched_txn_id"])],
)
data class ImportRowEntity(
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "row_no") val rowNo: Int,
    val outcome: String,
    @ColumnInfo(name = "txn_id") val txnId: String?,
    @ColumnInfo(name = "matched_txn_id") val matchedTxnId: String?,
    val fingerprint: String?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
)

@Entity(
    tableName = "txn_fingerprint",
    primaryKeys = ["account_id", "fingerprint"],
    foreignKeys = [
        ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["account_id"]),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txn_id"]),
    ],
    indices = [Index(value = ["txn_id"], name = "ix_fp_txn")],
)
data class TxnFingerprintEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    val fingerprint: String,
    @ColumnInfo(name = "txn_id") val txnId: String,
)

@Entity(
    tableName = "category_rule",
    foreignKeys = [
        ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["account_id"]),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["ledger_account_id"], childColumns = ["category_id"]),
    ],
    indices = [
        Index(
            value = ["enabled", "priority"],
            name = "ix_rule_priority",
        ), Index(value = ["account_id"]), Index(value = ["category_id"]),
    ],
)
data class CategoryRuleEntity(
    @PrimaryKey val id: String,
    val priority: Int,
    @ColumnInfo(name = "match_field") val matchField: String,
    @ColumnInfo(name = "match_op") val matchOp: String,
    @ColumnInfo(name = "pattern_norm") val patternNorm: String,
    @ColumnInfo(name = "min_minor") val minMinor: Long?,
    @ColumnInfo(name = "max_minor") val maxMinor: Long?,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val mode: String,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean,
    @ColumnInfo(defaultValue = "1") val version: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by_device") val updatedByDevice: String,
    val hlc: String,
)
