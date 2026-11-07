package ph.financewallet.domain.ledger

import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.common.validating
import ph.financewallet.domain.money.Money

/** One category part of an income or expense. */
public data class Split(
    public val categoryId: String,
    public val amount: Money,
)

/** The expense being refunded plus the total already refunded against it (TXN-05). */
public data class RefundOriginal(
    public val transaction: LedgerTransaction,
    public val priorRefunds: Money,
)

/**
 * The only way to build transaction entries (docs/10-domain-model.md §3.3). Every template validates its input,
 * produces entries in a fixed line order, and checks invariants I-1..I-3 before returning.
 *
 * Validation codes are part of the contract with the UI and are covered by tests.
 *
 * Archived accounts and categories are rejected (`ACCOUNT_ARCHIVED`, `CATEGORY_ARCHIVED`) except for the ids in
 * [allowArchived]: an edit passes the ids its transaction already uses, so a transaction can still be corrected after
 * its category or account was archived, while archived ones can never be newly chosen (owner decision 2026-11-07,
 * docs/10-domain-model.md, UC-09).
 */
public class EntryTemplates(
    private val accounts: AccountDirectory,
    private val allowArchived: Set<String> = emptySet(),
) {
    public fun income(
        accountId: String,
        splits: List<Split>,
    ): DomainResult<TransactionDraft> =
        draft(TxnType.INCOME) {
            val account = realAccount(accountId, "accountId")
            val parts = checkedSplits(splits, account, AccountClass.INCOME)
            listOf(Entry(account.id, total(parts, account))) + parts.map { Entry(it.categoryId, -it.amount) }
        }

    public fun expense(
        accountId: String,
        splits: List<Split>,
    ): DomainResult<TransactionDraft> =
        draft(TxnType.EXPENSE) {
            val account = realAccount(accountId, "accountId")
            val parts = checkedSplits(splits, account, AccountClass.EXPENSE)
            listOf(Entry(account.id, -total(parts, account))) + parts.map { Entry(it.categoryId, it.amount) }
        }

    /** Same-currency transfer between two of the user's accounts: never income or spending (TXN-04). */
    public fun transfer(
        fromId: String,
        toId: String,
        amount: Money,
    ): DomainResult<TransactionDraft> = transferOf(TxnType.TRANSFER, fromId, toId, amount)

    /** Physical move into a savings account (SAV, domain §6). */
    public fun savingsContribution(
        fromId: String,
        toId: String,
        amount: Money,
    ): DomainResult<TransactionDraft> = transferOf(TxnType.SAVINGS_CONTRIBUTION, fromId, toId, amount)

    /** Physical move out of a savings account. */
    public fun savingsWithdrawal(
        fromId: String,
        toId: String,
        amount: Money,
    ): DomainResult<TransactionDraft> = transferOf(TxnType.SAVINGS_WITHDRAWAL, fromId, toId, amount)

    /** Transfer between accounts in different currencies; both amounts are entered by the user. */
    public fun crossCurrencyTransfer(
        fromId: String,
        fromAmount: Money,
        toId: String,
        toAmount: Money,
    ): DomainResult<TransactionDraft> =
        draft(TxnType.TRANSFER) {
            val from = realAccount(fromId, "fromId")
            val to = realAccount(toId, "toId")
            ensure(from.id != to.id, "toId", "SAME_ACCOUNT")
            ensure(from.currency != to.currency, "toId", "SAME_CURRENCY")
            positive(fromAmount, "fromAmount")
            positive(toAmount, "toAmount")
            matchesCurrency(fromAmount, from, "fromAmount")
            matchesCurrency(toAmount, to, "toAmount")
            val fx = accounts.system(SystemAccount.FX).id
            listOf(Entry(from.id, -fromAmount), Entry(fx, fromAmount), Entry(fx, -toAmount), Entry(to.id, toAmount))
        }

    /**
     * Refund (TXN-05). Linked to [original] when known: the category defaults to the original's, and total refunds
     * may not exceed the original spending. A refund reduces spending; it is never income.
     */
    public fun refund(
        accountId: String,
        amount: Money,
        categoryId: String? = null,
        original: RefundOriginal? = null,
    ): DomainResult<TransactionDraft> =
        validating {
            val account = realAccount(accountId, "accountId")
            positive(amount, "amount")
            matchesCurrency(amount, account, "amount")
            val refundedCategory =
                if (original == null) {
                    category(categoryId ?: fail("categoryId", "REFUND_CATEGORY_REQUIRED"), AccountClass.EXPENSE)
                } else {
                    refundCategory(original, amount, categoryId)
                }
            val entries = listOf(Entry(account.id, amount), Entry(refundedCategory.id, -amount))
            checkInvariants(entries)
            TransactionDraft(TxnType.REFUND, entries, refundOfTxnId = original?.transaction?.id)
        }

    /** Exact negation of [original] (TXN-08). A reversal cannot itself be reversed. */
    public fun reversal(
        original: LedgerTransaction,
        alreadyReversed: Boolean,
    ): DomainResult<TransactionDraft> =
        validating {
            ensure(original.type != TxnType.REVERSAL, "original", "REVERSAL_OF_REVERSAL")
            ensure(original.isEffective, "original", "ORIGINAL_NOT_EFFECTIVE")
            ensure(!alreadyReversed, "original", "ALREADY_REVERSED")
            val entries = original.entries.map { Entry(it.accountId, -it.amount) }
            checkInvariants(entries)
            TransactionDraft(TxnType.REVERSAL, entries, reversesTxnId = original.id)
        }

    /** Signed correction of an account balance against `equity:adjustment` (ACC-06). */
    public fun balanceAdjustment(
        accountId: String,
        delta: Money,
    ): DomainResult<TransactionDraft> = againstEquity(TxnType.BALANCE_ADJUSTMENT, SystemAccount.ADJUSTMENT, accountId, delta)

    /** Opening balance against `equity:opening`. Liabilities owed are negative. */
    public fun openingBalance(
        accountId: String,
        amount: Money,
    ): DomainResult<TransactionDraft> = againstEquity(TxnType.OPENING_BALANCE, SystemAccount.OPENING, accountId, amount)

    /** Borrowed money arrives in an asset account (DEBT-03): not income. */
    public fun debtDisbursement(
        assetId: String,
        liabilityId: String,
        amount: Money,
    ): DomainResult<TransactionDraft> =
        draft(TxnType.DEBT_DISBURSEMENT) {
            val (asset, liability) = assetAndLiability(assetId, liabilityId)
            positive(amount, "amount")
            matchesCurrency(amount, asset, "amount")
            listOf(Entry(asset.id, amount), Entry(liability.id, -amount))
        }

    /**
     * Debt payment split into principal (reduces the liability, not spending) and interest/fees (spending)
     * (DEBT-02). [feeCategoryId] defaults to the system `expense:interest_fees` category.
     */
    public fun debtPayment(
        assetId: String,
        liabilityId: String,
        principal: Money,
        interest: Money,
        fees: Money,
        feeCategoryId: String? = null,
    ): DomainResult<TransactionDraft> =
        draft(TxnType.DEBT_PAYMENT) {
            val (asset, liability) = assetAndLiability(assetId, liabilityId)
            listOf(principal to "principal", interest to "interest", fees to "fees").forEach { (m, field) ->
                ensure(!m.isNegative, field, "AMOUNT_NEGATIVE")
                matchesCurrency(m, asset, field)
            }
            val charges = interest + fees
            val totalPaid = principal + charges
            ensure(totalPaid.isPositive, "principal", "AMOUNT_NOT_POSITIVE")
            val category = feeCategoryId?.let { category(it, AccountClass.EXPENSE) } ?: accounts.system(SystemAccount.INTEREST_FEES)
            buildList {
                add(Entry(asset.id, -totalPaid))
                if (principal.isPositive) add(Entry(liability.id, principal))
                if (charges.isPositive) add(Entry(category.id, charges))
            }
        }

    // ---- shared building blocks -------------------------------------------------------------------------------

    private fun draft(
        type: TxnType,
        build: ValidationScope.() -> List<Entry>,
    ): DomainResult<TransactionDraft> =
        validating {
            val entries = build()
            checkInvariants(entries)
            TransactionDraft(type, entries)
        }

    private fun transferOf(
        type: TxnType,
        fromId: String,
        toId: String,
        amount: Money,
    ): DomainResult<TransactionDraft> =
        draft(type) {
            val from = realAccount(fromId, "fromId")
            val to = realAccount(toId, "toId")
            ensure(from.id != to.id, "toId", "SAME_ACCOUNT")
            ensure(from.currency == to.currency, "toId", "CURRENCY_MISMATCH")
            if (type == TxnType.SAVINGS_CONTRIBUTION) ensure(to.type == FinancialAccountType.SAVINGS, "toId", "NOT_SAVINGS_ACCOUNT")
            if (type == TxnType.SAVINGS_WITHDRAWAL) ensure(from.type == FinancialAccountType.SAVINGS, "fromId", "NOT_SAVINGS_ACCOUNT")
            positive(amount, "amount")
            matchesCurrency(amount, from, "amount")
            listOf(Entry(from.id, -amount), Entry(to.id, amount))
        }

    private fun againstEquity(
        type: TxnType,
        equity: SystemAccount,
        accountId: String,
        amount: Money,
    ): DomainResult<TransactionDraft> =
        draft(type) {
            val account = realAccount(accountId, "accountId")
            ensure(!amount.isZero, "amount", "AMOUNT_ZERO")
            matchesCurrency(amount, account, "amount")
            listOf(Entry(account.id, amount), Entry(accounts.system(equity).id, -amount))
        }

    private fun ValidationScope.refundCategory(
        original: RefundOriginal,
        amount: Money,
        categoryId: String?,
    ): LedgerAccount {
        val tx = original.transaction
        ensure(tx.type == TxnType.EXPENSE, "original", "REFUND_ORIGINAL_NOT_EXPENSE")
        ensure(tx.isEffective, "original", "ORIGINAL_NOT_EFFECTIVE")
        val spent = tx.entries.filter { accounts.find(it.accountId)?.accountClass == AccountClass.EXPENSE }
        ensure(spent.all { it.amount.currency == amount.currency }, "amount", "CURRENCY_MISMATCH")
        ensure(original.priorRefunds.currency == amount.currency, "amount", "CURRENCY_MISMATCH")
        val spentTotal = Money.sum(spent.map { it.amount }, amount.currency)
        ensure(original.priorRefunds + amount <= spentTotal, "amount", "REFUND_EXCEEDS_ORIGINAL")
        val originalCategories = spent.map { it.accountId }.distinct()
        val chosen = categoryId ?: originalCategories.singleOrNull() ?: fail("categoryId", "REFUND_CATEGORY_REQUIRED")
        ensure(chosen in originalCategories, "categoryId", "REFUND_CATEGORY_NOT_IN_ORIGINAL")
        return category(chosen, AccountClass.EXPENSE)
    }

    private fun ValidationScope.assetAndLiability(
        assetId: String,
        liabilityId: String,
    ): Pair<LedgerAccount, LedgerAccount> {
        val asset = realAccount(assetId, "assetId")
        val liability = realAccount(liabilityId, "liabilityId")
        ensure(asset.accountClass == AccountClass.ASSET, "assetId", "ACCOUNT_NOT_ASSET")
        ensure(liability.accountClass == AccountClass.LIABILITY, "liabilityId", "ACCOUNT_NOT_LIABILITY")
        ensure(asset.currency == liability.currency, "liabilityId", "CURRENCY_MISMATCH")
        return asset to liability
    }

    private fun ValidationScope.checkedSplits(
        splits: List<Split>,
        account: LedgerAccount,
        expected: AccountClass,
    ): List<Split> {
        ensure(splits.isNotEmpty(), "splits", "SPLITS_EMPTY")
        ensure(splits.map { it.categoryId }.toSet().size == splits.size, "splits", "DUPLICATE_CATEGORY")
        splits.forEach { split ->
            category(split.categoryId, expected)
            positive(split.amount, "splits")
            matchesCurrency(split.amount, account, "splits")
        }
        return splits
    }

    private fun total(
        parts: List<Split>,
        account: LedgerAccount,
    ): Money = Money.sum(parts.map { it.amount }, requireNotNull(account.currency))

    private fun ValidationScope.realAccount(
        id: String,
        field: String,
    ): LedgerAccount {
        val account = accounts.find(id) ?: notFound("account", id)
        ensure(account.accountClass.isReal, field, "ACCOUNT_NOT_REAL")
        ensure(!account.archived || account.id in allowArchived, field, "ACCOUNT_ARCHIVED")
        return account
    }

    private fun ValidationScope.category(
        id: String,
        expected: AccountClass,
    ): LedgerAccount {
        val account = accounts.find(id) ?: notFound("category", id)
        ensure(account.accountClass == expected, "categoryId", "CATEGORY_WRONG_CLASS")
        ensure(!account.archived || account.id in allowArchived, "categoryId", "CATEGORY_ARCHIVED")
        return account
    }

    private fun ValidationScope.positive(
        amount: Money,
        field: String,
    ) = ensure(amount.isPositive, field, "AMOUNT_NOT_POSITIVE")

    private fun ValidationScope.matchesCurrency(
        amount: Money,
        account: LedgerAccount,
        field: String,
    ) = ensure(amount.currency == account.currency, field, "CURRENCY_MISMATCH")

    private fun ValidationScope.checkInvariants(entries: List<Entry>) = LedgerInvariants.check(entries, accounts).bind()
}
