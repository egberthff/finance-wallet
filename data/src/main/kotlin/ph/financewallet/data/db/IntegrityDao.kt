package ph.financewallet.data.db

import androidx.room.Dao
import androidx.room.Query

/** Row returned by integrity checks: a short description of each offending record. */
data class IntegrityViolation(
    val detail: String,
)

/**
 * Integrity checks IC-1..IC-4, IC-7 (docs/11-database-schema.md §5). Each query must return zero rows.
 * IC-5 (allocations) and IC-6 (cached summaries) are added with their tables. IC-8 uses PRAGMAs, see [IntegrityChecker].
 */
@Dao
interface IntegrityDao {
    /** IC-1: entries of every transaction sum to zero per currency. */
    @Query(
        """SELECT txn_id || ' ' || currency || ' sum=' || SUM(amount_minor) AS detail
           FROM entry GROUP BY txn_id, currency HAVING SUM(amount_minor) <> 0""",
    )
    suspend fun ic1UnbalancedTransactions(): List<IntegrityViolation>

    /** IC-2: entries on real accounts carry the account's currency. */
    @Query(
        """SELECT e.id || ' entry ' || e.currency || ' vs account ' || a.currency AS detail
           FROM entry e JOIN ledger_account a ON a.id = e.ledger_account_id
           WHERE a.class IN ('ASSET','LIABILITY') AND e.currency <> a.currency""",
    )
    suspend fun ic2CurrencyMismatches(): List<IntegrityViolation>

    /** IC-3: every transaction has at least two entries. */
    @Query(
        """SELECT t.id || ' entries=' || COUNT(e.id) AS detail
           FROM txn t LEFT JOIN entry e ON e.txn_id = t.id
           GROUP BY t.id HAVING COUNT(e.id) < 2""",
    )
    suspend fun ic3TransactionsWithTooFewEntries(): List<IntegrityViolation>

    /** IC-4: at most one live opening balance per real account. */
    @Query(
        """SELECT e.ledger_account_id || ' openings=' || COUNT(DISTINCT t.id) AS detail
           FROM txn t JOIN entry e ON e.txn_id = t.id
           JOIN ledger_account a ON a.id = e.ledger_account_id
           WHERE t.type = 'OPENING_BALANCE' AND t.deleted_at IS NULL AND a.class IN ('ASSET','LIABILITY')
           GROUP BY e.ledger_account_id HAVING COUNT(DISTINCT t.id) > 1""",
    )
    suspend fun ic4DuplicateOpeningBalances(): List<IntegrityViolation>

    /** IC-7: live refunds linked to an expense never exceed the original's spending amount. */
    @Query(
        """SELECT r.refund_of_txn_id || ' refunded=' || SUM(rx.amt) || ' original=' || o.amt AS detail
           FROM txn r
           JOIN (SELECT e.txn_id, -SUM(e.amount_minor) AS amt FROM entry e
                 JOIN ledger_account a ON a.id = e.ledger_account_id AND a.class = 'EXPENSE' GROUP BY e.txn_id) rx
             ON rx.txn_id = r.id
           JOIN (SELECT e.txn_id, SUM(e.amount_minor) AS amt FROM entry e
                 JOIN ledger_account a ON a.id = e.ledger_account_id AND a.class = 'EXPENSE' GROUP BY e.txn_id) o
             ON o.txn_id = r.refund_of_txn_id
           WHERE r.type = 'REFUND' AND r.deleted_at IS NULL AND r.refund_of_txn_id IS NOT NULL
           GROUP BY r.refund_of_txn_id HAVING SUM(rx.amt) > o.amt""",
    )
    suspend fun ic7OverRefunded(): List<IntegrityViolation>
}
