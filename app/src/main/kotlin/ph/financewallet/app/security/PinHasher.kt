package ph.financewallet.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A salted PIN hash. Stored only in Keystore-wrapped form ([PinStore]).
 *
 * Binary layout v1: [0]=format, [1]=salt length, [2]=hash length, [3..6]=iterations (big-endian), then salt, then hash.
 * MagicNumber is suppressed because the byte offsets and shifts below are that documented layout.
 */
@Suppress("MagicNumber")
class PinRecord(
    val salt: ByteArray,
    val hash: ByteArray,
    val iterations: Int,
) {
    fun encode(): ByteArray {
        require(salt.size <= Byte.MAX_VALUE && hash.size <= Byte.MAX_VALUE)
        return byteArrayOf(FORMAT_V1, salt.size.toByte(), hash.size.toByte()) +
            intToBytes(iterations) + salt + hash
    }

    companion object {
        private const val FORMAT_V1: Byte = 1

        fun decode(bytes: ByteArray): PinRecord {
            require(bytes.size > 7 && bytes[0] == FORMAT_V1) { "unknown PIN record format" }
            val saltLen = bytes[1].toInt()
            val hashLen = bytes[2].toInt()
            require(bytes.size == 7 + saltLen + hashLen) { "corrupt PIN record" }
            val iterations = bytesToInt(bytes, 3)
            return PinRecord(bytes.copyOfRange(7, 7 + saltLen), bytes.copyOfRange(7 + saltLen, bytes.size), iterations)
        }

        private fun intToBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        private fun bytesToInt(
            b: ByteArray,
            o: Int,
        ) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    }
}

/**
 * PBKDF2-HMAC-SHA256 PIN hashing (docs/16-security-architecture.md T9): 16-byte salt, 210,000 iterations,
 * constant-time verification. The PIN is accepted as a CharArray so callers can wipe it.
 */
class PinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun create(pin: CharArray): PinRecord {
        require(isValidPin(pin)) { "PIN must be exactly $PIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PinRecord(salt, derive(pin, salt, iterations), iterations)
    }

    fun verify(
        pin: CharArray,
        record: PinRecord,
    ): Boolean {
        if (!isValidPin(pin)) return false
        return MessageDigest.isEqual(derive(pin, record.salt, record.iterations), record.hash)
    }

    private fun derive(
        pin: CharArray,
        salt: ByteArray,
        rounds: Int,
    ): ByteArray {
        val spec = PBEKeySpec(pin, salt, rounds, HASH_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val PIN_LENGTH: Int = 6
        const val DEFAULT_ITERATIONS: Int = 210_000
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256

        fun isValidPin(pin: CharArray): Boolean = pin.size == PIN_LENGTH && pin.all { it in '0'..'9' }
    }
}
