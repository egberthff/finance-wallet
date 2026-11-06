package ph.financewallet.app.ui.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.parseAmount
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.advanced.AssetKind
import ph.financewallet.data.advanced.AssetSummary
import ph.financewallet.data.advanced.DebtKind
import ph.financewallet.data.advanced.DebtSummary
import ph.financewallet.data.advanced.InstallmentPlan
import ph.financewallet.data.advanced.NewDebt
import ph.financewallet.data.advanced.RecurringCost
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.advanced.RuleSpec
import ph.financewallet.data.advanced.UpcomingItem
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence

private val PHP = CurrencyCode.PHP

private fun dayOrNull(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..Recurrence.MAX_DAY }

// ---- debts ---------------------------------------------------------------------------------------------------------

data class DebtsState(
    val loading: Boolean = true,
    val debts: List<DebtSummary> = emptyList(),
    val accounts: List<Option> = emptyList(),
)

/** Debts screen (UC-11/12). Outstanding always comes from the ledger, never from a stored number. */
class DebtsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(DebtsState())
    val state: StateFlow<DebtsState> = mutable

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    suspend fun load(): DebtsState {
        val debtIds =
            s.debts
                .summaries()
                .map { it.id }
                .toSet()
        val assets =
            s.accounts
                .accounts()
                .filter { it.accountClass == "ASSET" && it.id !in debtIds }
                .map { Option(it.id, it.name) }
        return DebtsState(false, s.debts.summaries(), assets)
    }

    /**
     * New debt. With [receivedInto] the borrowed money arrived in that account (a disbursement, never income);
     * otherwise [originalText] is what is owed today. Optional monthly plan: [installmentsText] payments on [dueDayText].
     */
    suspend fun create(
        name: String,
        kind: DebtKind,
        originalText: String,
        receivedInto: String?,
        installmentsText: String,
        dueDayText: String,
        key: String,
    ): Int? {
        val (original, error) = parseAmount(originalText)
        val count = installmentsText.trim().toIntOrNull()
        val day = dayOrNull(dueDayText)
        val inputError =
            when {
                original == null -> error
                installmentsText.isBlank() -> null
                count == null -> R.string.err_installments
                day == null -> R.string.err_start_day
                else -> null
            }
        if (inputError != null || original == null) return inputError ?: R.string.err_generic
        val plan = if (count != null && day != null) InstallmentPlan(Recurrence.Monthly(s.ctx.today().plusDays(1), day), count) else null
        val req =
            NewDebt(
                name = name,
                kind = kind,
                currency = PHP,
                original = original,
                startDate = s.ctx.today(),
                receivedIntoAccountId = receivedInto,
                currentlyOwed = if (receivedInto == null) original else null,
                plan = plan,
                idempotencyKey = key,
            )
        return s.debts
            .create(req)
            .errorRes()
            .also { if (it == null) mutable.value = load() }
    }

    /** UC-11. Only [principalText] reduces the debt; interest and fees are spending. Links the next unpaid installment. */
    suspend fun pay(
        debt: DebtSummary,
        fromAccount: String?,
        principalText: String,
        interestText: String,
        feesText: String,
        key: String,
    ): Int? {
        val from = fromAccount ?: return R.string.err_account_required
        val amounts = listOf(principalText, interestText, feesText).map { if (it.isBlank()) Money.zero(PHP) to null else parseAmount(it) }
        amounts.firstOrNull { it.first == null }?.let { return it.second }
        val (principal, interest, fees) = amounts.map { checkNotNull(it.first) }
        val seq =
            s.debts
                .schedule(debt.id)
                .firstOrNull { it.paidTxnId == null }
                ?.seq
        val result =
            s.debts.recordPayment(
                debt.id,
                TxnHeader(s.ctx.today(), key, description = debt.name),
                from,
                principal,
                interest,
                fees,
                seq,
            )
        return result.errorRes().also { if (it == null) mutable.value = load() }
    }
}

// ---- bills ---------------------------------------------------------------------------------------------------------

data class BillsState(
    val loading: Boolean = true,
    val items: List<UpcomingItem> = emptyList(),
    val costs: List<RecurringCost> = emptyList(),
    val accounts: List<Option> = emptyList(),
    val expenseCategories: List<Option> = emptyList(),
    val incomeCategories: List<Option> = emptyList(),
)

