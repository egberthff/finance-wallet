package ph.financewallet.domain.ledger

import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** Classified totals for one currency over a date range (docs/10-domain-model.md §3.4). */
public data class PeriodSummary(
    public val currency: CurrencyCode,
    /** −Σ entries on INCOME accounts. */
    public val income: Money,
    /** Σ entries on EXPENSE accounts (refunds and reversals already reduce it). */
    public val spending: Money,
    /** −Σ entries on `equity:adjustment`: shown separately, never income or spending. */
    public val adjustments: Money,
    /** −Σ entries on `equity:opening`. */
    public val openings: Money,
    /** −Σ entries on `equity:fx` (cross-currency transfers). */
    public val fx: Money,
)

/**
 * Reference implementation of balances and report classification over in-memory transactions.
 *
 * It is the specification: the data layer computes the same figures with SQL aggregates, and tests cross-check both.
 * Only effective (POSTED, not deleted) transactions count, except for [pendingBalance].
 */
public class LedgerCalculator(
    private val accounts: AccountDirectory,
) {
    /** Balance of a real account including transactions dated on or before [asOf] (I-5). */
    public fun balance(
        accountId: String,
        asOf: LocalDate,
        transactions: Iterable<LedgerTransaction>,
    ): Money {
        val currency = requireNotNull(realAccount(accountId).currency)
        val minor =
            transactions
                .filter { it.isEffective && !it.txnDate.isAfter(asOf) }
                .flatMap { it.entries }
                .filter { it.accountId == accountId }
                .fold(0L) { acc, e -> Math.addExact(acc, e.amount.minor) }
        return Money(minor, currency)
    }

    /** Σ entries of PENDING, non-deleted transactions on the account (ACC-05). */
    public fun pendingBalance(
        accountId: String,
        transactions: Iterable<LedgerTransaction>,
    ): Money {
        val currency = requireNotNull(realAccount(accountId).currency)
        val minor =
            transactions
                .filter { it.status == TxnStatus.PENDING && !it.deleted }
                .flatMap { it.entries }
                .filter { it.accountId == accountId }
                .fold(0L) { acc, e -> Math.addExact(acc, e.amount.minor) }
        return Money(minor, currency)
    }

    /** Income, spending, adjustments, openings and FX per currency for [from]..[to] inclusive. */
    public fun summary(
        from: LocalDate,
        to: LocalDate,
        transactions: Iterable<LedgerTransaction>,
    ): Map<CurrencyCode, PeriodSummary> {
        require(!to.isBefore(from)) { "to must not be before from" }
        val sums = HashMap<Pair<CurrencyCode, Bucket>, Long>()
        transactions
            .filter { it.isEffective && !it.txnDate.isBefore(from) && !it.txnDate.isAfter(to) }
            .flatMap { it.entries }
            .forEach { e ->
                val bucket = bucketOf(e.accountId) ?: return@forEach
                val key = e.amount.currency to bucket
                sums[key] = Math.addExact(sums[key] ?: 0L, e.amount.minor)
            }
        return sums.keys.map { it.first }.distinct().sortedBy { it.code }.associateWith { c ->
            fun total(b: Bucket): Long = sums[c to b] ?: 0L
            PeriodSummary(
                currency = c,
                income = Money(Math.negateExact(total(Bucket.INCOME)), c),
                spending = Money(total(Bucket.SPENDING), c),
                adjustments = Money(Math.negateExact(total(Bucket.ADJUSTMENT)), c),
                openings = Money(Math.negateExact(total(Bucket.OPENING)), c),
                fx = Money(Math.negateExact(total(Bucket.FX)), c),
            )
        }
    }

    /** Spending per expense category for [from]..[to] inclusive (a category with net zero is omitted). */
    public fun spendingByCategory(
        from: LocalDate,
        to: LocalDate,
        transactions: Iterable<LedgerTransaction>,
    ): Map<String, Money> =
        transactions
            .filter { it.isEffective && !it.txnDate.isBefore(from) && !it.txnDate.isAfter(to) }
            .flatMap { it.entries }
            .filter { accounts.find(it.accountId)?.accountClass == AccountClass.EXPENSE }
            .groupBy { it.accountId }
            .mapValues { (_, es) -> es.map { it.amount }.reduce { a, b -> a + b } }
            .filterValues { !it.isZero }

    /**
     * Net worth per currency (NW-01): Σ balances of the given real accounts as of [asOf]. Liabilities are negative,
     * so they subtract. Internal transfers between included accounts net to zero.
     */
    public fun netWorth(
        accountIds: Collection<String>,
        asOf: LocalDate,
        transactions: Iterable<LedgerTransaction>,
    ): Map<CurrencyCode, Money> {
        val txns = transactions.toList()
        return accountIds
            .map { balance(it, asOf, txns) }
            .groupBy { it.currency }
            .mapValues { (c, balances) -> Money.sum(balances, c) }
    }

    private enum class Bucket { INCOME, SPENDING, ADJUSTMENT, OPENING, FX }

    private fun bucketOf(accountId: String): Bucket? {
        val account = accounts.find(accountId) ?: error("unknown account $accountId")
        return when (account.accountClass) {
            AccountClass.INCOME -> {
                Bucket.INCOME
            }

            AccountClass.EXPENSE -> {
                Bucket.SPENDING
            }

            AccountClass.EQUITY -> {
                when (account.systemAccount) {
                    SystemAccount.ADJUSTMENT -> Bucket.ADJUSTMENT
                    SystemAccount.OPENING -> Bucket.OPENING
                    SystemAccount.FX -> Bucket.FX
                    else -> error("unexpected equity account $accountId")
                }
            }

            AccountClass.ASSET, AccountClass.LIABILITY -> {
                null
            }
        }
    }

    private fun realAccount(id: String): LedgerAccount {
        val account = requireNotNull(accounts.find(id)) { "unknown account $id" }
        require(account.accountClass.isReal) { "$id is not a real account" }
        return account
    }
}
