package ph.financewallet.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.random.Random

/** The `.fwbak` stream format (docs/14-backup-format.md §2, AC-BAK-2/3/5): plain JVM, no Android needed. */
class BackupCryptoTest {
    // Fast KDF settings for tests; the production default (64 MiB) has its own test below.
    private val kdf = Kdf.Argon2id(memoryKiB = 8_192, iterations = 1)
    private val chunk = 1 shl 16
    private val pass = "correct horse battery staple".toCharArray()

    private fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray = pass,
        header: BackupHeader = BackupCrypto.newHeader(kdf, 1_700_000_000_000, 1, 4, chunk),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        EncryptingOutputStream(out, BackupCrypto.deriveKey(passphrase, header), header).use { it.write(plaintext) }
        return out.toByteArray()
    }

    private fun decrypt(
        file: ByteArray,
        passphrase: CharArray = pass,
    ): ByteArray {
        val input = ByteArrayInputStream(file)
        val header = BackupHeader.read(input)
        return DecryptingInputStream(input, BackupCrypto.deriveKey(passphrase, header), header).use { it.readBytes() }
    }

    private fun expect(
        error: BackupError,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected $error")
        } catch (e: BackupException) {
            assertEquals(error, e.error)
        }
    }

    @Test
    fun `round trip across chunk boundaries, including empty and exactly-full chunks`() {
        listOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk, 3 * chunk + 17).forEach { n ->
            val data = Random(n).nextBytes(n)
            assertArrayEquals("size $n", data, decrypt(encrypt(data)))
        }
    }

    @Test
    fun `ciphertext reveals nothing readable and differs every time`() {
        val data = "{\"t\":\"row\",\"table\":\"txn\",\"description\":\"Jollibee SM North\"}".repeat(50).toByteArray()
        val a = encrypt(data)
        val b = encrypt(data)
        assertTrue(!String(a, Charsets.ISO_8859_1).contains("Jollibee"))
        assertTrue(!a.contentEquals(b)) // fresh salt and nonce prefix
    }

    @Test
    fun `wrong passphrase, flipped byte, truncation, reordering and appended data are all rejected (AC-BAK-2)`() {
        val data = Random(1).nextBytes(3 * chunk + 100)
        val file = encrypt(data)
        val headerSize = BackupCrypto.newHeader(kdf, 0, 0, 0, chunk).encode().size
        val c = chunk + BackupCrypto.TAG_BYTES
        expect(BackupError.WRONG_PASSPHRASE_OR_DAMAGED) { decrypt(file, "correct horse battery stable".toCharArray()) }
        for (offset in listOf(headerSize, headerSize + c + 5, file.size - 1)) {
            val flipped = file.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            expect(BackupError.WRONG_PASSPHRASE_OR_DAMAGED) { decrypt(flipped) }
        }
        expect(BackupError.WRONG_PASSPHRASE_OR_DAMAGED) { decrypt(file.copyOf(headerSize + 2 * c)) } // cut at a chunk boundary
        expect(BackupError.WRONG_PASSPHRASE_OR_DAMAGED) { decrypt(file.copyOf(file.size - 7)) }
        val swapped =
            file.copyOf().also {
                System.arraycopy(file, headerSize + c, it, headerSize, c)
                System.arraycopy(file, headerSize, it, headerSize + c, c)
            }
        expect(BackupError.WRONG_PASSPHRASE_OR_DAMAGED) { decrypt(swapped) }
        val duplicated = file.copyOfRange(0, headerSize + c) + file.copyOfRange(headerSize, file.size)
        expect(BackupError.WRONG_PASSPHRASE_OR_DAMAGED) { decrypt(duplicated) }
        expect(BackupError.WRONG_PASSPHRASE_OR_DAMAGED) { decrypt(file + byteArrayOf(1, 2, 3)) }
        // the header is authenticated too: changing createdAt breaks every chunk
        val createdAtOffset = headerSize - 2 * Int.SIZE_BYTES - Long.SIZE_BYTES // header ends with createdAt, appVersion, schemaVersion
        val header = file.copyOf().also { it[createdAtOffset] = (it[createdAtOffset] + 1).toByte() }
        expect(BackupError.WRONG_PASSPHRASE_OR_DAMAGED) { decrypt(header) }
    }

    @Test
    fun `hostile or foreign headers are rejected before key derivation (AC-BAK-3)`() {
        expect(BackupError.NOT_A_BACKUP) { BackupHeader.read(ByteArrayInputStream("PK\u0003\u0004 not a backup".toByteArray())) }
        expect(BackupError.NOT_A_BACKUP) { BackupHeader.read(ByteArrayInputStream("FWB".toByteArray())) }

        fun header(write: DataOutputStream.() -> Unit) =
            ByteArrayInputStream(
                ByteArrayOutputStream()
                    .also {
                        DataOutputStream(it).apply {
                            write(BackupHeader.MAGIC)
                            write()
                        }
                    }.toByteArray(),
            )
        expect(BackupError.UNSUPPORTED_VERSION) { BackupHeader.read(header { writeShort(2) }) }
        expect(BackupError.KDF_PARAMS_OUT_OF_RANGE) {
            BackupHeader.read(
                header {
                    writeShort(1)
                    writeByte(1)
                    writeInt(4_000_000)
                    writeInt(3)
                    writeByte(1)
                },
            )
        }
        expect(BackupError.KDF_PARAMS_OUT_OF_RANGE) {
            BackupHeader.read(
                header {
                    writeShort(1)
                    writeByte(2)
                    writeInt(10)
                },
            )
        }
        expect(BackupError.BAD_HEADER) {
            BackupHeader.read(
                header {
                    writeShort(1)
                    writeByte(9)
                },
            )
        }
        val badChunk = BackupCrypto.newHeader(kdf, 0, 0, 0, chunk).copy(chunkSize = 10).encode()
        expect(BackupError.BAD_HEADER) { BackupHeader.read(ByteArrayInputStream(badChunk)) }
    }

    @Test
    fun `header round trip and the PBKDF2 reader path`() {
        val h = BackupCrypto.newHeader(Kdf.Pbkdf2(100_000), 42, 7, 4, chunk)
        assertEquals(h, BackupHeader.read(ByteArrayInputStream(h.encode())))
        val data = "pbkdf2".toByteArray()
        assertArrayEquals(data, decrypt(encrypt(data, header = h)))
    }

    @Test
    fun `production Argon2id parameters derive a stable key`() {
        val h = BackupCrypto.newHeader(Kdf.Argon2id(), 0, 0, 0).copy(salt = ByteArray(16) { it.toByte() })
        val k1 = BackupCrypto.deriveKey("passphrase-ñ".toCharArray(), h)
        val k2 = BackupCrypto.deriveKey("passphrase-ñ".toCharArray(), h) // NFC makes both spellings equal
        assertArrayEquals(k1, k2)
        assertEquals(32, k1.size)
        assertTrue(Kdf.ARGON2ID_LOW_MEMORY.memoryKiB < Kdf.Argon2id().memoryKiB)
    }

    @Test
    fun `passphrase policy (AC-BAK-5)`() {
        assertEquals(PassphraseProblem.TOO_SHORT, PassphrasePolicy.check("short".toCharArray()))
        assertEquals(PassphraseProblem.TOO_COMMON, PassphrasePolicy.check("1234567890".toCharArray()))
        assertEquals(PassphraseProblem.TOO_COMMON, PassphrasePolicy.check("QWERTYUIOP".toCharArray()))
        // A common word padded with digits/symbols to reach the length (found on the emulator, 2026-11-06).
        assertEquals(PassphraseProblem.TOO_COMMON, PassphrasePolicy.check("password123".toCharArray()))
        assertEquals(PassphraseProblem.TOO_COMMON, PassphrasePolicy.check("Iloveyou2024!".toCharArray()))
        assertEquals(PassphraseProblem.TOO_COMMON, PassphrasePolicy.check("!!Sunshine99".toCharArray()))
        // A padded short core is not over-matched; an uncommon word with digits is fine.
        assertNull(PassphrasePolicy.check("ab1234567890xy".toCharArray()))
        assertNull(PassphrasePolicy.check("jeepneyride2026".toCharArray()))
        assertEquals(PassphraseProblem.TOO_REPETITIVE, PassphrasePolicy.check("aaaaaaaaaaaa".toCharArray()))
        assertEquals(
            PassphraseProblem.MISMATCH,
            PassphrasePolicy.check("long enough phrase".toCharArray(), "long enough phrasE".toCharArray()),
        )
        assertNull(PassphrasePolicy.check("long enough phrase".toCharArray(), "long enough phrase".toCharArray()))
        assertTrue(PassphrasePolicy.commonCount >= 10_000) // SecLists 10k-most-common.txt: 10,001 distinct entries
        // R8 renames PassphrasePolicy in release builds; only an absolute resource path survives that.
        assertTrue(PassphrasePolicy.RESOURCE.startsWith("/ph/financewallet/data/backup/"))
        assertEquals(0, PassphrasePolicy.strength("password".toCharArray()))
        assertEquals(1, PassphrasePolicy.strength("bananaboat".toCharArray()))
        assertEquals(4, PassphrasePolicy.strength("Mango-Ripens-In-May-2027".toCharArray()))
    }
}
