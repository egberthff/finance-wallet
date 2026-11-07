package ph.financewallet.app.ui.transactions

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.categories.CategoryCreator
import ph.financewallet.app.ui.categories.CategoryOption
import ph.financewallet.app.ui.categories.ServicesCategoryCreator
import ph.financewallet.app.ui.categories.categoryOptions
import ph.financewallet.app.ui.common.Tables
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.parseAmount
import ph.financewallet.app.ui.common.reloadOn
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.ledger.EditableTxn
import ph.financewallet.data.ledger.LedgerReads
import ph.financewallet.data.ledger.PageCursor
import ph.financewallet.data.ledger.TransactionListItem
import ph.financewallet.data.ledger.TransactionQueries
import ph.financewallet.data.ledger.TxnFilter
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.ledger.AccountClass
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
    /** Icon and colour per category id, archived included (old transactions can use archived categories). */
    val categoryStyles: Map<String, CategoryRow> = emptyMap(),
    /** Active filters (blueprint H). The keyset paging is the same with or without them. */
    val accountId: String? = null,
    val filter: TxnFilter = TxnFilter(),
    /** Choices for the filter bar: accounts (archived included, they have history) and categories. */
    val accounts: List<Option> = emptyList(),
    val categoryRows: List<CategoryRow> = emptyList(),
) {
    val filtered: Boolean get() = accountId != null || filter != TxnFilter()
}

/**
 * Paged list (keyset), newest first. Loads one page at a time; never the whole history (NFR-03). It reloads by itself
 * when transactions change anywhere (blueprint F), keeping as many rows as were loaded and the scroll position, so an
 * edit or delete on page 3 does not throw the user back to the top (R-1).
 */
class TransactionsViewModel(
    private val s: AppServices,
    /** Deep links (H): open already filtered to an account or a category. */
    initialAccountId: String? = null,
    initialCategoryId: String? = null,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(TransactionsState(accountId = initialAccountId, filter = TxnFilter(categoryId = initialCategoryId)))
    val state: StateFlow<TransactionsState> = mutable

    /** Kept here, not in the composable, so the position survives leaving for an edit and coming back. */
    val listState = LazyListState()

    init {
        refresh()
        reloadOn(s, *Tables.LEDGER) { reload() }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /** Reloads the same number of rows that are loaded now (at least one page), with the current filters. */
    suspend fun reload() {
        val current = mutable.value
        val want = maxOf(current.items.size, PAGE)
        val items = mutableListOf<TransactionListItem>()
        var cursor: PageCursor? = null
        do {
            val page = s.transactions.page(cursor, current.accountId, minOf(MAX_PAGE, want - items.size), current.filter)
            items += page.items
            cursor = page.next
        } while (cursor != null && items.size < want)
        val rows = s.categories.categories(includeArchived = true)
        mutable.value =
            current.copy(
                loading = false,
                items = items,
                next = cursor,
                loadingMore = false,
                today = s.ctx.today(),
                categoryStyles = rows.associateBy { it.id },
                accounts = s.accounts.accounts(includeArchived = true).map { Option(it.id, it.name, archived = it.archivedAt != null) },
                categoryRows = rows,
            )
    }

    fun loadMore() {
        val st = mutable.value
        val cursor = st.next ?: return
        if (st.loadingMore) return
        mutable.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = s.transactions.page(after = cursor, accountId = st.accountId, filter = st.filter)
            mutable.update { it.copy(items = it.items + page.items, next = page.next, loadingMore = false) }
        }
    }

    /** Sets the filters and loads the first page for them (H). A category includes its subcategories. */
    suspend fun setFilter(
        accountId: String? = mutable.value.accountId,
        categoryId: String? = mutable.value.filter.categoryId,
        from: LocalDate? = mutable.value.filter.from,
        to: LocalDate? = mutable.value.filter.to,
    ) {
        mutable.update { it.copy(accountId = accountId, filter = TxnFilter(categoryId, from, to), items = emptyList(), next = null) }
        // Not scrollToItem: that suspends until the list is laid out, which never happens while the screen is closed.
        listState.requestScrollToItem(0)
        reload()
    }

    suspend fun clearFilters() = setFilter(null, null, null, null)

    /** Soft delete with revision (TXN-07). Returns an error message resource or null. */
    suspend fun delete(id: String): Int? =
        s.ledger
            .delete(id, reason = null)
            .errorRes()
            .also { if (it == null) reload() }

    private companion object {
        const val PAGE = TransactionQueries.DEFAULT_PAGE
        const val MAX_PAGE = TransactionQueries.MAX_PAGE
    }
}

