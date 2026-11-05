package ph.financewallet.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.financewallet.data.security.KeyWrapper
import java.nio.file.Files

class AppLockControllerTest {
    private val identity =
        object : KeyWrapper {
            override fun wrap(plaintext: ByteArray) = plaintext.copyOf()

            override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
        }
    private var clock = 1_000_000L
    private val dir = Files.createTempDirectory("lock").toFile()

    private fun controller() =
        AppLockController(PinStore(dir, identity), PinHasher(iterations = 1_000), {
            clock
        }, autoLockAfterMillis = 60_000L)

    private fun AppLockController.enter(pin: String) = submitPin(pin.toCharArray())

    @Test
    fun `setup requires matching confirmation, then unlocks`() {
        val c = controller()
        assertEquals(LockState.NeedsSetup(), c.state)
        assertEquals(LockState.ConfirmSetup, c.enter("123456"))
        assertEquals(LockState.NeedsSetup(mismatch = true), c.enter("654321"))
        c.enter("123456")
        assertEquals(LockState.Unlocked, c.enter("123456"))
    }

    @Test
    fun `restart starts locked, wrong PIN is reported, right PIN unlocks`() {
        controller().apply {
            enter("111222")
            enter("111222")
        }
        val c = controller()
        assertEquals(LockState.Locked(), c.state)
        assertEquals(LockState.Locked(wrongPin = true, lockedUntilMillis = 0L), c.enter("000000"))
        assertEquals(LockState.Unlocked, c.enter("111222"))
    }

    @Test
    fun `lockout blocks even the correct PIN and biometrics until it expires`() {
        controller().apply {
            enter("111222")
            enter("111222")
        }
        val c = controller()
        repeat(6) { c.enter("000000") }
        val locked = c.state as LockState.Locked
        assertTrue(locked.lockedUntilMillis > clock)
        assertTrue(c.enter("111222") is LockState.Locked)
        assertTrue(c.onBiometricSuccess() is LockState.Locked)
        clock = locked.lockedUntilMillis
        assertEquals(LockState.Unlocked, c.enter("111222"))
    }

    @Test
    fun `lockout survives app restart`() {
        controller().apply {
            enter("111222")
            enter("111222")
        }
        val c = controller()
        repeat(6) { c.enter("000000") }
        assertTrue(controller().enter("111222") is LockState.Locked)
    }

    @Test
    fun `auto-lock after the background timeout only`() {
        val c = controller()
        c.enter("111222")
        c.enter("111222")
        c.onBackgrounded()
        clock += 59_999
        c.onForegrounded()
        assertEquals(LockState.Unlocked, c.state)
        c.onBackgrounded()
        clock += 60_000
        c.onForegrounded()
        assertEquals(LockState.Locked(), c.state)
        assertEquals(LockState.Unlocked, c.onBiometricSuccess())
    }

    @Test
    fun `submitted PIN buffers are wiped`() {
        val c = controller()
        val pin = "246810".toCharArray()
        c.submitPin(pin)
        assertTrue(pin.all { it == '\u0000' })
    }
}
