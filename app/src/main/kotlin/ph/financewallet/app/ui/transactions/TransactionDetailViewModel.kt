package ph.financewallet.app.ui.transactions

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.Tables
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.parseAmount
import ph.financewallet.app.ui.common.reloadOn
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.ledger.TxnDetails
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.money.MoneyFormatter
import java.time.LocalDate

data class TransactionDetailState(
    val loading: Boolean = true,
    /** Null after loading means the transaction does not exist. */
    val details: TxnDetails? = null,
    /** Categories by id (archived included) for the "Main › Sub" path and icon. */
    val categories: Map<String, CategoryRow> = emptyMap(),
    /** Live accounts a refund can go back to. */
    val refundAccounts: List<Option> = emptyList(),
)

/**
 * Transaction detail (blueprint G; TXN-02/05/06/07): every header field, the lines, links, refunds and the revision
 * history, plus Edit, Record refund and Delete. Read-only until the user confirms an action.
 */
class TransactionDetailViewModel(
    private val s: AppServices,
    private val txnId: String,
) : ViewModel() {
    private val mutable = MutableStateFlow(TransactionDetailState())
    val state: StateFlow<TransactionDetailState> = mutable
    private var refundKey = s.newKey()

    init {
        reloadOn(s, *Tables.LEDGER) { load() }
    }

    suspend fun load() {
        mutable.value =
            TransactionDetailState(
                loading = false,
                details = s.transactionDetails.details(txnId),
                categories = s.categories.categories(includeArchived = true).associateBy { it.id },
                refundAccounts = s.accounts.accounts().map { Option(it.id, it.name) },
            )
    }

    /** The remaining refundable amount as editable text ("1,234.50" → "1234.50"), or "" when no refund applies. */
    fun refundDefault(): String =
        mutable.value.details
            ?.refundable
            ?.let { MoneyFormatter.plain(it) }
            .orEmpty()

    /** The account the original money left, so a refund goes back there by default. */
    fun refundAccountDefault(): String? =
        mutable.value.details
            ?.lines
            ?.firstOrNull { it.accountClass == "ASSET" || it.accountClass == "LIABILITY" }
            ?.ledgerAccountId
            ?.takeIf { id -> mutable.value.refundAccounts.any { it.id == id } }

    /**
     * Records a refund linked to this expense (TXN-05): it reduces spending in the original's category and is never
     * income. The amount may not exceed what is still unrefunded (`REFUND_EXCEEDS_ORIGINAL`).
     */
    suspend fun recordRefund(
        amountText: String,
        accountId: String?,
        date: LocalDate,
    ): Int? {
        val (amount, error) = parseAmount(amountText)
        if (error != null || amount == null) return error ?: R.string.err_generic
        val account = accountId ?: return R.string.err_account_required
        val description =
            mutable.value.details
                ?.description
                .orEmpty()
        val result =
            s.ledger.record(TxnHeader(date, refundKey, description = description)) { t, reads ->
                val original =
                    reads.refundOriginal(txnId) ?: return@record ph.financewallet.domain.common.DomainResult.Err(
                        ph.financewallet.domain.common.DomainError
                            .NotFound("transaction", txnId),
                    )
                t.refund(account, amount, null, original)
            }
        return result.errorRes().also {
            if (it == null) {
                refundKey = s.newKey()
                load()
            }
        }
    }

    /** Soft delete with a revision (TXN-07): it leaves balances and reports and stays in the history. */
    suspend fun delete(): Int? =
        s.ledger
            .delete(txnId, reason = null)
            .errorRes()
            .also { if (it == null) load() }

    fun today(): LocalDate = s.ctx.today()
}
