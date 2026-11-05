package ph.financewallet.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The single place where fractional monetary math is allowed (docs/10-domain-model.md §1.4).
 * Rates are integer basis points (1 bp = 0.01 %).
 */
public object RateMath {
    public const val BP_PER_UNIT: Long = 10_000L

    /** Returns `amount × bp / 10 000` rounded with [rounding] (default HALF_EVEN). */
    public fun applyBp(
        amount: Money,
        bp: Long,
        rounding: RoundingMode = RoundingMode.HALF_EVEN,
    ): Money {
        val result =
            BigDecimal
                .valueOf(amount.minor)
                .multiply(BigDecimal.valueOf(bp))
                .divide(BigDecimal.valueOf(BP_PER_UNIT), 0, rounding)
        return Money(result.longValueExact(), amount.currency)
    }
}
