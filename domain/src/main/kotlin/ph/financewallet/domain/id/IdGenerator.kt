package ph.financewallet.domain.id

import java.security.SecureRandom
import java.time.Clock

/** Source of new entity ids. Injected so tests are deterministic (docs/18-coding-standards.md §4). */
public fun interface IdGenerator {
    public fun newId(): String
}

/**
 * UUID version 7 (RFC 9562): 48-bit Unix milliseconds, then 74 random bits.
 *
 * Ids from one generator are strictly increasing (RFC 9562 §6.2 method 1: a 12-bit counter seeded randomly
 * in `rand_a`; on counter overflow, the timestamp is advanced by 1 ms).
 */
public class UuidV7Generator(
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) : IdGenerator {
    private var lastMillis = -1L
    private var counter = 0

    @Synchronized
    override fun newId(): String {
        var millis = clock.millis()
        if (millis <= lastMillis) {
            millis = lastMillis
            counter++
            if (counter > MAX_COUNTER) {
                millis++
                counter = random.nextInt(COUNTER_SEED_BOUND)
            }
        } else {
            counter = random.nextInt(COUNTER_SEED_BOUND)
        }
        lastMillis = millis

        val msb = (millis shl 16) or (0x7L shl 12) or counter.toLong()
        val lsb = (random.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE // variant 0b10
        return format(msb, lsb)
    }

    private fun format(msb: Long, lsb: Long): String {
        val hex = String.format("%016x%016x", msb, lsb)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private companion object {
        const val MAX_COUNTER = 0xFFF

        /** Seed the counter in the lower half so that many ids per millisecond can be generated before overflow. */
        const val COUNTER_SEED_BOUND = 0x800
    }
}
