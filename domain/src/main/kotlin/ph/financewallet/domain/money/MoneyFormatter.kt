package ph.financewallet.domain.money

import java.math.BigInteger

/**
 * Deterministic display formatting (PRD UX-06): `₱1,234.56`, negatives as `−₱1,234.56` (U+2212).
 * Non-PHP currencies are prefixed with their ISO code: `USD 1,234.56`.
 */
public object MoneyFormatter {
    private const val MINUS = "−"
    private const val PESO = "₱"

    public fun format(
        money: Money,
        withSymbol: Boolean = true,
    ): String {
        val digits = money.currency.minorDigits
        val magnitude = BigInteger.valueOf(money.minor).abs()
        val divisor = BigInteger.TEN.pow(digits)
        val (whole, fraction) = magnitude.divideAndRemainder(divisor)

        val grouped =
            whole
                .toString()
                .reversed()
                .chunked(3)
                .joinToString(",")
                .reversed()
        val number = if (digits == 0) grouped else grouped + "." + fraction.toString().padStart(digits, '0')
        val prefix =
            when {
                !withSymbol -> ""
                money.currency == CurrencyCode.PHP -> PESO
                else -> money.currency.code + " "
            }
        val sign = if (money.minor < 0) MINUS else ""
        return sign + prefix + number
    }
}
