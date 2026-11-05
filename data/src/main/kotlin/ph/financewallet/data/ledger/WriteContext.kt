package ph.financewallet.data.ledger

import ph.financewallet.domain.id.IdGenerator
import java.time.Clock
import java.time.ZoneId

/**
 * Everything a write needs besides data: time, ids, the device identity, and the user's zone. Injected so tests
 * are deterministic (docs/18-coding-standards.md §4).
 */
class WriteContext(
    val clock: Clock,
    val ids: IdGenerator,
    val deviceId: String,
    val zone: ZoneId = ZoneId.of("Asia/Manila"),
) {
    private var lastHlcMillis = 0L
    private var hlcCounter = 0

    fun nowMillis(): Long = clock.millis()

    /**
     * Hybrid logical clock stamp `{millis:013}-{counter:05}-{device}` (ADR-0007). Monotonic per device even if the
     * wall clock moves backwards; compared lexicographically.
     */
    @Synchronized
    fun nextHlc(): String {
        val now = clock.millis()
        if (now > lastHlcMillis) {
            lastHlcMillis = now
            hlcCounter = 0
        } else {
            hlcCounter++
        }
        return "%013d-%05d-%s".format(lastHlcMillis, hlcCounter, deviceId)
    }
}

/** Test-only fault injection points for crash-atomicity tests (AC-CORE-9). Production uses [NONE]. */
fun interface FaultInjector {
    fun at(checkpoint: String)

    companion object {
        val NONE = FaultInjector { }
    }
}
