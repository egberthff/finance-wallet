package ph.financewallet.domain.money

/**
 * ISO 4217 alphabetic currency code, restricted to currencies the app explicitly supports.
 *
 * The minor-unit table is built in (not read from the platform) so results are identical on every
 * device and JVM. A unit test cross-checks it against `java.util.Currency` on the build JVM.
 */
@JvmInline
public value class CurrencyCode private constructor(
    public val code: String,
) {
    /** Number of minor-unit digits (PHP = 2, JPY = 0, KWD = 3). */
    public val minorDigits: Int get() = MINOR_DIGITS.getValue(code)

    override fun toString(): String = code

    public companion object {
        private val MINOR_DIGITS: Map<String, Int> =
            mapOf(
                "PHP" to 2,
                "USD" to 2,
                "EUR" to 2,
                "GBP" to 2,
                "CHF" to 2,
                "JPY" to 0,
                "KRW" to 0,
                "VND" to 0,
                "CNY" to 2,
                "HKD" to 2,
                "TWD" to 2,
                "SGD" to 2,
                "MYR" to 2,
                "THB" to 2,
                "IDR" to 2,
                "INR" to 2,
                "AUD" to 2,
                "NZD" to 2,
                "CAD" to 2,
                "AED" to 2,
                "SAR" to 2,
                "QAR" to 2,
                "KWD" to 3,
                "BHD" to 3,
                "OMR" to 3,
            )

        /** All supported codes, sorted. */
        public val supported: List<String> = MINOR_DIGITS.keys.sorted()

        public val PHP: CurrencyCode = CurrencyCode("PHP")

        /** Returns the code if supported (input is trimmed and upper-cased), otherwise null. */
        public fun parseOrNull(code: String): CurrencyCode? {
            val normalized = code.trim().uppercase()
            return if (normalized in MINOR_DIGITS) CurrencyCode(normalized) else null
        }

        /** Like [parseOrNull] but throws [IllegalArgumentException] for unsupported codes. */
        public fun of(code: String): CurrencyCode = requireNotNull(parseOrNull(code)) { "Unsupported currency code: '$code'" }
    }
}
