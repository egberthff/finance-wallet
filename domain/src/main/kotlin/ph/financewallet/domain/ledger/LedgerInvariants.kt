package ph.financewallet.domain.ledger

import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.validating

/**
 * Structural invariants of every transaction (docs/10-domain-model.md §3.1). Checked by every template and by the
 * data layer before writing; the same rules are re-checked in the database by integrity checks IC-1..IC-3.
 */
public object LedgerInvariants {
    public const val MIN_ENTRIES: Int = 2

    public fun check(
        entries: List<Entry>,
        accounts: AccountDirectory,
    ): DomainResult<Unit> =
        validating {
            invariant(entries.size >= MIN_ENTRIES, "TOO_FEW_ENTRIES") { "${entries.size} entries" }
            entries.forEach { e ->
                invariant(!e.amount.isZero, "ZERO_ENTRY") { "zero amount on ${e.accountId}" }
                val account = accounts.find(e.accountId)
                invariant(account != null, "UNKNOWN_ACCOUNT") { e.accountId }
                if (account != null && account.accountClass.isReal) {
                    invariant(account.currency == e.amount.currency, "REAL_ACCOUNT_CURRENCY") {
                        "${e.amount.currency} on ${account.id} (${account.currency})"
                    }
                }
            }
            entries.groupBy { it.amount.currency }.forEach { (currency, group) ->
                val sum = group.fold(0L) { acc, e -> Math.addExact(acc, e.amount.minor) }
                invariant(sum == 0L, "UNBALANCED") { "$currency sums to $sum" }
            }
        }
}
