package ph.financewallet.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.Tables
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.parseAmount
import ph.financewallet.app.ui.common.reloadOn
import ph.financewallet.data.account.AccountEditState
import ph.financewallet.data.account.AccountRow
import ph.financewallet.data.account.ReconciledBalance
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money

/**
 * One account as shown in the list. [balance] is the locally calculated balance as of today; [pending] and
 * [reconciled] are kept separate so a calculated figure is never presented as a verified one (ACC-05).
 */
data class AccountUi(
    val row: AccountRow,
    val balance: Money,
    val pending: Money,
    val reconciled: ReconciledBalance?,
)

data class AccountsState(
    val loading: Boolean = true,
    val accounts: List<AccountUi> = emptyList(),
    val archived: List<AccountUi> = emptyList(),
    /** Active PHP asset accounts (cash, bank, e-wallet, savings): the same figure as Home's "Money in your accounts". */
    val inAccounts: Money = Money.zero(CurrencyCode.PHP),
    /** Active PHP liability accounts as a positive amount owed; shown beside [inAccounts], never netted into it. */
    val owed: Money = Money.zero(CurrencyCode.PHP),
)

class AccountsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = mutable

    init {
        reloadOn(s, *Tables.ACCOUNTS) { mutable.value = load() }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    suspend fun load(): AccountsState {
        val today = s.ctx.today()
        val all =
            s.accounts.accounts(includeArchived = true).map { row ->
                AccountUi(row, s.ledger.balance(row.id, today), s.ledger.pendingBalance(row.id), s.accounts.reconciledBalance(row.id))
            }
        val active = all.filter { it.row.archivedAt == null }
        val php = CurrencyCode.PHP

        fun total(cls: AccountClass): Money =
            Money.sum(active.filter { it.row.currency == php.code && it.row.accountClass == cls.name }.map { it.balance }, php)
        return AccountsState(
            loading = false,
            accounts = active,
            archived = all.filter { it.row.archivedAt != null },
            inAccounts = total(AccountClass.ASSET),
            owed = -total(AccountClass.LIABILITY),
        )
    }

    /** Returns an error message resource, or null when the account was created. */
    suspend fun create(
        name: String,
        type: FinancialAccountType,
        openingText: String,
    ): Int? {
        val (opening, amountError) = if (openingText.isBlank()) Money.zero(CurrencyCode.PHP) to null else parseAmount(openingText)
        if (opening == null) return amountError
        val result = s.ledger.createAccount(NewAccount(name, type, CurrencyCode.PHP, s.ctx.today(), opening))
        return result.errorRes().also { if (it == null) mutable.value = load() }
    }

    suspend fun archive(
        id: String,
        acknowledgeNonZeroBalance: Boolean,
    ): Int? =
        s.accounts
            .archive(id, acknowledgeNonZeroBalance)
            .errorRes()
            .also { if (it == null) mutable.value = load() }

    suspend fun unarchive(id: String): Int? =
        s.accounts
            .unarchive(id)
            .errorRes()
            .also { if (it == null) mutable.value = load() }

    /** UC-13 for today. [statementText] is the balance the bank or e-wallet shows (credit cards: amount owed). */
    suspend fun reconcile(
        id: String,
        statementText: String,
        createAdjustment: Boolean,
        key: String,
    ): Int? {
        val (statement, amountError) = parseAmount(statementText)
        if (statement == null) return amountError ?: R.string.err_generic
        val result = s.accounts.reconcile(id, s.ctx.today(), statement, createAdjustment, key)
        return result.errorRes().also { if (it == null) mutable.value = load() }
    }

    /** Current values for the edit form (UC-02). */
    suspend fun editState(id: String): AccountEditState? = s.accounts.editState(id)

    /**
     * UC-02 from the account dialog: name, type, institution and opening balance. The currency never changes here. A changed
     * opening balance edits the account's opening transaction, which keeps a revision (TXN-06).
     */
    suspend fun edit(
        id: String,
        state: AccountEditState,
        name: String,
        institution: String,
        openingText: String,
        type: FinancialAccountType = state.edit.type ?: FinancialAccountType.OTHER,
    ): Int? {
        val (opening, amountError) = parseAmount(openingText.ifBlank { "0" }, state.edit.currency)
        if (opening == null) return amountError ?: R.string.err_generic
        val edit = state.edit.copy(name = name, institution = institution.trim().ifEmpty { null }, openingAmount = opening, type = type)
        return s.accounts
            .edit(id, state.version, edit)
            .errorRes()
            .also { if (it == null) mutable.value = load() }
    }
}
