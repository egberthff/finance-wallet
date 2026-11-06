package ph.financewallet.data.imports

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ph.financewallet.data.db.entity.CategoryRuleEntity
import ph.financewallet.data.db.entity.ImportBatchEntity
import ph.financewallet.data.db.entity.ImportProfileEntity
import ph.financewallet.data.db.entity.ImportRowEntity
import ph.financewallet.data.db.entity.TxnFingerprintEntity

/** An existing transaction that a statement row might duplicate (docs/13-import-pipeline.md §7). */
data class DuplicateCandidate(
    val txnId: String,
    val txnDate: String,
    val descriptionNorm: String,
    val merchantNorm: String?,
    val referenceNo: String?,
    /** Σ entries of the transaction on the account (account perspective: + in, − out). */
    val amountMinor: Long,
)

/**
 * Import batches, rows, fingerprints, saved profiles and categorization rules (schema v4). Called only from the import
 * use cases. TooManyFunctions is suppressed: one function per SQL statement.
 */
@Suppress("TooManyFunctions")
@Dao
interface ImportDao {
    // ---- batches and rows ----------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(row: ImportBatchEntity)

    @Update
    suspend fun updateBatch(row: ImportBatchEntity)

    @Query("SELECT * FROM import_batch WHERE id = :id")
    suspend fun batch(id: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batch WHERE (:accountId IS NULL OR account_id = :accountId) ORDER BY committed_at DESC, id DESC")
    suspend fun batches(accountId: String?): List<ImportBatchEntity>

    @Query(
        """SELECT * FROM import_batch WHERE account_id = :accountId AND file_sha256 = :sha AND status = 'COMMITTED'
           ORDER BY committed_at LIMIT 1""",
    )
    suspend fun committedWithHash(
        accountId: String,
        sha: String,
    ): ImportBatchEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRows(rows: List<ImportRowEntity>)

    @Query("SELECT * FROM import_row WHERE batch_id = :batchId ORDER BY row_no")
    suspend fun rows(batchId: String): List<ImportRowEntity>

    /** Live transactions created by the batch. */
    @Query("SELECT id FROM txn WHERE import_batch_id = :batchId AND deleted_at IS NULL ORDER BY id")
    suspend fun liveTxnsOf(batchId: String): List<String>

    /** Live transactions of the batch that were edited after import (more than the CREATE revision). */
    @Query(
        """SELECT t.id FROM txn t WHERE t.import_batch_id = :batchId AND t.deleted_at IS NULL
           AND (SELECT MAX(r.revision_no) FROM txn_revision r WHERE r.txn_id = t.id) > 1 ORDER BY t.id""",
    )
    suspend fun editedAfterImport(batchId: String): List<String>

    // ---- fingerprints --------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFingerprints(rows: List<TxnFingerprintEntity>)

    @Query("SELECT * FROM txn_fingerprint WHERE account_id = :accountId AND fingerprint IN (:fingerprints)")
    suspend fun fingerprints(
        accountId: String,
        fingerprints: List<String>,
    ): List<TxnFingerprintEntity>

    @Query("DELETE FROM txn_fingerprint WHERE account_id = :accountId AND fingerprint IN (:fingerprints)")
    suspend fun deleteFingerprints(
        accountId: String,
        fingerprints: List<String>,
    )

    // ---- duplicate candidates and history ------------------------------------------------------------------------

    /**
     * Live transactions on [accountId] dated in [from]..[to] that are not linked to any fingerprint yet, with their amount
     * on the account. Bounded by the statement's date window (§7), using ix_entry_account.
     */
    @Query(
        """SELECT t.id AS txnId, t.txn_date AS txnDate, t.description_norm AS descriptionNorm, t.merchant_norm AS merchantNorm,
                  t.reference_no AS referenceNo, SUM(e.amount_minor) AS amountMinor
           FROM entry e JOIN txn t ON t.id = e.txn_id
           WHERE e.ledger_account_id = :accountId AND t.deleted_at IS NULL AND t.txn_date BETWEEN :from AND :to
             AND NOT EXISTS (SELECT 1 FROM txn_fingerprint f WHERE f.txn_id = t.id)
           GROUP BY t.id""",
    )
    suspend fun candidates(
        accountId: String,
        from: String,
        to: String,
    ): List<DuplicateCandidate>

    /**
     * Most used category of [accountClass] for transactions on [accountId] since [since] whose description matches
     * [descriptionNorm] (merchant history, §8). Null when there is none.
     */
    @Query(
        """SELECT c.ledger_account_id FROM txn t
           JOIN entry ea ON ea.txn_id = t.id AND ea.ledger_account_id = :accountId
           JOIN entry ec ON ec.txn_id = t.id
           JOIN category c ON c.ledger_account_id = ec.ledger_account_id
           JOIN ledger_account a ON a.id = c.ledger_account_id AND a.class = :accountClass AND a.archived_at IS NULL
           WHERE t.deleted_at IS NULL AND t.txn_date >= :since AND (t.description_norm = :descriptionNorm OR t.merchant_norm = :descriptionNorm)
           GROUP BY c.ledger_account_id ORDER BY COUNT(*) DESC, MAX(t.txn_date) DESC LIMIT 1""",
    )
    suspend fun historyCategory(
        accountId: String,
        descriptionNorm: String,
        accountClass: String,
        since: String,
    ): String?

    // ---- profiles ------------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(row: ImportProfileEntity)

    @Update
    suspend fun updateProfile(row: ImportProfileEntity)

    @Query("SELECT * FROM import_profile ORDER BY builtin DESC, name")
    suspend fun profiles(): List<ImportProfileEntity>

    @Query("SELECT * FROM import_profile WHERE id = :id")
    suspend fun profile(id: String): ImportProfileEntity?

    // ---- categorization rules ------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(row: CategoryRuleEntity)

    @Update
    suspend fun updateRule(row: CategoryRuleEntity)

    @Query("DELETE FROM category_rule WHERE id = :id")
    suspend fun deleteRule(id: String)

    @Query("SELECT * FROM category_rule WHERE id = :id")
    suspend fun rule(id: String): CategoryRuleEntity?

    @Query("SELECT * FROM category_rule ORDER BY priority, id")
    suspend fun rules(): List<CategoryRuleEntity>

    @Query("SELECT COALESCE(MAX(priority), 0) FROM category_rule")
    suspend fun maxPriority(): Int
}
