package ph.financewallet.data.account

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ph.financewallet.data.db.entity.AuditEventEntity
import ph.financewallet.data.db.entity.CategoryEntity
import ph.financewallet.data.db.entity.FinancialAccountEntity
import ph.financewallet.data.db.entity.LedgerAccountEntity
import ph.financewallet.data.db.entity.ReconciliationEntity

/** What uses a category (with its subcategories); shown before archiving it. */
data class CategoryUsage(
    val budgets: Int,
    val recurringRules: Int,
    val categoryRules: Int,
    val transactions: Int,
)

/** Category row joined with its ledger account (name, class, archived). */
data class CategoryRow(
    val id: String,
    val name: String,
    val accountClass: String,
    val parentId: String?,
    val icon: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val archivedAt: Long?,
    val isDefaultSeed: Boolean,
)

/** Account row for lists: ledger account + financial account. */
data class AccountRow(
    val id: String,
    val name: String,
    val accountClass: String,
    val currency: String,
    val type: String,
    val institution: String?,
    val accountNumberLast4: String?,
    val includeInNetWorth: Boolean,
    val spendable: Boolean,
    val sortOrder: Int,
    val archivedAt: Long?,
    val version: Long,
)

/**
 * Account, category, reconciliation and audit access for UC-02/03/04, UC-13, UC-14. Called only from use cases.
 * TooManyFunctions is suppressed: one function per SQL statement.
 */
@Suppress("TooManyFunctions")
@Dao
interface AccountDao {
    @Update
    suspend fun updateLedgerAccount(row: LedgerAccountEntity)

    @Update
    suspend fun updateFinancialAccount(row: FinancialAccountEntity)

    @Update
    suspend fun updateCategory(row: CategoryEntity)

    @Query("DELETE FROM financial_account WHERE ledger_account_id = :id")
    suspend fun deleteFinancialAccount(id: String)

