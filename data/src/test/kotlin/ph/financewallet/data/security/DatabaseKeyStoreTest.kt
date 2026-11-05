package ph.financewallet.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.financewallet.data.db.EncryptedDatabaseFactory
import java.io.File
import java.nio.file.Files

class DatabaseKeyStoreTest {
    /** Test double: XOR "wrapping" that is reversible and detectably wrong when [broken] is set. */
    private class FakeWrapper(
        var broken: Boolean = false,
    ) : KeyWrapper {
        override fun wrap(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (broken) throw javax.crypto.AEADBadTagException("tag mismatch")
            return wrapped.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private fun tempFile(): File = Files.createTempDirectory("keystore").toFile().resolve("db.key.enc")

    @Test
    fun `creates a 32-byte key once and returns the same key afterwards`() {
        val file = tempFile()
        val store = DatabaseKeyStore(file, FakeWrapper())
        val first = store.getOrCreateKey()
        assertEquals(32, first.size)
        assertTrue(file.exists())
        assertFalse("key must not be stored in plaintext", file.readBytes().contentEquals(first))
        assertArrayEquals(first, DatabaseKeyStore(file, FakeWrapper()).getOrCreateKey())
    }

    @Test
    fun `an unreadable existing key is reported and never silently replaced`() {
        val file = tempFile()
        DatabaseKeyStore(file, FakeWrapper()).getOrCreateKey()
        val before = file.readBytes()
        try {
            DatabaseKeyStore(file, FakeWrapper(broken = true)).getOrCreateKey()
            throw AssertionError("expected DatabaseKeyUnavailableException")
        } catch (_: DatabaseKeyUnavailableException) {
        }
        assertArrayEquals("wrapped key file must be untouched", before, file.readBytes())
    }

    @Test
    fun `raw key spec is SQLCipher x-quote-hex form`() {
        val key = ByteArray(32) { it.toByte() }
        val spec = String(EncryptedDatabaseFactory.rawKeySpec(key), Charsets.US_ASCII)
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", spec)
    }
}
