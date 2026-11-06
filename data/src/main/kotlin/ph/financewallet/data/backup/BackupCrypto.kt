package ph.financewallet.data.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup file could not be read. Mapped to user messages; never reveals which byte failed. */
enum class BackupError {
    NOT_A_BACKUP,
    UNSUPPORTED_VERSION,
    BAD_HEADER,
    KDF_PARAMS_OUT_OF_RANGE,

    /** Any authentication failure: wrong passphrase, damaged, truncated, reordered or extended file. */
    WRONG_PASSPHRASE_OR_DAMAGED,
    CHECKSUM_MISMATCH,
    SCHEMA_TOO_NEW,
    INVALID_CONTENT,
}

class BackupException(
    val error: BackupError,
    detail: String = "",
    cause: Throwable? = null,
) : IOException(error.name + if (detail.isEmpty()) "" else ": $detail", cause)

internal fun fail(
    error: BackupError,
    detail: String = "",
    cause: Throwable? = null,
): Nothing = throw BackupException(error, detail, cause)

/** Key derivation function recorded in the header (docs/14-backup-format.md §2). */
sealed interface Kdf {
    val id: Int

    /** Argon2id with memory [memoryKiB], [iterations] passes and [parallelism] lanes (default 64 MiB, t = 3, p = 1). */
    data class Argon2id(
        val memoryKiB: Int = 65_536,
        val iterations: Int = 3,
        val parallelism: Int = 1,
    ) : Kdf {
        override val id: Int get() = ARGON2ID_ID
    }

    /** PBKDF2-HMAC-SHA256 (platform crypto): readable for compatibility, not written by default. */
    data class Pbkdf2(
        val iterations: Int = 600_000,
    ) : Kdf {
        override val id: Int get() = PBKDF2_ID
    }

    companion object {
        const val ARGON2ID_ID = 1
        const val PBKDF2_ID = 2

        /** Smaller Argon2id setting used when the 64 MiB allocation fails on a low-memory device (§2). */
        val ARGON2ID_LOW_MEMORY = Argon2id(memoryKiB = 32_768, iterations = 4)
    }
}

/** The plaintext, authenticated header of a `.fwbak` file. */
data class BackupHeader(
    val formatVersion: Int,
    val kdf: Kdf,
    val salt: ByteArray,
    val noncePrefix: ByteArray,
    val chunkSize: Int,
    val createdAt: Long,
    val appVersion: Int,
    val schemaVersion: Int,
) {
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.write(MAGIC)
            o.writeShort(formatVersion)
            o.writeByte(kdf.id)
            when (kdf) {
                is Kdf.Argon2id -> {
                    o.writeInt(kdf.memoryKiB)
                    o.writeInt(kdf.iterations)
                    o.writeByte(kdf.parallelism)
                }

                is Kdf.Pbkdf2 -> {
                    o.writeInt(kdf.iterations)
                }
            }
            o.write(salt)
            o.write(noncePrefix)
            o.writeInt(chunkSize)
            o.writeLong(createdAt)
            o.writeInt(appVersion)
            o.writeInt(schemaVersion)
        }
        return bytes.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is BackupHeader && encode().contentEquals(other.encode())

    override fun hashCode(): Int = encode().contentHashCode()

    companion object {
        val MAGIC = "FWBK".toByteArray(Charsets.US_ASCII)
        const val FORMAT_VERSION = 1
        const val SALT_BYTES = 16
        const val NONCE_PREFIX_BYTES = 8
        const val DEFAULT_CHUNK = 1 shl 20

        // Bounds that keep a hostile header from forcing huge memory or time (§4 step 2).
        private const val MIN_ARGON_KIB = 8_192
        private const val MAX_ARGON_KIB = 262_144
        private const val MAX_ARGON_T = 10
        private const val MAX_ARGON_P = 4
        private const val MIN_PBKDF2 = 100_000
        private const val MAX_PBKDF2 = 5_000_000
        private const val MIN_CHUNK = 1 shl 16
        private const val MAX_CHUNK = 1 shl 23

        /** Reads and validates a header; rejects anything outside the supported format before any key derivation. */
        fun read(input: InputStream): BackupHeader {
            val d = DataInputStream(input)
            try {
                val magic = ByteArray(MAGIC.size).also { d.readFully(it) }
                if (!magic.contentEquals(MAGIC)) fail(BackupError.NOT_A_BACKUP)
                val version = d.readUnsignedShort()
                if (version != FORMAT_VERSION) fail(BackupError.UNSUPPORTED_VERSION, "v$version")
                val kdf =
                    when (val id = d.readUnsignedByte()) {
                        Kdf.ARGON2ID_ID -> Kdf.Argon2id(d.readInt(), d.readInt(), d.readUnsignedByte())
                        Kdf.PBKDF2_ID -> Kdf.Pbkdf2(d.readInt())
                        else -> fail(BackupError.BAD_HEADER, "kdf $id")
                    }
                checkBounds(kdf)
                val salt = ByteArray(SALT_BYTES).also { d.readFully(it) }
                val prefix = ByteArray(NONCE_PREFIX_BYTES).also { d.readFully(it) }
                val chunk = d.readInt()
                if (chunk !in MIN_CHUNK..MAX_CHUNK) fail(BackupError.BAD_HEADER, "chunk $chunk")
                return BackupHeader(version, kdf, salt, prefix, chunk, d.readLong(), d.readInt(), d.readInt())
            } catch (e: EOFException) {
                fail(BackupError.NOT_A_BACKUP, "short header", e)
            }
        }

        private fun checkBounds(kdf: Kdf) {
            val ok =
                when (kdf) {
                    is Kdf.Argon2id -> {
                        kdf.memoryKiB in MIN_ARGON_KIB..MAX_ARGON_KIB && kdf.iterations in 1..MAX_ARGON_T &&
                            kdf.parallelism in 1..MAX_ARGON_P
                    }

                    is Kdf.Pbkdf2 -> {
                        kdf.iterations in MIN_PBKDF2..MAX_PBKDF2
                    }
                }
            if (!ok) fail(BackupError.KDF_PARAMS_OUT_OF_RANGE, kdf.toString())
        }
    }
}