enum class EntryKind { EXPENSE, INCOME, TRANSFER }

/** A choosable item. [archived] is set only for an archived one kept because the edited item already uses it (B). */
data class Option(
    val id: String,
    val label: String,
    val archived: Boolean = false,
)

data class EntryOptions(
    val accounts: List<Option> = emptyList(),
    /** All categories, archived included; [categories] decides what is offered. */
    val categoryRows: List<CategoryRow> = emptyList(),
    /** Accounts and categories the edited transaction uses: offered even when archived since (UC-09, blueprint B). */
    val keepIds: Set<String> = emptySet(),
    val recentExpense: List<String> = emptyList(),
    val recentIncome: List<String> = emptyList(),
    val lastExpense: String? = null,
    val lastIncome: String? = null,
) {
    fun categories(kind: EntryKind): List<CategoryOption> =
        categoryOptions(categoryRows, if (kind == EntryKind.INCOME) "INCOME" else "EXPENSE", keepIds)

    fun recent(kind: EntryKind): List<String> = if (kind == EntryKind.INCOME) recentIncome else recentExpense

    /** The remembered category for [kind] if it is still offered (E: a remembered archived category is not preselected). */
    fun remembered(kind: EntryKind): String? =
        (if (kind == EntryKind.INCOME) lastIncome else lastExpense)?.takeIf { id -> categories(kind).any { it.id == id && !it.archived } }
}

data class EntryForm(
    val kind: EntryKind = EntryKind.EXPENSE,
    val amount: String = "",
    val accountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val date: String = "",
    val description: String = "",
    val merchant: String = "",
    val notes: String = "",
    val reference: String = "",
)

/**
 * Fast manual entry (TXN-13): amount, account and category are the only required inputs. A new entry starts with the
 * last used account and the last used category of its kind (remembered after each new save, E), and the date of
 * today. Each form instance has one idempotency key, so a double tap on Save stores the transaction once (TXN-09);
 * Save is also disabled while a save runs. The key is renewed after a successful save.
 */
class EntryViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutableOptions = MutableStateFlow(EntryOptions())
    val options: StateFlow<EntryOptions> = mutableOptions
    private val mutableForm = MutableStateFlow(EntryForm(date = s.ctx.today().toString()))
    val form: StateFlow<EntryForm> = mutableForm
    private val mutableSaving = MutableStateFlow(false)

    /** True while a save runs: the Save button is disabled and shows progress (T-9). */
    val saving: StateFlow<Boolean> = mutableSaving
    private var key = s.newKey()

    /** "+ New category" in the picker: creates (or restores) and refreshes the options. */
    val creator: CategoryCreator = ServicesCategoryCreator(s) { loadOptions() }

    init {
        viewModelScope.launch { loadOptions() }
    }

    suspend fun loadOptions() {
        val keep = mutableOptions.value.keepIds
        val allAccounts = s.accounts.accounts(includeArchived = true)
        val accounts =
            allAccounts
                .filter { it.archivedAt == null || it.id in keep }
                .map { Option(it.id, it.name, archived = it.archivedAt != null) }
        val prefs = s.entryPreferences
        val today = s.ctx.today()
        mutableOptions.value =
            EntryOptions(
                accounts = accounts,
                categoryRows = s.categories.categories(includeArchived = true),
                keepIds = keep,
                recentExpense = prefs.recentCategories(AccountClass.EXPENSE, today),
                recentIncome = prefs.recentCategories(AccountClass.INCOME, today),
                lastExpense = prefs.lastCategory(AccountClass.EXPENSE),
                lastIncome = prefs.lastCategory(AccountClass.INCOME),
            )
        if (editing != null) return
        val live = accounts.filterNot { it.archived }
        val lastAccount = prefs.lastAccount()?.takeIf { id -> live.any { it.id == id } }
        mutableForm.update { f ->
            f.copy(
                accountId = f.accountId ?: lastAccount ?: live.firstOrNull()?.id,
                categoryId = f.categoryId ?: mutableOptions.value.remembered(f.kind).takeIf { f.kind != EntryKind.TRANSFER },
            )
        }
    }

    fun edit(change: (EntryForm) -> EntryForm) = mutableForm.update(change)

    /** Switches between expense, income and transfer; a new entry takes the remembered category of the new kind. */
    fun setKind(kind: EntryKind) {
        val remembered = if (editing == null && kind != EntryKind.TRANSFER) mutableOptions.value.remembered(kind) else null
        mutableForm.update { it.copy(kind = kind, categoryId = remembered) }
    }

    private var editing: EditableTxn? = null

    /** True while the form edits an existing transaction instead of creating one. */
    val isEditing: Boolean get() = editing != null

    /**
     * Prefills the form from an existing expense, income (one category) or transfer (TXN-06). Other types, or
     * split transactions, return false: they cannot be edited with this simple form. An archived account or category
     * the transaction uses stays selected and is labelled "(archived)" (blueprint B).
     */
    suspend fun loadExisting(txnId: String): Boolean {
        val e = s.ledger.editable(txnId) ?: return false
        val form = formOf(e, s.ledger.directory()) ?: return false
        editing = e
        mutableOptions.update {
            it.copy(
                keepIds =
                    e.transaction.entries
                        .map { entry -> entry.accountId }
                        .toSet(),
            )
        }
        loadOptions()
        mutableForm.value =
            form.copy(
                date = e.header.txnDate.toString(),
                description = e.header.description,
                merchant = e.header.merchant.orEmpty(),
                notes = e.header.notes.orEmpty(),
                reference = e.header.referenceNo.orEmpty(),
            )
        return true
    }

    private fun formOf(
        e: EditableTxn,
        directory: ph.financewallet.domain.ledger.AccountDirectory,
    ): EntryForm? {
        val real = e.transaction.entries.filter { directory.find(it.accountId)?.accountClass?.isReal == true }
        val categories = e.transaction.entries.filter { directory.find(it.accountId)?.accountClass?.isReal == false }
        return when (e.transaction.type) {
            TxnType.EXPENSE, TxnType.INCOME -> {
                if (real.size != 1 || categories.size != 1) return null
                EntryForm(
                    kind = if (e.transaction.type == TxnType.EXPENSE) EntryKind.EXPENSE else EntryKind.INCOME,
                    amount = MoneyFormatter.plain(real.single().amount.let { if (it.isNegative) -it else it }),
                    accountId = real.single().accountId,
                    categoryId = categories.single().accountId,
                )
            }

            TxnType.TRANSFER -> {
                if (real.size != 2) return null
                EntryForm(
                    kind = EntryKind.TRANSFER,
                    amount = MoneyFormatter.plain(real.first { it.amount.isPositive }.amount),
                    accountId = real.first { it.amount.isNegative }.accountId,
                    toAccountId = real.first { it.amount.isPositive }.accountId,
                )
            }

            else -> {
                null
            }
        }
    }

    /** Saves; returns an error message resource, or null on success (the form is then reset for the next entry). */
    suspend fun save(): Int? {
        // A second tap while saving never saves twice (the idempotency key also guards this) and never closes the form.
        if (mutableSaving.value) return R.string.err_save_in_progress
        mutableSaving.value = true
        try {
            return saveNow()
        } finally {
            mutableSaving.value = false
        }
    }

    private suspend fun saveNow(): Int? {
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
                s.ledger.record(header(TxnHeader(date, key), f), build)
            } else {
                // UC-09: same transaction, new revision; the stored version guards against a stale edit.
                s.ledger.edit(existing.transaction.id, existing.version, header(existing.header.copy(txnDate = date), f), build = build)
            }
        return result.errorRes().also {
            if (it == null) {
                if (existing == null) remember(f, account)
                key = s.newKey()
                mutableForm.update { old -> old.copy(amount = "", description = "", merchant = "", notes = "", reference = "") }
            }
        }
    }

    private fun header(
        base: TxnHeader,
        f: EntryForm,
    ) = base.copy(
        description = f.description.trim(),
        merchant = f.merchant.trim().ifEmpty { null },
        notes = f.notes.trim().ifEmpty { null },
        referenceNo = f.reference.trim().ifEmpty { null },
    )

    /** E: remember the account and the category of a new entry (never after an edit). Preferences only. */
    private suspend fun remember(
        f: EntryForm,
        account: String,
    ) {
        val cls =
            when (f.kind) {
                EntryKind.EXPENSE -> AccountClass.EXPENSE
                EntryKind.INCOME -> AccountClass.INCOME
                EntryKind.TRANSFER -> null
            }
        s.entryPreferences.remember(account, cls, f.categoryId)
        loadOptions()
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
