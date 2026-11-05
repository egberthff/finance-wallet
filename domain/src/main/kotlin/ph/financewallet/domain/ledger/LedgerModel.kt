package ph.financewallet.domain.ledger

import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** Ledger account classes (docs/10-domain-model.md §2). ASSET and LIABILITY are real; the rest are nominal. */
public enum class AccountClass {
    ASSET,
    LIABILITY,
    INCOME,
    EXPENSE,
    EQUITY,
    ;

    public val isReal: Boolean get() = this == ASSET || this == LIABILITY
}

/** User-facing kinds of real accounts (PRD ACC-01). */
public enum class FinancialAccountType(
    public val accountClass: AccountClass,
) {
    CASH(AccountClass.ASSET),
    BANK(AccountClass.ASSET),
    EWALLET(AccountClass.ASSET),
    SAVINGS(AccountClass.ASSET),
    CREDIT_CARD(AccountClass.LIABILITY),
    LOAN(AccountClass.LIABILITY),
    BORROWED(AccountClass.LIABILITY),
    OTHER(AccountClass.ASSET),
}

/** System ledger accounts created at first run (docs/10-domain-model.md §2). */
public enum class SystemAccount(
    public val key: String,
    public val accountClass: AccountClass,
) {
    OPENING("equity:opening", AccountClass.EQUITY),
    ADJUSTMENT("equity:adjustment", AccountClass.EQUITY),
    FX("equity:fx", AccountClass.EQUITY),
    INTEREST_FEES("expense:interest_fees", AccountClass.EXPENSE),
    INCOME_UNCATEGORIZED("income:uncategorized", AccountClass.INCOME),
    EXPENSE_UNCATEGORIZED("expense:uncategorized", AccountClass.EXPENSE),
}

/** What the domain needs to know about a ledger account. [currency] is set for real accounts only. */
public data class LedgerAccount(
    public val id: String,
    public val accountClass: AccountClass,
    public val currency: CurrencyCode?,
    public val type: FinancialAccountType? = null,
    public val systemAccount: SystemAccount? = null,
    public val archived: Boolean = false,
) {
    init {
        require(!accountClass.isReal || (currency != null && type != null)) { "real accounts need a currency and a type" }
        require(type == null || type.accountClass == accountClass) { "type $type does not match class $accountClass" }
    }
}

/** Lookup of ledger accounts by id and of the system accounts. Implemented by the data layer. */
public interface AccountDirectory {
    public fun find(id: String): LedgerAccount?

    public fun system(account: SystemAccount): LedgerAccount
}

/** Transaction types (PRD TXN-01, docs/10-domain-model.md §3.3). */
public enum class TxnType {
    INCOME,
    EXPENSE,
    TRANSFER,
    SAVINGS_CONTRIBUTION,
    SAVINGS_WITHDRAWAL,
    REFUND,
    REVERSAL,
    BALANCE_ADJUSTMENT,
    DEBT_PAYMENT,
    DEBT_DISBURSEMENT,
    OPENING_BALANCE,
}

public enum class TxnStatus { PENDING, POSTED }

/** One signed posting. Positive = debit (asset/expense increase), negative = credit. */
public data class Entry(
    public val accountId: String,
    public val amount: Money,
)

/** Output of an entry template: a balanced, shape-checked set of entries for one transaction. */
public data class TransactionDraft(
    public val type: TxnType,
    public val entries: List<Entry>,
    public val reversesTxnId: String? = null,
    public val refundOfTxnId: String? = null,
)

/** A stored transaction as the calculators see it (header fields irrelevant to arithmetic are omitted). */
public data class LedgerTransaction(
    public val id: String,
    public val type: TxnType,
    public val status: TxnStatus,
    public val txnDate: LocalDate,
    public val entries: List<Entry>,
    public val deleted: Boolean = false,
    public val reversesTxnId: String? = null,
    public val refundOfTxnId: String? = null,
) {
    /** Counts toward posted balances and reports (I-5). */
    public val isEffective: Boolean get() = status == TxnStatus.POSTED && !deleted
}
