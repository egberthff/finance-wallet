package ph.financewallet.domain.money

import java.math.BigDecimal

/** Why user-entered amount text was rejected. Mapped to string resources in the UI. */
public enum class AmountError {
    EMPTY,
    INVALID_FORMAT,
    NEGATIVE_NOT_ALLOWED,
    TOO_MANY_DECIMALS,
    TOO_LARGE,
}

public sealed interface AmountParseResult {
    public data class Ok(
        val money: Money,
    ) : AmountParseResult

    public data class Invalid(
        val error: AmountError,
    ) : AmountParseResult
}

/**
 * Parses amounts typed by users (en-PH conventions: `.` decimal separator, optional `,` grouping
 * in groups of three, optional `₱` / currency-code prefix).
 *
 * Never rounds: more decimals than the currency allows is an error (docs/10-domain-model.md §1.1).
 */
public object MoneyParser {
    private val GROUPED = Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""")
    private val PLAIN = Regex("""^\d+(\.\d+)?$|^\.\d+$""")

    /** Upper bound for user-entered amounts: 1 trillion major units, far below Long overflow. */
    private val MAX_MAJOR = BigDecimal("1000000000000")

    // Each early return is a distinct, user-visible rejection reason (AmountError); a single exit would obscure them.
    @Suppress("ReturnCount")
    public fun parse(
        text: String,
        currency: CurrencyCode,
        allowNegative: Boolean = false,
    ): AmountParseResult {
        var s = text.trim()
        if (s.isEmpty()) return AmountParseResult.Invalid(AmountError.EMPTY)

        var negative = false
        if (s.startsWith("-") || s.startsWith("−")) {
            negative = true
            s = s.substring(1).trim()
        }
        s = s.removePrefix("₱").removePrefix(currency.code).trim()
        if (s.isEmpty()) return AmountParseResult.Invalid(AmountError.INVALID_FORMAT)

        val digits =
            when {
                GROUPED.matches(s) -> s.replace(",", "")
                PLAIN.matches(s) -> s
                else -> return AmountParseResult.Invalid(AmountError.INVALID_FORMAT)
            }
        val value = BigDecimal(digits)
        if (value.scale() > currency.minorDigits) {
            return AmountParseResult.Invalid(AmountError.TOO_MANY_DECIMALS)
        }
        if (value > MAX_MAJOR) return AmountParseResult.Invalid(AmountError.TOO_LARGE)
        if (negative && value.signum() != 0 && !allowNegative) {
            return AmountParseResult.Invalid(AmountError.NEGATIVE_NOT_ALLOWED)
        }
        val minor = value.movePointRight(currency.minorDigits).longValueExact()
        return AmountParseResult.Ok(Money(if (negative) -minor else minor, currency))
    }
}
