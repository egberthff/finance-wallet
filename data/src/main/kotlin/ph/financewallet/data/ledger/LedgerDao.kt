package ph.financewallet.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ph.financewallet.data.db.entity.CategoryEntity
import ph.financewallet.data.db.entity.EntryEntity
import ph.financewallet.data.db.entity.FinancialAccountEntity
import ph.financewallet.data.db.entity.LedgerAccountEntity
import ph.financewallet.data.db.entity.TxnEntity
import ph.financewallet.data.db.entity.TxnRevisionEntity

/** One transaction header for list screens. */
data class TxnListRow(
    val id: String,
    val type: String,
    val status: String,
    val txnDate: String,
    val description: String,
    val merchant: String?,
    val version: Long,
)

/** Aggregate row for report classification (docs/10-domain-model.md §3.4). */
data class BucketTotal(
    val currency: String,
    val bucket: String,
    val total: Long,
)

/**
 * Ledger-table access. Only use cases call this, inside a database transaction (docs/12-use-cases.md).
 * TooManyFunctions is suppressed: a Room DAO has one function per SQL statement, and splitting by count would be arbitrary.
 */
@Suppress("TooManyFunctions")
@Dao
interface LedgerDao {
    // ---- accounts -------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLedgerAccount(row: LedgerAccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFinancialAccount(row: FinancialAccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(row: CategoryEntity)

    @Query("SELECT * FROM ledger_account")
    suspend fun allLedgerAccounts(): List<LedgerAccountEntity>

    @Query("SELECT * FROM financial_account")
    suspend fun allFinancialAccounts(): List<FinancialAccountEntity>

    @Query("SELECT * FROM ledger_account WHERE system_key = :key")
    suspend fun bySystemKey(key: String): LedgerAccountEntity?

    @Query("SELECT * FROM ledger_account WHERE id = :id")
    suspend fun ledgerAccount(id: String): LedgerAccountEntity?

    @Query("SELECT * FROM category WHERE ledger_account_id = :id")
    suspend fun category(id: String): CategoryEntity?

    @Query("SELECT * FROM financial_account WHERE ledger_account_id = :id")
    suspend fun financialAccount(id: String): FinancialAccountEntity?

    // ---- transactions ---------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTxn(row: TxnEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(rows: List<EntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(row: TxnRevisionEntity)

    @Update
    suspend fun updateTxn(row: TxnEntity)

    @Query("DELETE FROM entry WHERE txn_id = :txnId")
    suspend fun deleteEntries(txnId: String)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun txn(id: String): TxnEntity?

    @Query("SELECT * FROM txn WHERE idempotency_key = :key")
    suspend fun txnByIdempotencyKey(key: String): TxnEntity?

    @Query("SELECT * FROM entry WHERE txn_id = :txnId ORDER BY line_no")
    suspend fun entries(txnId: String): List<EntryEntity>

    @Query("SELECT COALESCE(MAX(revision_no), 0) FROM txn_revision WHERE txn_id = :txnId")
    suspend fun lastRevisionNo(txnId: String): Int

    @Query("SELECT * FROM txn_revision WHERE txn_id = :txnId ORDER BY revision_no")
    suspend fun revisions(txnId: String): List<TxnRevisionEntity>

    /** Live reversal of [txnId], if any (an original can be reversed at most once). */
    @Query("SELECT * FROM txn WHERE reverses_txn_id = :txnId AND deleted_at IS NULL")
    suspend fun liveReversalOf(txnId: String): TxnEntity?

    /** Total already refunded against [txnId] by live refunds: refunds credit expense entries, so negate. */
    @Query(
        """SELECT COALESCE(-SUM(e.amount_minor), 0) FROM txn r
           JOIN entry e ON e.txn_id = r.id
           JOIN ledger_account a ON a.id = e.ledger_account_id AND a.class = 'EXPENSE'
           WHERE r.refund_of_txn_id = :txnId AND r.deleted_at IS NULL AND r.id <> :excludingId""",
    )
    suspend fun refundedAgainst(
        txnId: String,
        excludingId: String,
    ): Long

