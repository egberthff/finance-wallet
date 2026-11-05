package ph.financewallet.app.security

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import ph.financewallet.data.security.KeyWrapper

/**
 * Persists the PIN record (wrapped with a Keystore key, so a copied file cannot be brute-forced offline)
 * and the lockout state in app-private no-backup storage.
 */
class PinStore(private val dir: File, private val wrapper: KeyWrapper) {
    private val pinFile get() = File(dir, "pin.enc")
    private val lockoutFile get() = File(dir, "pin.lockout")

    fun hasPin(): Boolean = pinFile.exists()

    fun save(record: PinRecord) = writeAtomically(pinFile, wrapper.wrap(record.encode()))

    fun load(): PinRecord? = if (pinFile.exists()) PinRecord.decode(wrapper.unwrap(pinFile.readBytes())) else null

    fun loadLockout(): LockoutState {
        if (!lockoutFile.exists()) return LockoutState()
        val parts = lockoutFile.readText().split(",")
        return LockoutState(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun saveLockout(state: LockoutState) =
        writeAtomically(lockoutFile, "${state.consecutiveFailures},${state.lockedUntilMillis}".toByteArray())

    private fun writeAtomically(target: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("could not write ${target.name}", e)
        }
    }
}
