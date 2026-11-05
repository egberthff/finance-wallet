package ph.financewallet.domain.ledger

import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** In-memory account directory with a realistic PH account set, shared by ledger tests. */
class LedgerFixture {
    val php = CurrencyCode.PHP
    val usd = CurrencyCode.of("USD")

    fun p(minor: Long) = Money(minor, php)

    fun u(minor: Long) = Money(minor, usd)

    private val all = mutableMapOf<String, LedgerAccount>()

    private fun real(
        id: String,
        type: FinancialAccountType,
        currency: CurrencyCode = php,
        archived: Boolean = false,
    ) = LedgerAccount(id, type.accountClass, currency, type, archived = archived).also { all[id] = it }

    private fun nominal(
        id: String,
        cls: AccountClass,
        archived: Boolean = false,
    ) = LedgerAccount(id, cls, null, archived = archived).also { all[id] = it }

    val gcash = real("gcash", FinancialAccountType.EWALLET)
    val bpi = real("bpi", FinancialAccountType.BANK)
    val savings = real("bpi-savings", FinancialAccountType.SAVINGS)
    val cash = real("cash", FinancialAccountType.CASH)
    val card = real("card", FinancialAccountType.CREDIT_CARD)
    val loan = real("loan", FinancialAccountType.LOAN)
    val usdBank = real("usd-bank", FinancialAccountType.BANK, usd)
    val oldWallet = real("old-wallet", FinancialAccountType.EWALLET, archived = true)

    val salary = nominal("salary", AccountClass.INCOME)
    val freelance = nominal("freelance", AccountClass.INCOME)
    val food = nominal("food", AccountClass.EXPENSE)
    val transport = nominal("transport", AccountClass.EXPENSE)
    val shopping = nominal("shopping", AccountClass.EXPENSE)
    val oldCategory = nominal("old-category", AccountClass.EXPENSE, archived = true)

    private val systems =
        SystemAccount.entries.associateWith { s ->
            LedgerAccount("sys:${s.key}", s.accountClass, null, systemAccount = s).also { all[it.id] = it }
        }

    val directory =
        object : AccountDirectory {
            override fun find(id: String) = all[id]

            override fun system(account: SystemAccount) = systems.getValue(account)
        }

    val templates = EntryTemplates(directory)
    val calculator = LedgerCalculator(directory)

    /** All real PHP accounts that count toward net worth. */
    val phpRealAccounts = listOf(gcash, bpi, savings, cash, card, loan).map { it.id }

    private var seq = 0

    fun post(
        draft: TransactionDraft,
        date: LocalDate = LocalDate.of(2026, 11, 5),
        status: TxnStatus = TxnStatus.POSTED,
    ) = LedgerTransaction(
        id = "t${++seq}",
        type = draft.type,
        status = status,
        txnDate = date,
        entries = draft.entries,
        reversesTxnId = draft.reversesTxnId,
        refundOfTxnId = draft.refundOfTxnId,
    )

    fun postOk(
        result: ph.financewallet.domain.common.DomainResult<TransactionDraft>,
        date: LocalDate = LocalDate.of(2026, 11, 5),
    ) = post(result.getOrThrow(), date)
}
