package ph.financewallet.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.parseAmount
import ph.financewallet.data.ledger.EditableTxn
import ph.financewallet.data.ledger.LedgerReads
import ph.financewallet.data.ledger.PageCursor
import ph.financewallet.data.ledger.TransactionListItem
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.ledger.EntryTemplates
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.ledger.TransactionDraft
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.MoneyFormatter
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class TransactionsState(
    val loading: Boolean = true,
    val items: List<TransactionListItem> = emptyList(),
    val next: PageCursor? = null,
    val loadingMore: Boolean = false,
    /** For the "Today" and "Yesterday" day headings; from the app clock (Asia/Manila). */
    val today: java.time.LocalDate? = null,
)

/** Paged list (keyset), newest first. Loads one page at a time; never the whole history (NFR-03). */
class TransactionsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(TransactionsState())
    val state: StateFlow<TransactionsState> = mutable

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    suspend fun reload() {
        val page = s.transactions.page()
        mutable.value = TransactionsState(loading = false, items = page.items, next = page.next, today = s.ctx.today())
    }

    fun loadMore() {
        val cursor = mutable.value.next ?: return
        if (mutable.value.loadingMore) return
        mutable.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = s.transactions.page(after = cursor)
            mutable.update { it.copy(items = it.items + page.items, next = page.next, loadingMore = false) }
        }
    }

    /** Soft delete with revision (TXN-07). Returns an error message resource or null. */
    suspend fun delete(id: String): Int? =
        s.ledger
            .delete(id, reason = null)
            .errorRes()
            .also { if (it == null) reload() }
}

enum class EntryKind { EXPENSE, INCOME, TRANSFER }

data class Option(
    val id: String,
    val label: String,
)

data class EntryOptions(
    val accounts: List<Option> = emptyList(),
    val expenseCategories: List<Option> = emptyList(),
    val incomeCategories: List<Option> = emptyList(),
)

data class EntryForm(
    val kind: EntryKind = EntryKind.EXPENSE,
    val amount: String = "",
    val accountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val date: String = "",
    val description: String = "",
)

/**
 * Fast manual entry (TXN-13): amount, account and category are the only required inputs; the account and categories
 * default to the last used ones, the date to today. Each form instance has one idempotency key, so a double tap on
 * Save stores the transaction once (TXN-09). The key is renewed after a successful save.
 */
class EntryViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutableOptions = MutableStateFlow(EntryOptions())
    val options: StateFlow<EntryOptions> = mutableOptions
    private val mutableForm = MutableStateFlow(EntryForm(date = s.ctx.today().toString()))
    val form: StateFlow<EntryForm> = mutableForm
    private var key = s.newKey()

    init {
        viewModelScope.launch { loadOptions() }
    }

    suspend fun loadOptions() {
        val accounts = s.accounts.accounts().map { Option(it.id, it.name) }
        val categories = s.categories.categories()
        val byId = categories.associateBy { it.id }

        fun label(c: ph.financewallet.data.account.CategoryRow) = c.parentId?.let { byId[it]?.name + " › " + c.name } ?: c.name
        mutableOptions.value =
            EntryOptions(
                accounts = accounts,
                expenseCategories = categories.filter { it.accountClass == "EXPENSE" }.map { Option(it.id, label(it)) },
                incomeCategories = categories.filter { it.accountClass == "INCOME" }.map { Option(it.id, label(it)) },
            )
        mutableForm.update { f -> if (f.accountId == null) f.copy(accountId = accounts.firstOrNull()?.id) else f }
    }

    fun edit(change: (EntryForm) -> EntryForm) = mutableForm.update(change)

    private var editing: EditableTxn? = null

    /** True while the form edits an existing transaction instead of creating one. */
    val isEditing: Boolean get() = editing != null

    /**
     * Prefills the form from an existing expense, income (one category) or transfer (TXN-06). Other types, or
     * split transactions, return false: they cannot be edited with this simple form.
     */
    suspend fun loadExisting(txnId: String): Boolean {
        val e = s.ledger.editable(txnId) ?: return false
        val directory = s.ledger.directory()
        val real = e.transaction.entries.filter { directory.find(it.accountId)?.accountClass?.isReal == true }
        val categories = e.transaction.entries.filter { directory.find(it.accountId)?.accountClass?.isReal == false }
        val form =
            when (e.transaction.type) {
                TxnType.EXPENSE, TxnType.INCOME -> {
                    if (real.size != 1 || categories.size != 1) return false
                    EntryForm(
                        kind = if (e.transaction.type == TxnType.EXPENSE) EntryKind.EXPENSE else EntryKind.INCOME,
                        amount = MoneyFormatter.plain(real.single().amount.let { if (it.isNegative) -it else it }),
                        accountId = real.single().accountId,
                        categoryId = categories.single().accountId,
                    )
                }

                TxnType.TRANSFER -> {
                    if (real.size != 2) return false
                    EntryForm(
                        kind = EntryKind.TRANSFER,
                        amount = MoneyFormatter.plain(real.first { it.amount.isPositive }.amount),
                        accountId = real.first { it.amount.isNegative }.accountId,
                        toAccountId = real.first { it.amount.isPositive }.accountId,
                    )
                }

                else -> {
                    return false
                }
            }
        editing = e
        mutableForm.value = form.copy(date = e.header.txnDate.toString(), description = e.header.description)
        return true
    }

    /** Saves; returns an error message resource, or null on success (the form is then reset for the next entry). */
    suspend fun save(): Int? {
        val f = mutableForm.value
        val (amount, amountError) = parseAmount(f.amount)
        val date = parseDate(f.date)
        val error = if (date == null) R.string.err_date_format else missingField(f) ?: amountError
        if (error != null || date == null || amount == null) return error ?: R.string.err_generic
        val account = checkNotNull(f.accountId)
        val build: suspend (EntryTemplates, LedgerReads) -> DomainResult<TransactionDraft> = { t, _ ->
            when (f.kind) {
                EntryKind.EXPENSE -> t.expense(account, listOf(Split(checkNotNull(f.categoryId), amount)))
                EntryKind.INCOME -> t.income(account, listOf(Split(checkNotNull(f.categoryId), amount)))
                EntryKind.TRANSFER -> t.transfer(account, checkNotNull(f.toAccountId), amount)
            }
        }
        val existing = editing
        val result =
            if (existing == null) {
                s.ledger.record(TxnHeader(date, key, description = f.description), build)
            } else {
                // UC-09: same transaction, new revision; the stored version guards against a stale edit.
                s.ledger.edit(
                    existing.transaction.id,
                    existing.version,
                    existing.header.copy(txnDate = date, description = f.description),
                    build = build,
                )
            }
        return result.errorRes().also {
            if (it == null) {
                key = s.newKey()
                mutableForm.update { old -> old.copy(amount = "", description = "") }
            }
        }
    }

    /** The first required choice that is missing for this kind of entry, if any. */
    private fun missingField(f: EntryForm): Int? =
        when {
            f.accountId == null -> R.string.err_account_required
            f.kind == EntryKind.TRANSFER && f.toAccountId == null -> R.string.err_account_required
            f.kind != EntryKind.TRANSFER && f.categoryId == null -> R.string.err_category_required
            else -> null
        }

    private fun parseDate(text: String): LocalDate? =
        try {
            LocalDate.parse(text.trim())
        } catch (_: DateTimeParseException) {
            null
        }
}
