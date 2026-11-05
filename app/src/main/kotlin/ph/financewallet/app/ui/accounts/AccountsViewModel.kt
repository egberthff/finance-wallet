package ph.financewallet.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.parseAmount
import ph.financewallet.data.account.AccountRow
import ph.financewallet.data.account.ReconciledBalance
import ph.financewallet.data.ledger.NewAccount
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
)

class AccountsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = mutable

    init {
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
        return AccountsState(
            loading = false,
            accounts = all.filter { it.row.archivedAt == null },
            archived =
                all.filter {
                    it.row.archivedAt !=
                        null
                },
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
}
