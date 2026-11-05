package ph.financewallet.domain.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.RoundingMode
import java.util.Currency

class MoneyTest {
    private val php = CurrencyCode.PHP
    private val usd = CurrencyCode.of("USD")

    private fun p(minor: Long) = Money(minor, php)

    @Test
    fun `arithmetic is exact`() {
        assertEquals(p(300), p(100) + p(200))
        assertEquals(p(-100), p(100) - p(200))
        assertEquals(p(-5), -p(5))
        assertEquals(p(1500), p(500) * 3)
        assertEquals(p(7), p(-7).abs())
        assertEquals(p(7), p(7).abs())
        assertEquals(p(600), Money.sum(listOf(p(100), p(200), p(300)), php))
        assertEquals(Money.zero(php), Money.sum(emptyList(), php))
    }

    @Test
    fun `predicates and ordering`() {
        assertTrue(p(0).isZero)
        assertTrue(p(1).isPositive)
        assertTrue(p(-1).isNegative)
        assertFalse(p(1).isNegative)
        assertTrue(p(1) < p(2))
    }

    @Test
    fun `overflow throws instead of wrapping`() {
        assertThrows<ArithmeticException> { p(Long.MAX_VALUE) + p(1) }
        assertThrows<ArithmeticException> { p(Long.MIN_VALUE) - p(1) }
        assertThrows<ArithmeticException> { -p(Long.MIN_VALUE) }
        assertThrows<ArithmeticException> { p(Long.MAX_VALUE) * 2 }
    }

    @Test
    fun `mixing currencies is rejected`() {
        assertThrows<CurrencyMismatchException> { p(1) + Money(1, usd) }
        assertThrows<CurrencyMismatchException> { p(1) - Money(1, usd) }
        assertThrows<CurrencyMismatchException> { p(1).compareTo(Money(1, usd)) }
    }

    @Test
    fun `currency codes are validated and normalized`() {
        assertEquals(php, CurrencyCode.of(" php "))
        assertNull(CurrencyCode.parseOrNull("XYZ"))
        assertThrows<IllegalArgumentException> { CurrencyCode.of("ZZZ") }
        assertEquals(2, php.minorDigits)
        assertEquals(0, CurrencyCode.of("JPY").minorDigits)
        assertEquals(3, CurrencyCode.of("KWD").minorDigits)
        assertEquals("PHP", php.toString())
    }

    @Test
    fun `built-in minor-unit table matches the JVM ISO 4217 data`() {
        CurrencyCode.supported.forEach { code ->
            assertEquals(
                Currency.getInstance(code).defaultFractionDigits,
                CurrencyCode.of(code).minorDigits,
                "minor digits for $code",
            )
        }
    }

    @Test
    fun `EX-4 2_5 percent of 1234_56 pesos is 30_86 with HALF_EVEN`() {
        assertEquals(p(3086), RateMath.applyBp(p(123_456), 250))
    }

    @Test
    fun `rate rounding modes are explicit`() {
        // 0.5 centavo cases: 25 × 2000 bp = 5.0 exactly; 5 × 1000 bp = 0.5 → HALF_EVEN 0, HALF_UP 1
        assertEquals(p(5), RateMath.applyBp(p(25), 2000))
        assertEquals(p(0), RateMath.applyBp(p(5), 1000))
        assertEquals(p(1), RateMath.applyBp(p(5), 1000, RoundingMode.HALF_UP))
        assertEquals(p(-1), RateMath.applyBp(p(-5), 1000, RoundingMode.HALF_UP))
    }
}
