package ph.financewallet.data.ledger

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.EntryEntity
import ph.financewallet.domain.ledger.TxnStatus
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/**
 * Read-side query service for one transaction's detail screen (blueprint G): header fields, lines, links, refunds and
 * revisions. Read-only, like [TransactionQueries]; writes stay in [LedgerService].
 */
class TransactionDetailQueries(
    private val db: FinanceDatabase,
) {
    private val dao get() = db.ledgerDao()

    /** Revision history of a transaction, oldest first (read-only; TXN-06 audit trail shown on the detail screen). */
    suspend fun history(txnId: String): List<RevisionSummary> =
        dao.revisions(txnId).map { RevisionSummary(it.revisionNo, it.changeKind, it.reason, it.createdAt) }

    /** Live refunds linked to [txnId] (TXN-05), with their amounts. */
    suspend fun refundsOf(txnId: String): List<RefundSummary> =
        dao.refundsOf(txnId).map { r ->
            val refunded = dao.entries(r.id).filter { dao.ledgerAccount(it.ledgerAccountId)?.accountClass == "EXPENSE" }
            RefundSummary(
                r.id,
                LocalDate.parse(r.txnDate),
                Money(-refunded.sumOf { it.amountMinor }, CurrencyCode.of(refunded.first().currency)),
            )
        }

    /**
     * Everything the transaction detail screen shows (blueprint G), read in one go: header fields, each line with its
     * account or category, links (refund of, reversal), live refunds and how much can still be refunded, and revisions.
     * Null when the transaction does not exist.
     */
    suspend fun details(txnId: String): TxnDetails? {
        val t = dao.txn(txnId) ?: return null
        val lines =
            dao.entries(txnId).map { e ->
                val a = dao.ledgerAccount(e.ledgerAccountId)
                DetailLine(e.ledgerAccountId, a?.name.orEmpty(), a?.accountClass.orEmpty(), a?.archivedAt != null, a?.systemKey, money(e))
            }
        val refunds = refundsOf(txnId)
        val spent = lines.filter { it.accountClass == "EXPENSE" }.map { it.amount }
        val refundable =
            if (t.type == TxnType.EXPENSE.name && t.deletedAt == null && spent.isNotEmpty()) {
                val currency = spent.first().currency
                Money.sum(spent, currency) - Money(dao.refundedAgainst(txnId, ""), currency)
            } else {
                null
            }
        return TxnDetails(
            id = t.id,
            type = TxnType.valueOf(t.type),
            status = TxnStatus.valueOf(t.status),
            txnDate = LocalDate.parse(t.txnDate),
            postingDate = t.postingDate?.let(LocalDate::parse),
            description = t.description,
            merchant = t.merchant,
            notes = t.notes,
            referenceNo = t.referenceNo,
            source = t.source,
            importBatchId = t.importBatchId,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
            deleted = t.deletedAt != null,
            version = t.version,
            lines = lines,
            refundOfTxnId = t.refundOfTxnId,
            reversesTxnId = t.reversesTxnId,
            reversedByTxnId = dao.liveReversalOf(txnId)?.id,
            refunds = refunds,
            refundable = refundable,
            revisions = history(txnId),
        )
    }

    private fun money(e: EntryEntity) = Money(e.amountMinor, CurrencyCode.of(e.currency))
}

/** One revision of a transaction (TXN-06): CREATE, UPDATE, DELETE, RESTORE or IMPORT_ROLLBACK. */
data class RevisionSummary(
    val revisionNo: Int,
    val changeKind: String,
    val reason: String?,
    val createdAt: Long,
)

/** A live refund of a transaction and the amount it gave back (positive). */
data class RefundSummary(
    val id: String,
    val date: LocalDate,
    val amount: Money,
)

/** One line of a transaction: the account or category it touches, and its signed amount. */
data class DetailLine(
    val ledgerAccountId: String,
    val name: String,
    val accountClass: String,
    val archived: Boolean,
    val systemKey: String?,
    val amount: Money,
)

/** Read model of the transaction detail screen (blueprint G). [refundable] is null when a refund does not apply. */
data class TxnDetails(
    val id: String,
    val type: TxnType,
    val status: TxnStatus,
    val txnDate: LocalDate,
    val postingDate: LocalDate?,
    val description: String,
    val merchant: String?,
    val notes: String?,
    val referenceNo: String?,
    val source: String,
    val importBatchId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val version: Long,
    val lines: List<DetailLine>,
    val refundOfTxnId: String?,
    val reversesTxnId: String?,
    val reversedByTxnId: String?,
    val refunds: List<RefundSummary>,
    val refundable: Money?,
    val revisions: List<RevisionSummary>,
)