/** Bills, subscriptions and expected income (UC-22/23). Marking paid always creates or links a real transaction. */
class BillsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(BillsState())
    val state: StateFlow<BillsState> = mutable

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    suspend fun load(): BillsState {
        s.recurring.materialize()
        val today = s.ctx.today()
        val categories = s.categories.categories()
        return BillsState(
            loading = false,
            items = s.recurring.upcoming(today.minusDays(BACK), today.plusDays(AHEAD)),
            costs = s.recurring.costs(),
            accounts = s.accounts.accounts().map { Option(it.id, it.name) },
            expenseCategories = categories.filter { it.accountClass == "EXPENSE" }.map { Option(it.id, it.name) },
            incomeCategories = categories.filter { it.accountClass == "INCOME" }.map { Option(it.id, it.name) },
        )
    }

    suspend fun create(
        kind: RuleKind,
        name: String,
        amountText: String,
        dayText: String,
        accountId: String?,
        categoryId: String?,
        reminderDaysText: String,
        estimate: Boolean,
    ): Int? {
        val (amount, error) = parseAmount(amountText)
        val day = dayOrNull(dayText)
        val reminder = reminderDaysText.trim().toIntOrNull()
        val inputError =
            when {
                amount == null -> error
                day == null -> R.string.err_start_day
                reminderDaysText.isNotBlank() && reminder == null -> R.string.err_reminder_days
                else -> null
            }
        if (inputError != null || amount == null || day == null) return inputError ?: R.string.err_generic
        val spec =
            RuleSpec(
                kind = kind,
                name = name,
                amount = amount,
                // First due date is the next day-[day] on or after today: a new rule never starts overdue.
                recurrence = Recurrence.Monthly(s.ctx.today(), day),
                amountIsEstimate = estimate,
                accountId = accountId,
                categoryId = categoryId,
                reminderDaysBefore = reminder,
            )
        return s.recurring
            .createRule(spec)
            .errorRes()
            .also { if (it == null) mutable.value = load() }
    }

    /** Records the actual payment today for [item]; [amountText] blank = the scheduled amount. */
    suspend fun markPaid(
        item: UpcomingItem,
        accountId: String?,
        amountText: String,
        key: String,
    ): Int? {
        val amount =
            if (amountText.isBlank()) {
                null
            } else {
                parseAmount(amountText).let { (m, e) -> m ?: return e }
            }
        val result =
            s.recurring.markPaid(
                item.occurrenceId,
                TxnHeader(s.ctx.today(), key, description = item.name),
                accountId = accountId,
                amount = amount,
            )
        return result.errorRes().also { if (it == null) mutable.value = load() }
    }

    suspend fun skip(item: UpcomingItem): Int? =
        s.recurring.skip(item.occurrenceId).errorRes().also {
            if (it ==
                null
            ) {
                mutable.value = load()
            }
        }

    private companion object {
        const val BACK = 31L
        const val AHEAD = 62L
    }
}

// ---- assets --------------------------------------------------------------------------------------------------------

data class AssetsState(
    val loading: Boolean = true,
    val assets: List<AssetSummary> = emptyList(),
)

/** User-entered assets and valuations (UC-25). The app never claims to know market values (AST-01). */
class AssetsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(AssetsState())
    val state: StateFlow<AssetsState> = mutable

    fun refresh() {
        viewModelScope.launch { mutable.value = AssetsState(false, s.assets.assets()) }
    }

    suspend fun create(
        name: String,
        kind: AssetKind,
        valueText: String,
    ): Int? {
        val (value, error) = if (valueText.isBlank()) null to null else parseAmount(valueText)
        if (valueText.isNotBlank() && value == null) return error
        val created = s.assets.create(name, kind, PHP)
        val id = (created as? ph.financewallet.domain.common.DomainResult.Ok)?.value
        val result = if (id != null && value != null) s.assets.addValuation(id, s.ctx.today(), value).errorRes() else created.errorRes()
        refresh()
        return result
    }

    suspend fun addValuation(
        assetId: String,
        valueText: String,
    ): Int? {
        val (value, error) = parseAmount(valueText)
        if (value == null) return error
        return s.assets
            .addValuation(assetId, s.ctx.today(), value)
            .errorRes()
            .also { if (it == null) refresh() }
    }
}
