package ph.financewallet.domain.allocation

import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money

/** A savings goal and the real accounts it is linked to (empty = virtual goal). */
public data class GoalLink(
    public val goalId: String,
    public val accountIds: Set<String>,
)

/**
 * Linked-account cap (SAV-03, EX-17). Goals that share any linked account form a group (connected components over
 * shared accounts). Within a group, the total allocated to its goals may not exceed the total balance of its accounts.
 * This prevents earmarking more than the money that physically exists, and therefore any double counting.
 */
public class LinkedGoalCap(
    links: List<GoalLink>,
    private val allocated: (goalId: String) -> Money,
    private val accountBalance: (accountId: String) -> Money,
) {
    private val linksById = links.associateBy { it.goalId }
    private val groupOf: Map<String, Set<String>> = buildGroups(links)

    /** Remaining room for [goalId], or null when the goal is not linked to any account (no cap). */
    public fun headroom(
        goalId: String,
        currency: CurrencyCode,
    ): Money? {
        val group = groupOf[goalId] ?: return null
        val accounts = group.flatMap { linksById.getValue(it).accountIds }.toSet()
        val capacity = Money.sum(accounts.map(accountBalance), currency)
        val used = Money.sum(group.map(allocated), currency)
        return capacity - used
    }

    private fun buildGroups(links: List<GoalLink>): Map<String, Set<String>> {
        val linked = links.filter { it.accountIds.isNotEmpty() }
        val parent = linked.associate { it.goalId to it.goalId }.toMutableMap()

        fun root(x: String): String {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            return r
        }
        val firstGoalOfAccount = mutableMapOf<String, String>()
        linked.forEach { link ->
            link.accountIds.forEach { account ->
                val other = firstGoalOfAccount.putIfAbsent(account, link.goalId)
                if (other != null) parent[root(link.goalId)] = root(other)
            }
        }
        val groups = linked.groupBy({ root(it.goalId) }, { it.goalId }).values.map { it.toSet() }
        return groups.flatMap { g -> g.map { it to g } }.toMap()
    }
}
