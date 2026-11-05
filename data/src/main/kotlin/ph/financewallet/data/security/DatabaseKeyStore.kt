package ph.financewallet.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts/decrypts small secrets with a non-exportable key (docs/16-security-architecture.md §3). */
interface KeyWrapper {
    fun wrap(plaintext: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** AES-256-GCM key held in the Android Keystore under [alias]. Output format: 12-byte IV ‖ ciphertext+tag. */
class AndroidKeystoreKeyWrapper(private val alias: String) : KeyWrapper {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // Not bound to user authentication: see security architecture §3 (background work, R-11).
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        return iv + cipher.doFinal(plaintext)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_BYTES + TAG_BYTES) { "wrapped data too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES))
        return cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
    }
}

/** Thrown when a stored database key exists but cannot be unwrapped (e.g., Keystore key invalidated). */
class DatabaseKeyUnavailableException(cause: Throwable) :
    IllegalStateException("The database key could not be unlocked", cause)

/**
 * Holds the random 256-bit SQLCipher key, stored only in wrapped form in [keyFile].
 *
 * If the wrapped key exists but cannot be unwrapped, this never generates a replacement, because that
 * would orphan the existing encrypted database. The caller must offer restore from backup (R-11).
 */
class DatabaseKeyStore(
    private val keyFile: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    @Synchronized
    fun getOrCreateKey(): ByteArray {
        if (keyFile.exists()) {
            val wrapped = keyFile.readBytes()
            return try {
                wrapper.unwrap(wrapped).also { check(it.size == KEY_BYTES) { "stored key has wrong length" } }
            } catch (e: Exception) {
                throw DatabaseKeyUnavailableException(e)
            }
        }
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        writeAtomically(wrapper.wrap(key))
        return key
    }

    private fun writeAtomically(bytes: ByteArray) {
        keyFile.parentFile?.mkdirs()
        val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                tmp.toPath(),
                keyFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("could not persist database key", e)
        }
    }

    companion object {
        const val KEY_BYTES: Int = 32
    }
}