/** Key derivation and the chunked AES-256-GCM stream format of docs/14-backup-format.md §2. */
object BackupCrypto {
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    const val TAG_BYTES = 16
    private val random = SecureRandom()

    /** A header with fresh random salt and nonce prefix. */
    fun newHeader(
        kdf: Kdf,
        createdAt: Long,
        appVersion: Int,
        schemaVersion: Int,
        chunkSize: Int = BackupHeader.DEFAULT_CHUNK,
    ): BackupHeader =
        BackupHeader(
            BackupHeader.FORMAT_VERSION,
            kdf,
            ByteArray(BackupHeader.SALT_BYTES).also(random::nextBytes),
            ByteArray(BackupHeader.NONCE_PREFIX_BYTES).also(random::nextBytes),
            chunkSize,
            createdAt,
            appVersion,
            schemaVersion,
        )

    /** Derives the 32-byte key from the passphrase (Unicode NFC, UTF-8). The intermediate byte arrays are wiped. */
    fun deriveKey(
        passphrase: CharArray,
        header: BackupHeader,
    ): ByteArray {
        val normalized = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC).toCharArray()
        val utf8 = Charsets.UTF_8.encode(CharBuffer.wrap(normalized))
        val bytes = ByteArray(utf8.remaining()).also { utf8.get(it) }
        try {
            return when (val kdf = header.kdf) {
                is Kdf.Argon2id -> {
                    val params =
                        Argon2Parameters
                            .Builder(Argon2Parameters.ARGON2_id)
                            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                            .withMemoryAsKB(kdf.memoryKiB)
                            .withIterations(kdf.iterations)
                            .withParallelism(kdf.parallelism)
                            .withSalt(header.salt)
                            .build()
                    ByteArray(KEY_BYTES).also { out -> Argon2BytesGenerator().apply { init(params) }.generateBytes(bytes, out) }
                }

                is Kdf.Pbkdf2 -> {
                    val spec = PBEKeySpec(normalized, header.salt, kdf.iterations, KEY_BYTES * Byte.SIZE_BITS)
                    try {
                        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                    } finally {
                        spec.clearPassword()
                    }
                }
            }
        } finally {
            bytes.fill(0)
            normalized.fill('\u0000')
            if (utf8.hasArray()) utf8.array().fill(0)
        }
    }

    internal fun aad(
        headerHash: ByteArray,
        index: Int,
        final: Boolean,
    ): ByteArray =
        ByteBuffer
            .allocate(headerHash.size + Int.SIZE_BYTES + 1)
            .put(headerHash)
            .putInt(index)
            .put(if (final) 1 else 0)
            .array()

    internal fun nonce(
        prefix: ByteArray,
        index: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(prefix.size + Int.SIZE_BYTES)
            .put(prefix)
            .putInt(index)
            .array()

    internal fun cipher(
        mode: Int,
        key: ByteArray,
        header: BackupHeader,
        headerHash: ByteArray,
        index: Int,
        final: Boolean,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(header.noncePrefix, index)))
            updateAAD(aad(headerHash, index, final))
        }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * Writes the header, then the plaintext in encrypted chunks. A full buffer is written as a non-final chunk only once more
 * data follows, so the last chunk (possibly empty or full) is always flagged final on [close]. Nothing plaintext touches disk.
 */