    @Query("SELECT COUNT(*) FROM txn WHERE refund_of_txn_id = :txnId AND deleted_at IS NULL")
    suspend fun liveRefundCount(txnId: String): Int

    // ---- lists (keyset pagination on ix_txn_date, NFR-03) -------------------------------------------------

    /**
     * Live transactions strictly before the cursor (date, id), newest first. Optional filters: touching [accountId];
     * touching [categoryId] or one of its subcategories (report drill-down, RPT-03); dated within [from]..[to];
     * [postedOnly] excludes pending ones (report figures count only posted transactions).
     */
    @Query(
        """SELECT t.id, t.type, t.status, t.txn_date AS txnDate, t.description, t.merchant, t.version FROM txn t
           WHERE t.deleted_at IS NULL
             AND (:beforeDate IS NULL OR t.txn_date < :beforeDate OR (t.txn_date = :beforeDate AND t.id < :beforeId))
             AND (:accountId IS NULL OR EXISTS (SELECT 1 FROM entry e WHERE e.txn_id = t.id AND e.ledger_account_id = :accountId))
             AND (:categoryId IS NULL OR EXISTS (SELECT 1 FROM entry e LEFT JOIN category c ON c.ledger_account_id = e.ledger_account_id
                  WHERE e.txn_id = t.id AND (e.ledger_account_id = :categoryId OR c.parent_id = :categoryId)))
             AND (:from IS NULL OR t.txn_date >= :from) AND (:to IS NULL OR t.txn_date <= :to)
             AND (:postedOnly = 0 OR t.status = 'POSTED')
           ORDER BY t.txn_date DESC, t.id DESC LIMIT :limit""",
    )
    suspend fun txnPage(
        beforeDate: String?,
        beforeId: String?,
        accountId: String?,
        limit: Int,
        categoryId: String? = null,
        from: String? = null,
        to: String? = null,
        postedOnly: Boolean = false,
    ): List<TxnListRow>

    @Query("SELECT * FROM entry WHERE txn_id IN (:txnIds) ORDER BY txn_id, line_no")
    suspend fun entriesOf(txnIds: List<String>): List<EntryEntity>

    @Query("SELECT * FROM ledger_account WHERE id IN (:ids)")
    suspend fun ledgerAccounts(ids: List<String>): List<LedgerAccountEntity>

    // ---- balances & reports (same semantics as domain LedgerCalculator) ----------------------------------

    /** I-5: posted, not deleted, dated on or before [asOf]. */
    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM entry e JOIN txn t ON t.id = e.txn_id
           WHERE e.ledger_account_id = :accountId AND t.status = 'POSTED' AND t.deleted_at IS NULL AND t.txn_date <= :asOf""",
    )
    suspend fun balance(
        accountId: String,
        asOf: String,
    ): Long

    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM entry e JOIN txn t ON t.id = e.txn_id
           WHERE e.ledger_account_id = :accountId AND t.status = 'PENDING' AND t.deleted_at IS NULL""",
    )
    suspend fun pendingBalance(accountId: String): Long

    @Query(
        """SELECT e.currency AS currency,
                  CASE WHEN a.class = 'INCOME' THEN 'INCOME'
                       WHEN a.class = 'EXPENSE' THEN 'SPENDING'
                       WHEN a.system_key = 'equity:adjustment' THEN 'ADJUSTMENT'
                       WHEN a.system_key = 'equity:opening' THEN 'OPENING'
                       WHEN a.system_key = 'equity:fx' THEN 'FX' END AS bucket,
                  SUM(e.amount_minor) AS total
           FROM txn t JOIN entry e ON e.txn_id = t.id JOIN ledger_account a ON a.id = e.ledger_account_id
           WHERE t.status = 'POSTED' AND t.deleted_at IS NULL AND t.txn_date BETWEEN :from AND :to
             AND a.class IN ('INCOME', 'EXPENSE', 'EQUITY')
           GROUP BY e.currency, bucket""",
    )
    suspend fun bucketTotals(
        from: String,
        to: String,
    ): List<BucketTotal>
}
