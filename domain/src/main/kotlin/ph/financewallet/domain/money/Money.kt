package ph.financewallet.domain.money

/** Thrown when two [Money] values with different currencies are combined. A programming error, not a user error. */
public class CurrencyMismatchException(left: CurrencyCode, right: CurrencyCode) :
    IllegalArgumentException("Currency mismatch: $left vs $right")

/**
 * An exact monetary amount in integer minor units (ADR-0003).
 *
 * All arithmetic is overflow-checked ([ArithmeticException] on overflow). There is intentionally no
 * multiplication or division by fractional values; use [Allocation.split] or [RateMath].
 */
public data class Money(public val minor: Long, public val currency: CurrencyCode) : Comparable<Money> {

    public operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minor, other.minor), currency)
    }

    public operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minor, other.minor), currency)
    }

    public operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)

    /** Multiplies by an integer quantity (e.g., number of installments). */
    public operator fun times(quantity: Long): Money = Money(Math.multiplyExact(minor, quantity), currency)

    public val isZero: Boolean get() = minor == 0L
    public val isPositive: Boolean get() = minor > 0L
    public val isNegative: Boolean get() = minor < 0L

    public fun abs(): Money = if (minor < 0) -this else this

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minor.compareTo(other.minor)
    }

    private fun requireSameCurrency(other: Money) {
        if (currency != other.currency) throw CurrencyMismatchException(currency, other.currency)
    }

    public companion object {
        public fun zero(currency: CurrencyCode): Money = Money(0L, currency)

        /** Sums amounts of one currency. Returns zero of [currency] for an empty list. */
        public fun sum(amounts: Iterable<Money>, currency: CurrencyCode): Money =
            amounts.fold(zero(currency)) { acc, m -> acc + m }
    }
}