class EncryptingOutputStream(
    out: OutputStream,
    private val key: ByteArray,
    private val header: BackupHeader,
) : FilterOutputStream(out) {
    private val headerHash: ByteArray
    private val buffer = ByteArray(header.chunkSize)
    private var size = 0
    private var index = 0
    private var closed = false

    init {
        val h = header.encode()
        headerHash = BackupCrypto.sha256(h)
        out.write(h)
    }

    override fun write(b: Int) {
        if (size == buffer.size) flushChunk(final = false)
        buffer[size++] = b.toByte()
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        var pos = off
        var remaining = len
        while (remaining > 0) {
            if (size == buffer.size) flushChunk(final = false)
            val n = minOf(remaining, buffer.size - size)
            System.arraycopy(b, pos, buffer, size, n)
            size += n
            pos += n
            remaining -= n
        }
    }

    private fun flushChunk(final: Boolean) {
        val c = BackupCrypto.cipher(Cipher.ENCRYPT_MODE, key, header, headerHash, index, final)
        out.write(c.doFinal(buffer, 0, size))
        size = 0
        index++
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        closed = true
        try {
            flushChunk(final = true)
            out.flush()
        } finally {
            buffer.fill(0)
            key.fill(0)
            out.close()
        }
    }
}

/**
 * Reads chunks after [header] and verifies each one. A full-length chunk is final only when the file ends right after it.
 * Any tag failure (wrong passphrase, flipped byte, truncation, reordering, appended data) raises
 * [BackupError.WRONG_PASSPHRASE_OR_DAMAGED]; plaintext is only ever returned after its chunk verified.
 */
class DecryptingInputStream(
    input: InputStream,
    private val key: ByteArray,
    private val header: BackupHeader,
) : InputStream() {
    private val source = PushbackInputStream(input, 1)
    private val headerHash = BackupCrypto.sha256(header.encode())
    private var chunk = ByteArray(0)
    private var pos = 0
    private var index = 0
    private var finished = false

    override fun read(): Int {
        if (!fill()) return -1
        return chunk[pos++].toInt() and BYTE_MASK
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, chunk.size - pos)
        System.arraycopy(chunk, pos, b, off, n)
        pos += n
        return n
    }

    /** Ensures unread plaintext is available; false at the verified end of the stream. */
    private fun fill(): Boolean {
        while (pos >= chunk.size) {
            if (finished) return false
            nextChunk()
        }
        return true
    }

    private fun nextChunk() {
        val full = header.chunkSize + BackupCrypto.TAG_BYTES
        val data = ByteArray(full)
        var read = 0
        while (read < full) {
            val n = source.read(data, read, full - read)
            if (n < 0) break
            read += n
        }
        if (read < BackupCrypto.TAG_BYTES) fail(BackupError.WRONG_PASSPHRASE_OR_DAMAGED, "truncated")
        val final =
            if (read < full) {
                true
            } else {
                val next = source.read()
                if (next >= 0) source.unread(next)
                next < 0
            }
        try {
            chunk = BackupCrypto.cipher(Cipher.DECRYPT_MODE, key, header, headerHash, index, final).doFinal(data, 0, read)
        } catch (e: AEADBadTagException) {
            fail(BackupError.WRONG_PASSPHRASE_OR_DAMAGED, "chunk $index", e)
        } catch (e: GeneralSecurityException) {
            fail(BackupError.WRONG_PASSPHRASE_OR_DAMAGED, "chunk $index", e)
        }
        pos = 0
        index++
        finished = final
    }

    override fun close() {
        chunk.fill(0)
        key.fill(0)
        source.close()
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
