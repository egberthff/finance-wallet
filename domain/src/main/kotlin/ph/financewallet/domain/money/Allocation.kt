package ph.financewallet.domain.money

import java.math.BigInteger

/**
 * Deterministic proportional splitting with largest-remainder rounding (docs/10-domain-model.md §1.3).
 *
 * Guarantees: the shares sum exactly to the total, every share has the sign of the total (or is zero),
 * and the result is identical for identical input on every device.
 */
public object Allocation {
    /**
     * Splits [total] minor units proportionally to [weights].
     *
     * Ties in the remainder go to the smallest key. Keys must be distinct, weights must be >= 0, and
     * at least one weight must be > 0. Returns shares in the input order.
     */
    public fun <K : Comparable<K>> split(
        total: Long,
        weights: List<Pair<K, Long>>,
    ): LinkedHashMap<K, Long> {
        require(weights.isNotEmpty()) { "weights must not be empty" }
        require(weights.map { it.first }.toSet().size == weights.size) { "keys must be distinct" }
        require(weights.all { it.second >= 0 }) { "weights must be >= 0" }
        val weightSum = weights.fold(BigInteger.ZERO) { acc, w -> acc + BigInteger.valueOf(w.second) }
        require(weightSum.signum() > 0) { "at least one weight must be > 0" }

        val magnitude = BigInteger.valueOf(total).abs()
        val quotients = LongArray(weights.size)
        val remainders = arrayOfNulls<BigInteger>(weights.size)
        var distributed = BigInteger.ZERO
        weights.forEachIndexed { i, (_, w) ->
            val (q, r) = (magnitude * BigInteger.valueOf(w)).divideAndRemainder(weightSum)
            quotients[i] = q.longValueExact()
            remainders[i] = r
            distributed += q
        }
        val leftover = (magnitude - distributed).intValueExact() // 0 <= leftover < weights.size

        val order =
            weights.indices.sortedWith(
                compareByDescending<Int> { remainders[it]!! }.thenBy { weights[it].first },
            )
        for (k in 0 until leftover) quotients[order[k]] += 1

        val sign = if (total < 0) -1L else 1L
        val result = LinkedHashMap<K, Long>(weights.size)
        weights.forEachIndexed { i, (key, _) -> result[key] = Math.multiplyExact(quotients[i], sign) }
        return result
    }

    /** Splits a [Money] amount; see [split]. */
    public fun <K : Comparable<K>> split(
        total: Money,
        weights: List<Pair<K, Long>>,
    ): LinkedHashMap<K, Money> {
        val shares = split(total.minor, weights)
        return shares.mapValuesTo(LinkedHashMap(shares.size)) { Money(it.value, total.currency) }
    }
}
