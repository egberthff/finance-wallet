package ph.financewallet.app.security

/** UI-facing lock state (SEC-01). */
sealed interface LockState {
    /** No PIN yet: user must create one. [mismatch] = the previous confirmation did not match. */
    data class NeedsSetup(val mismatch: Boolean = false) : LockState

    /** First PIN entered during setup; waiting for confirmation. */
    data object ConfirmSetup : LockState

    data class Locked(val wrongPin: Boolean = false, val lockedUntilMillis: Long = 0L) : LockState

    data object Unlocked : LockState
}

/**
 * App-lock state machine. Synchronous and free of Android dependencies so it is unit-testable;
 * callers run [submitPin] off the main thread because PBKDF2 is intentionally slow.
 *
 * Auto-lock: [onBackgrounded] / [onForegrounded] lock the app after [autoLockAfterMillis] in background.
 */
class AppLockController(
    private val store: PinStore,
    private val hasher: PinHasher = PinHasher(),
    private val now: () -> Long = System::currentTimeMillis,
    private val autoLockAfterMillis: Long = DEFAULT_AUTO_LOCK_MS,
) {
    private var pendingPin: CharArray? = null
    private var backgroundedAt: Long? = null

    @Volatile
    var state: LockState = if (store.hasPin()) LockState.Locked() else LockState.NeedsSetup()
        private set

    /** Handles a complete 6-digit entry for whatever step the user is on. The [pin] array is wiped. */
    @Synchronized
    fun submitPin(pin: CharArray): LockState {
        try {
            state = when (val s = state) {
                is LockState.NeedsSetup -> {
                    if (!PinHasher.isValidPin(pin)) return s
                    pendingPin = pin.copyOf()
                    LockState.ConfirmSetup
                }
                LockState.ConfirmSetup -> confirmSetup(pin)
                is LockState.Locked -> unlock(pin, s)
                LockState.Unlocked -> s
            }
            return state
        } finally {
            pin.fill('\u0000')
        }
    }

    private fun confirmSetup(pin: CharArray): LockState {
        val first = pendingPin ?: return LockState.NeedsSetup()
        pendingPin = null
        val matches = first.contentEquals(pin)
        first.fill('\u0000')
        if (!matches) return LockState.NeedsSetup(mismatch = true)
        store.save(hasher.create(pin))
        store.saveLockout(LockoutPolicy.onSuccess())
        return LockState.Unlocked
    }

    private fun unlock(pin: CharArray, current: LockState.Locked): LockState {
        val lockout = store.loadLockout()
        val t = now()
        if (LockoutPolicy.isLocked(lockout, t)) return current.copy(lockedUntilMillis = lockout.lockedUntilMillis)
        val record = store.load() ?: return LockState.NeedsSetup()
        return if (hasher.verify(pin, record)) {
            store.saveLockout(LockoutPolicy.onSuccess())
            LockState.Unlocked
        } else {
            val next = LockoutPolicy.onFailure(lockout, t)
            store.saveLockout(next)
            LockState.Locked(wrongPin = true, lockedUntilMillis = next.lockedUntilMillis)
        }
    }

    /** Called after a successful BIOMETRIC_STRONG authentication. Does not bypass an active lockout. */
    @Synchronized
    fun onBiometricSuccess(): LockState {
        if (state is LockState.Locked && !LockoutPolicy.isLocked(store.loadLockout(), now())) {
            store.saveLockout(LockoutPolicy.onSuccess())
            state = LockState.Unlocked
        }
        return state
    }

    @Synchronized
    fun lockNow() {
        if (store.hasPin()) state = LockState.Locked()
    }

    @Synchronized
    fun onBackgrounded() {
        backgroundedAt = now()
    }

    @Synchronized
    fun onForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (state == LockState.Unlocked && now() - since >= autoLockAfterMillis) lockNow()
    }

    companion object {
        const val DEFAULT_AUTO_LOCK_MS: Long = 60_000L
    }
}
