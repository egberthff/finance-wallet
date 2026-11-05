package ph.financewallet.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.financewallet.data.security.KeyWrapper
import java.nio.file.Files

class AppLockSecurityTest {
    // Low iteration count only to keep tests fast; production default is verified separately.
    private val hasher = PinHasher(iterations = 1_000)

    @Test
    fun `correct PIN verifies, wrong PIN does not`() {
        val record = hasher.create("482913".toCharArray())
        assertTrue(hasher.verify("482913".toCharArray(), record))
        assertFalse(hasher.verify("482914".toCharArray(), record))
        assertFalse(hasher.verify("48291".toCharArray(), record))
    }

    @Test
    fun `same PIN gets different salts and hashes`() {
        val a = hasher.create("111111".toCharArray())
        val b = hasher.create("111111".toCharArray())
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))
    }

    @Test
    fun `only six-digit PINs are valid`() {
        assertTrue(PinHasher.isValidPin("000000".toCharArray()))
        assertFalse(PinHasher.isValidPin("12345".toCharArray()))
        assertFalse(PinHasher.isValidPin("1234567".toCharArray()))
        assertFalse(PinHasher.isValidPin("12a456".toCharArray()))
    }

    @Test
    fun `production default iteration count matches the security architecture`() {
        assertEquals(210_000, PinHasher.DEFAULT_ITERATIONS)
    }

    @Test
    fun `PIN record encoding round-trips`() {
        val record = hasher.create("246810".toCharArray())
        val decoded = PinRecord.decode(record.encode())
        assertArrayEquals(record.salt, decoded.salt)
        assertArrayEquals(record.hash, decoded.hash)
        assertEquals(record.iterations, decoded.iterations)
    }

    @Test
    fun `lockout starts after five failures and doubles up to one hour`() {
        var state = LockoutState()
        val now = 1_000_000L
        repeat(5) {
            state = LockoutPolicy.onFailure(state, now)
            assertFalse(LockoutPolicy.isLocked(state, now))
        }
        state = LockoutPolicy.onFailure(state, now)
        assertTrue(LockoutPolicy.isLocked(state, now))
        assertEquals(30_000L, LockoutPolicy.remainingMillis(state, now))
        assertFalse(LockoutPolicy.isLocked(state, now + 30_000L))
        assertEquals(60_000L, LockoutPolicy.delayFor(2))
        assertEquals(1_920_000L, LockoutPolicy.delayFor(7))
        assertEquals(3_600_000L, LockoutPolicy.delayFor(8))
        assertEquals(3_600_000L, LockoutPolicy.delayFor(50))
        assertEquals(LockoutState(), LockoutPolicy.onSuccess())
    }

    @Test
    fun `PIN store persists only wrapped data and survives overwrite`() {
        val wrapper =
            object : KeyWrapper {
                override fun wrap(plaintext: ByteArray) = plaintext.reversedArray() + 0x7F

                override fun unwrap(wrapped: ByteArray) = wrapped.copyOf(wrapped.size - 1).reversedArray()
            }
        val dir = Files.createTempDirectory("pinstore").toFile()
        val store = PinStore(dir, wrapper)
        assertFalse(store.hasPin())
        assertNull(store.load())

        val first = hasher.create("135790".toCharArray())
        store.save(first)
        val second = hasher.create("975310".toCharArray())
        store.save(second) // overwrite must work (atomic replace)
        assertTrue(hasher.verify("975310".toCharArray(), checkNotNull(store.load())))
        assertFalse(dir.resolve("pin.enc").readBytes().contentEquals(second.encode()))

        store.saveLockout(LockoutState(6, 42L))
        assertEquals(LockoutState(6, 42L), PinStore(dir, wrapper).loadLockout())
    }
}
