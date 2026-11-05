package ph.financewallet.app.security

/** Persisted failure state for PIN attempts. */
data class LockoutState(val consecutiveFailures: Int = 0, val lockedUntilMillis: Long = 0L)

/**
 * Exponential backoff after repeated wrong PINs (docs/16-security-architecture.md T9):
 * the first [FREE_ATTEMPTS] failures are free; each further failure locks input for
 * 30 s × 2^(n−1), capped at 1 hour. No auto-wipe (data-loss risk).
 */
object LockoutPolicy {
    const val FREE_ATTEMPTS: Int = 5
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 3_600_000L

    fun isLocked(state: LockoutState, nowMillis: Long): Boolean = nowMillis < state.lockedUntilMillis

    fun remainingMillis(state: LockoutState, nowMillis: Long): Long = (state.lockedUntilMillis - nowMillis).coerceAtLeast(0L)

    fun onFailure(state: LockoutState, nowMillis: Long): LockoutState {
        val failures = state.consecutiveFailures + 1
        val overLimit = failures - FREE_ATTEMPTS
        if (overLimit <= 0) return LockoutState(failures, 0L)
        val delay = delayFor(overLimit)
        return LockoutState(failures, nowMillis + delay)
    }

    fun onSuccess(): LockoutState = LockoutState()

    internal fun delayFor(overLimit: Int): Long {
        var delay = BASE_DELAY_MS
        repeat(overLimit - 1) {
            delay *= 2
            if (delay >= MAX_DELAY_MS) return MAX_DELAY_MS
        }
        return delay
    }
}
