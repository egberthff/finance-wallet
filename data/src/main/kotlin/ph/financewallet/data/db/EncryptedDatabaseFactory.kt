package ph.financewallet.data.db

import android.content.Context
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ph.financewallet.data.security.AndroidKeystoreKeyWrapper
import ph.financewallet.data.security.DatabaseKeyStore
import java.io.File

/**
 * Opens the production database encrypted with SQLCipher (ADR-0002). The raw 32-byte key comes from
 * [DatabaseKeyStore] and is wrapped by the Android Keystore alias `fw_db_wrap_v1`.
 */
object EncryptedDatabaseFactory {
    private const val WRAP_ALIAS = "fw_db_wrap_v1"
    private const val KEY_FILE = "db.key.enc"

    @Volatile
    private var libraryLoaded = false

    fun open(context: Context): FinanceDatabase {
        loadLibrary()
        val keyStore = DatabaseKeyStore(File(context.noBackupFilesDir, KEY_FILE), AndroidKeystoreKeyWrapper(WRAP_ALIAS))
        val key = keyStore.getOrCreateKey()
        val rawKeySpec = rawKeySpec(key)
        key.fill(0)
        return FinanceDatabase.build(context, SupportOpenHelperFactory(rawKeySpec))
    }

    /**
     * SQLCipher raw-key form `x'<64 hex>'`: the 256-bit key is used directly, skipping per-open PBKDF2
     * (the key is already random, so a KDF adds startup cost without security benefit).
     * MagicNumber is suppressed: the nibble shifts/masks and the 3 framing bytes are the hex encoding itself.
     */
    @Suppress("MagicNumber")
    internal fun rawKeySpec(key: ByteArray): ByteArray {
        require(key.size == DatabaseKeyStore.KEY_BYTES) { "key must be ${DatabaseKeyStore.KEY_BYTES} bytes" }
        val hex = "0123456789abcdef"
        val out = ByteArray(3 + key.size * 2)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        key.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[2 + i * 2] = hex[v ushr 4].code.toByte()
            out[3 + i * 2] = hex[v and 0x0F].code.toByte()
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }

    @Synchronized
    private fun loadLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("sqlcipher")
            libraryLoaded = true
        }
    }
}
