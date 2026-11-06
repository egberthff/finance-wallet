package ph.financewallet.data.advanced

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ph.financewallet.data.db.entity.AssetEntity
import ph.financewallet.data.db.entity.AssetValuationEntity
import ph.financewallet.data.db.entity.DebtEntity
import ph.financewallet.data.db.entity.DebtScheduleItemEntity
import ph.financewallet.data.db.entity.OccurrenceEntity
import ph.financewallet.data.db.entity.RecurringRuleEntity

/** Aggregated amount per ledger account (reports). */
data class AccountTotal(
    val accountId: String,
    val currency: String,
    val total: Long,
)

/** One entry of an effective transaction, for range-bounded report computations. */
data class EntryRow(
    val txnId: String,
    val type: String,
    val txnDate: String,
    val accountId: String,
    val amountMinor: Long,
    val currency: String,
)

/**
 * Debts, assets, recurring rules and report aggregates (schema v3, docs/11-database-schema.md §3.5–3.6, §4).
 * TooManyFunctions is suppressed: one function per SQL statement.
 */
@Suppress("TooManyFunctions")
@Dao
interface AdvancedDao {
    // ---- debts ---------------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDebt(row: DebtEntity)

    @Update
    suspend fun updateDebt(row: DebtEntity)

    @Query("SELECT * FROM debt WHERE ledger_account_id = :id")
    suspend fun debt(id: String): DebtEntity?

    @Query("SELECT * FROM debt ORDER BY status, start_date")
    suspend fun debts(): List<DebtEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScheduleItems(rows: List<DebtScheduleItemEntity>)

    @Update
    suspend fun updateScheduleItem(row: DebtScheduleItemEntity)

    @Query("SELECT * FROM debt_schedule_item WHERE debt_id = :debtId ORDER BY seq")
    suspend fun schedule(debtId: String): List<DebtScheduleItemEntity>

    /** Schedule items whose payment is missing or was deleted, i.e. still to pay. */
    @Query(
        """SELECT s.* FROM debt_schedule_item s LEFT JOIN txn t ON t.id = s.paid_txn_id
           WHERE s.debt_id = :debtId AND (s.paid_txn_id IS NULL OR t.deleted_at IS NOT NULL) ORDER BY s.seq""",
    )
    suspend fun unpaidSchedule(debtId: String): List<DebtScheduleItemEntity>