    @Query("DELETE FROM ledger_account WHERE id = :id")
    suspend fun deleteLedgerAccount(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAudit(row: AuditEventEntity)

    @Query("SELECT * FROM audit_event WHERE entity_id = :entityId ORDER BY created_at, id")
    suspend fun auditFor(entityId: String): List<AuditEventEntity>

    // ---- accounts -----------------------------------------------------------------------------------------------

    @Query(
        """SELECT a.id, a.name, a.class AS accountClass, a.currency, f.type, f.institution,
                  f.account_number_last4 AS accountNumberLast4, f.include_in_net_worth AS includeInNetWorth, f.spendable,
                  f.sort_order AS sortOrder, a.archived_at AS archivedAt, f.version
           FROM ledger_account a JOIN financial_account f ON f.ledger_account_id = a.id
           WHERE (:includeArchived = 1 OR a.archived_at IS NULL)
           ORDER BY a.archived_at IS NOT NULL, f.sort_order, a.name""",
    )
    suspend fun accounts(includeArchived: Boolean): List<AccountRow>

    /** Entries on [accountId] of any transaction (live or deleted) other than its opening balance. */
    @Query(
        """SELECT COUNT(*) FROM entry e JOIN txn t ON t.id = e.txn_id
           WHERE e.ledger_account_id = :accountId AND t.type <> 'OPENING_BALANCE'""",
    )
    suspend fun nonOpeningEntryCount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM entry WHERE ledger_account_id = :accountId")
    suspend fun entryCount(accountId: String): Int

    /** Links that would dangle if the account were deleted (budgets, goals, reconciliations). */
    @Query(
        """SELECT (SELECT COUNT(*) FROM budget_account WHERE ledger_account_id = :id)
                + (SELECT COUNT(*) FROM goal_account WHERE ledger_account_id = :id)
                + (SELECT COUNT(*) FROM reconciliation WHERE account_id = :id)""",
    )
    suspend fun accountReferences(id: String): Int

    // ---- categories ---------------------------------------------------------------------------------------------

    @Query(
        """SELECT c.ledger_account_id AS id, a.name, a.class AS accountClass, c.parent_id AS parentId, c.icon,
                  c.color_argb AS colorArgb, c.sort_order AS sortOrder, a.archived_at AS archivedAt, c.is_default_seed AS isDefaultSeed
           FROM category c JOIN ledger_account a ON a.id = c.ledger_account_id
           WHERE (:includeArchived = 1 OR a.archived_at IS NULL)
           ORDER BY a.class, c.parent_id IS NOT NULL, c.sort_order, a.name""",
    )
    suspend fun categories(includeArchived: Boolean): List<CategoryRow>

    @Query(
        """SELECT COUNT(*) FROM category c JOIN ledger_account a ON a.id = c.ledger_account_id
           WHERE c.parent_id = :id AND a.archived_at IS NULL""",
    )
    suspend fun liveChildCount(id: String): Int

    /**
     * What uses a category and its subcategories: lines in live budgets, active or paused recurring rules, enabled
     * categorization rules, and live transactions.
     */
    @Query(
        """WITH ids AS (SELECT :id AS id UNION SELECT ledger_account_id FROM category WHERE parent_id = :id)
           SELECT
             (SELECT COUNT(DISTINCT bc.budget_id) FROM budget_category bc JOIN budget b ON b.id = bc.budget_id
                WHERE b.archived_at IS NULL AND bc.category_id IN (SELECT id FROM ids)) AS budgets,
             (SELECT COUNT(*) FROM recurring_rule r
                WHERE r.status <> 'ENDED' AND r.category_id IN (SELECT id FROM ids)) AS recurringRules,
             (SELECT COUNT(*) FROM category_rule cr WHERE cr.enabled = 1 AND cr.category_id IN (SELECT id FROM ids)) AS categoryRules,
             (SELECT COUNT(DISTINCT e.txn_id) FROM entry e JOIN txn t ON t.id = e.txn_id
                WHERE t.deleted_at IS NULL AND e.ledger_account_id IN (SELECT id FROM ids)) AS transactions""",
    )
    suspend fun categoryUsage(id: String): CategoryUsage

    /**
     * The live categories of [accountClass] used most often by live transactions on or after [fromDate] (most uses
     * first, then most recent). The date range uses `ix_txn_date`; entries are reached by `ix_entry_account`.
     */
    @Query(
        """SELECT e.ledger_account_id FROM txn t
           JOIN entry e ON e.txn_id = t.id
           JOIN ledger_account a ON a.id = e.ledger_account_id
           JOIN category c ON c.ledger_account_id = a.id
           WHERE t.txn_date >= :fromDate AND t.deleted_at IS NULL AND a.class = :accountClass AND a.archived_at IS NULL
           GROUP BY e.ledger_account_id
           ORDER BY COUNT(*) DESC, MAX(t.txn_date) DESC, e.ledger_account_id
           LIMIT :limit""",
    )
    suspend fun recentCategories(
        accountClass: String,
        fromDate: String,
        limit: Int,
    ): List<String>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM category WHERE (:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId")
    suspend fun maxSortOrder(parentId: String?): Int

    // ---- reconciliation -----------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReconciliation(row: ReconciliationEntity)

    @Query("SELECT * FROM reconciliation WHERE account_id = :accountId ORDER BY as_of_date DESC, created_at DESC LIMIT 1")
    suspend fun latestReconciliation(accountId: String): ReconciliationEntity?

    @Query("SELECT * FROM reconciliation WHERE account_id = :accountId ORDER BY as_of_date DESC, created_at DESC")
    suspend fun reconciliations(accountId: String): List<ReconciliationEntity>

    // ---- app meta -----------------------------------------------------------------------------------------------

    @Query("SELECT value FROM app_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("INSERT OR REPLACE INTO app_meta(`key`, value) VALUES (:key, :value)")
    suspend fun setMeta(
        key: String,
        value: String,
    )
}
