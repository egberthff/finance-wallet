package ph.financewallet.data.ledger

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.EntryEntity
import ph.financewallet.data.db.entity.LedgerAccountEntity
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** Position after the last item of a page (keyset pagination). */
data class PageCursor(
    val txnDate: String,
    val id: String,
)

/**
 * One row of the transaction list. [amount] is signed from the point of view of [accountName]'s account:
 * negative = money left it. For transfers without an account filter it is the (positive) amount moved, with
 * [counterpartyName] the receiving account. [categoryNames] lists income/expense categories, never totals.
 */
data class TransactionListItem(
    val id: String,
    val type: TxnType,
    val pending: Boolean,
    val date: LocalDate,
    val description: String,
    val merchant: String?,
    val amount: Money,
    val accountName: String,
    val counterpartyName: String?,
    val categoryNames: List<String>,
    val version: Long,
)

/** Optional list filters (report drill-down and export). */
data class TxnFilter(
    val categoryId: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val postedOnly: Boolean = false,
)

data class TransactionPage(
    val items: List<TransactionListItem>,
    /** Null when there are no more items. */
    val next: PageCursor?,
)

/** Read-side query service for transaction lists (docs/12-use-cases.md: queries are not use cases). */
class TransactionQueries(
    private val db: FinanceDatabase,
) {
    private val dao get() = db.ledgerDao()

    /**
     * A page of live transactions, newest first. Only [limit] headers plus their entries are loaded, so memory use does
     * not grow with the history (NFR-03/04).
     */
    suspend fun page(
        after: PageCursor? = null,
        accountId: String? = null,
        limit: Int = DEFAULT_PAGE,
        filter: TxnFilter = TxnFilter(),
    ): TransactionPage {
        require(limit in 1..MAX_PAGE) { "limit must be in 1..$MAX_PAGE" }
        val rows =
            dao.txnPage(
                after?.txnDate,
                after?.id,
                accountId,
                limit + 1,
                filter.categoryId,
                filter.from?.toString(),
                filter.to?.toString(),
                filter.postedOnly,
            )
        val pageRows = rows.take(limit)
        if (pageRows.isEmpty()) return TransactionPage(emptyList(), null)
        val entries = dao.entriesOf(pageRows.map { it.id }).groupBy { it.txnId }
        val accounts =
            dao
                .ledgerAccounts(
                    entries.values
                        .flatten()
                        .map { it.ledgerAccountId }
                        .distinct(),
                ).associateBy { it.id }
        val items =
            pageRows.map { row ->
                item(row, entries[row.id].orEmpty(), accounts, accountId)
            }
        val next = if (rows.size > limit) pageRows.last().let { PageCursor(it.txnDate, it.id) } else null
        return TransactionPage(items, next)
    }

    private fun item(
        row: TxnListRow,
        entries: List<EntryEntity>,
        accounts: Map<String, LedgerAccountEntity>,
        accountId: String?,
    ): TransactionListItem {
        fun account(e: EntryEntity) = accounts.getValue(e.ledgerAccountId)
        val real = entries.filter { account(it).accountClass in REAL }
        val categories = entries.filter { account(it).accountClass in CATEGORY_CLASSES && account(it).systemKey == null }
        val focus =
            accountId?.let { id -> real.firstOrNull { it.ledgerAccountId == id } }
                ?: real.firstOrNull { it.amountMinor < 0 }
                ?: real.first()
        val isMove = real.size > 1 && accountId == null
        val amountMinor = if (isMove) -focus.amountMinor else focus.amountMinor
        val counterparty = if (real.size > 1) real.firstOrNull { it !== focus }?.let { account(it).name } else null
        return TransactionListItem(
            id = row.id,
            type = TxnType.valueOf(row.type),
            pending = row.status == "PENDING",
            date = LocalDate.parse(row.txnDate),
            description = row.description,
            merchant = row.merchant,
            amount = Money(if (isMove && amountMinor < 0) -amountMinor else amountMinor, CurrencyCode.of(focus.currency)),
            accountName = account(focus).name,
            counterpartyName = counterparty,
            categoryNames = categories.map { account(it).name }.distinct(),
            version = row.version,
        )
    }

    companion object {
        const val DEFAULT_PAGE = 50
        const val MAX_PAGE = 200
        private val REAL = setOf("ASSET", "LIABILITY")
        private val CATEGORY_CLASSES = setOf("INCOME", "EXPENSE")
    }
}
