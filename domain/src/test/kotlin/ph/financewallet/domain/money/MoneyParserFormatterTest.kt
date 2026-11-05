package ph.financewallet.domain.money

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MoneyParserFormatterTest {
    private val php = CurrencyCode.PHP

    private fun ok(
        text: String,
        minor: Long,
        allowNegative: Boolean = false,
    ) = assertEquals(AmountParseResult.Ok(Money(minor, php)), MoneyParser.parse(text, php, allowNegative), "input '$text'")

    private fun bad(
        text: String,
        error: AmountError,
        currency: CurrencyCode = php,
    ) = assertEquals(AmountParseResult.Invalid(error), MoneyParser.parse(text, currency), "input '$text'")

    @Test
    fun `accepts en-PH amount formats`() {
        ok("1234.56", 123_456)
        ok("1,234.56", 123_456)
        ok("₱1,234.56", 123_456)
        ok("PHP 1,234.5", 123_450)
        ok("  12 ", 1_200)
        ok(".5", 50)
        ok("0", 0)
        ok("1,000,000", 100_000_000)
    }

    @Test
    fun `negatives only when allowed`() {
        bad("-5", AmountError.NEGATIVE_NOT_ALLOWED)
        ok("-5", -500, allowNegative = true)
        ok("−5.25", -525, allowNegative = true)
        ok("-0", 0)
    }

    @Test
    fun `never rounds and rejects malformed input`() {
        bad("1.234", AmountError.TOO_MANY_DECIMALS)
        bad("1.5", AmountError.TOO_MANY_DECIMALS, CurrencyCode.of("JPY"))
        bad("", AmountError.EMPTY)
        bad("   ", AmountError.EMPTY)
        bad("₱", AmountError.INVALID_FORMAT)
        bad("1,23.45", AmountError.INVALID_FORMAT)
        bad("12,3456", AmountError.INVALID_FORMAT)
        bad("1.2.3", AmountError.INVALID_FORMAT)
        bad("abc", AmountError.INVALID_FORMAT)
        bad("1e5", AmountError.INVALID_FORMAT)
        bad("1 000", AmountError.INVALID_FORMAT)
        bad("1000000000000.01", AmountError.TOO_LARGE)
    }

    @Test
    fun `formats PHP per UX-06`() {
        assertEquals("₱1,234.56", MoneyFormatter.format(Money(123_456, php)))
        assertEquals("−₱1,234.56", MoneyFormatter.format(Money(-123_456, php)))
        assertEquals("₱0.05", MoneyFormatter.format(Money(5, php)))
        assertEquals("₱0.00", MoneyFormatter.format(Money(0, php)))
        assertEquals("₱1,000,000.00", MoneyFormatter.format(Money(100_000_000, php)))
        assertEquals("999.99", MoneyFormatter.format(Money(99_999, php), withSymbol = false))
    }

    @Test
    fun `formats other currencies with code prefix and their precision`() {
        assertEquals("JPY 1,234", MoneyFormatter.format(Money(1_234, CurrencyCode.of("JPY"))))
        assertEquals("KWD 1.234", MoneyFormatter.format(Money(1_234, CurrencyCode.of("KWD"))))
        assertEquals("−USD 0.01", MoneyFormatter.format(Money(-1, CurrencyCode.of("USD"))))
    }

    @Test
    fun `formats extreme values without overflow`() {
        assertEquals("−₱92,233,720,368,547,758.08", MoneyFormatter.format(Money(Long.MIN_VALUE, php)))
    }

    @Test
    fun `property - format then parse round-trips`() =
        runBlocking<Unit> {
            checkAll(3_000, Arb.long(-99_999_999_999_999L..99_999_999_999_999L)) { minor ->
                val text = MoneyFormatter.format(Money(minor, php)).replace("−", "-")
                assertEquals(AmountParseResult.Ok(Money(minor, php)), MoneyParser.parse(text, php, allowNegative = true))
            }
        }
}