    /** Σ of the liability-side entries of live DEBT_PAYMENT transactions, i.e. principal repaid. */
    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM entry e JOIN txn t ON t.id = e.txn_id
           WHERE e.ledger_account_id = :debtId AND t.type = 'DEBT_PAYMENT' AND t.deleted_at IS NULL AND t.status = 'POSTED'""",
    )
    suspend fun principalRepaid(debtId: String): Long

    /** Interest and fees (expense side) of live payments on the debt. */
    @Query(
        """SELECT COALESCE(SUM(e.amount_minor), 0) FROM entry e JOIN txn t ON t.id = e.txn_id
           JOIN ledger_account a ON a.id = e.ledger_account_id AND a.class = 'EXPENSE'
           WHERE t.type = 'DEBT_PAYMENT' AND t.deleted_at IS NULL AND t.status = 'POSTED'
             AND EXISTS (SELECT 1 FROM entry l WHERE l.txn_id = t.id AND l.ledger_account_id = :debtId)""",
    )
    suspend fun interestAndFeesPaid(debtId: String): Long

    // ---- assets --------------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAsset(row: AssetEntity)

    @Update
    suspend fun updateAsset(row: AssetEntity)

    @Query("SELECT * FROM asset WHERE id = :id")
    suspend fun asset(id: String): AssetEntity?

    @Query("SELECT * FROM asset ORDER BY archived_at IS NOT NULL, name")
    suspend fun assets(): List<AssetEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertValuation(row: AssetValuationEntity)

    @Update
    suspend fun updateValuation(row: AssetValuationEntity)

    @Query("SELECT * FROM asset_valuation WHERE id = :id")
    suspend fun valuation(id: String): AssetValuationEntity?

    @Query("SELECT * FROM asset_valuation WHERE asset_id = :assetId AND deleted_at IS NULL ORDER BY as_of_date DESC, updated_at DESC")
    suspend fun valuations(assetId: String): List<AssetValuationEntity>

    /** Live valuations of assets counted in net worth, on or before [asOf]. */
    @Query(
        """SELECT v.* FROM asset_valuation v JOIN asset a ON a.id = v.asset_id
           WHERE v.deleted_at IS NULL AND a.include_in_net_worth = 1 AND v.as_of_date <= :asOf""",
    )
    suspend fun netWorthValuations(asOf: String): List<AssetValuationEntity>

    // ---- recurring -----------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(row: RecurringRuleEntity)

    @Update
    suspend fun updateRule(row: RecurringRuleEntity)

    @Query("SELECT * FROM recurring_rule WHERE id = :id")
    suspend fun rule(id: String): RecurringRuleEntity?

    @Query("SELECT * FROM recurring_rule ORDER BY status, name")
    suspend fun rules(): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrences(rows: List<OccurrenceEntity>): List<Long>

    @Update
    suspend fun updateOccurrence(row: OccurrenceEntity)

    @Query("SELECT * FROM occurrence WHERE id = :id")
    suspend fun occurrence(id: String): OccurrenceEntity?

    @Query("SELECT * FROM occurrence WHERE paid_txn_id = :txnId")
    suspend fun occurrenceForTxn(txnId: String): OccurrenceEntity?

    @Query("SELECT * FROM occurrence WHERE rule_id = :ruleId ORDER BY due_date")
    suspend fun occurrences(ruleId: String): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrence WHERE due_date BETWEEN :from AND :to ORDER BY due_date, rule_id")
    suspend fun occurrencesBetween(
        from: String,
        to: String,
    ): List<OccurrenceEntity>

    /** Removes not-yet-paid future occurrences of a rule so they can be regenerated after a rule change. */
    @Query("DELETE FROM occurrence WHERE rule_id = :ruleId AND status = 'UPCOMING' AND due_date >= :from")
    suspend fun deleteUpcomingFrom(
        ruleId: String,
        from: String,
    )

    /** PAID occurrences whose linked transaction was deleted afterwards: they are due again. */
    @Query(
        """SELECT o.* FROM occurrence o JOIN txn t ON t.id = o.paid_txn_id
           WHERE o.status = 'PAID' AND t.deleted_at IS NOT NULL""",
    )
    suspend fun paidWithDeletedTxn(): List<OccurrenceEntity>

    // ---- report aggregates (effective = POSTED and not deleted) --------------------------------------------------

    /** Net amount per INCOME/EXPENSE account in the range. */
    @Query(
        """SELECT e.ledger_account_id AS accountId, e.currency, SUM(e.amount_minor) AS total
           FROM entry e JOIN txn t ON t.id = e.txn_id JOIN ledger_account a ON a.id = e.ledger_account_id
           WHERE t.status = 'POSTED' AND t.deleted_at IS NULL AND t.txn_date BETWEEN :from AND :to AND a.class = :accountClass
           GROUP BY e.ledger_account_id, e.currency HAVING SUM(e.amount_minor) <> 0""",
    )
    suspend fun totalsByAccountClass(
        accountClass: String,
        from: String,
        to: String,
    ): List<AccountTotal>

    /** Every entry of effective transactions in the range (bounded by the range, never the whole history). */
    @Query(
        """SELECT t.id AS txnId, t.type, t.txn_date AS txnDate, e.ledger_account_id AS accountId, e.amount_minor AS amountMinor, e.currency
           FROM txn t JOIN entry e ON e.txn_id = t.id
           WHERE t.status = 'POSTED' AND t.deleted_at IS NULL AND t.txn_date BETWEEN :from AND :to
           ORDER BY t.txn_date, t.id, e.line_no""",
    )
    suspend fun entriesBetween(
        from: String,
        to: String,
    ): List<EntryRow>

    /** Balance per real account as of a date, for accounts counted in net worth. */
    @Query(
        """SELECT a.id AS accountId, a.currency AS currency, COALESCE(SUM(CASE WHEN t.id IS NOT NULL THEN e.amount_minor END), 0) AS total
           FROM ledger_account a JOIN financial_account f ON f.ledger_account_id = a.id AND f.include_in_net_worth = 1
           LEFT JOIN entry e ON e.ledger_account_id = a.id
           LEFT JOIN txn t ON t.id = e.txn_id AND t.status = 'POSTED' AND t.deleted_at IS NULL AND t.txn_date <= :asOf
           GROUP BY a.id, a.currency""",
    )
    suspend fun netWorthBalances(asOf: String): List<AccountTotal>
}
