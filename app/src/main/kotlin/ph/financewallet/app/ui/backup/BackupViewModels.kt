package ph.financewallet.app.ui.backup

import android.database.SQLException
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.data.backup.BackupError
import ph.financewallet.data.backup.BackupException
import ph.financewallet.data.backup.BackupSummary
import ph.financewallet.data.backup.PassphrasePolicy
import ph.financewallet.data.backup.PassphraseProblem
import ph.financewallet.data.insight.InsightsReport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext

data class BackupState(
    val loading: Boolean = true,
    /** Epoch ms of the last backup made on this phone, or null if there has never been one. */
    val lastBackupAt: Long? = null,
    val busy: Boolean = false,
    /** Rows written by the backup just made on this screen. */
    val createdRows: Int? = null,
    /** A fully verified backup waiting for "Replace all current data" (docs/14-backup-format.md §4 step 6). */
    val pending: BackupSummary? = null,
    /** The backup that was just restored. */
    val restored: BackupSummary? = null,
)

/**
 * UC-29 backup and restore. Streams come from files the user picked with the system picker; nothing is written to any
 * other place. Every function returns a message resource for a failure, or null on success.
 */
class BackupViewModel(
    private val s: AppServices,
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = mutable

    fun refresh() {
        viewModelScope.launch { load() }
    }

    suspend fun load() = mutable.update { it.copy(loading = false, lastBackupAt = s.backup.lastBackupAt()) }

    @StringRes
    fun passphraseProblem(
        passphrase: String,
        confirmation: String,
    ): Int? = withChars(passphrase) { p -> withChars(confirmation) { c -> PassphrasePolicy.check(p, c) } }?.let(::passphraseText)

    /** 0 (refused) to [PassphrasePolicy.MAX_STRENGTH]; only a guide for the meter. */
    fun strength(passphrase: String): Int = withChars(passphrase, PassphrasePolicy::strength)

    /** BAK-01: writes an encrypted backup into the stream [open] returns. */
    suspend fun create(
        open: () -> OutputStream?,
        passphrase: String,
        confirmation: String,
    ): Int? {
        passphraseProblem(passphrase, confirmation)?.let { return it }
        mutable.update { it.copy(busy = true, createdRows = null) }
        return try {
            val counts = withContext(io) { open()?.use { s.backup.create(it, passphrase.toCharArray()) } }
            if (counts == null) {
                R.string.backup_err_write
            } else {
                mutable.update { it.copy(createdRows = counts.values.sum(), lastBackupAt = s.backup.lastBackupAt()) }
                null
            }
        } catch (_: IOException) {
            R.string.backup_err_write
        } catch (_: SecurityException) {
            R.string.backup_err_write
        } finally {
            mutable.update { it.copy(busy = false) }
        }
    }

    /** Pass 1: decrypts and verifies the whole file; nothing changes. On success the summary waits for confirmation. */
    suspend fun inspect(
        open: () -> InputStream?,
        passphrase: String,
    ): Int? =
        guarded {
            val summary = withContext(io) { s.backup.inspect({ open() ?: throw IOException("cannot open") }, passphrase.toCharArray()) }
            mutable.update { it.copy(pending = summary, restored = null) }
        }

    /** Pass 2, after the user confirmed: replaces all data, or changes nothing if anything fails. */
    suspend fun restore(
        open: () -> InputStream?,
        passphrase: String,
    ): Int? =
        guarded {
            val summary = withContext(io) { s.backup.restore({ open() ?: throw IOException("cannot open") }, passphrase.toCharArray()) }
            mutable.update { it.copy(pending = null, restored = summary, lastBackupAt = s.backup.lastBackupAt()) }
        }

    fun cancelRestore() = mutable.update { it.copy(pending = null) }

    private suspend fun guarded(block: suspend () -> Unit): Int? {
        mutable.update { it.copy(busy = true) }
        return try {
            block()
            null
        } catch (e: BackupException) {
            backupErrorText(e.error)
        } catch (_: IOException) {
            R.string.backup_err_read
        } catch (_: SQLException) {
            R.string.backup_err_restore
        } catch (_: SecurityException) {
            R.string.backup_err_read
        } finally {
            mutable.update { it.copy(busy = false) }
        }
    }

    /** UC-30: all data as readable JSON Lines (not encrypted; the screen warns first). Returns rows written, or null. */
    suspend fun exportPlaintext(open: () -> OutputStream?): Int? =
        try {
            withContext(io) {
                open()?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                    s.backup
                        .exportPlaintext(w)
                        .values
                        .sum()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}

/** Runs [block] on a copy of [text] as characters, then wipes the copy. */
private fun <T> withChars(
    text: String,
    block: (CharArray) -> T,
): T {
    val chars = text.toCharArray()
    return try {
        block(chars)
    } finally {
        chars.fill('\u0000')
    }
}

@StringRes
fun passphraseText(p: PassphraseProblem): Int =
    when (p) {
        PassphraseProblem.TOO_SHORT -> R.string.backup_pass_too_short
        PassphraseProblem.TOO_COMMON -> R.string.backup_pass_too_common
        PassphraseProblem.TOO_REPETITIVE -> R.string.backup_pass_repetitive
        PassphraseProblem.MISMATCH -> R.string.backup_pass_mismatch
    }

@StringRes
fun backupErrorText(e: BackupError): Int =
    when (e) {
        BackupError.NOT_A_BACKUP -> {
            R.string.backup_err_not_backup
        }

        BackupError.UNSUPPORTED_VERSION, BackupError.SCHEMA_TOO_NEW -> {
            R.string.backup_err_too_new
        }

        BackupError.WRONG_PASSPHRASE_OR_DAMAGED -> {
            R.string.backup_err_passphrase
        }

        BackupError.BAD_HEADER, BackupError.KDF_PARAMS_OUT_OF_RANGE, BackupError.CHECKSUM_MISMATCH, BackupError.INVALID_CONTENT -> {
            R.string.backup_err_damaged
        }
    }

/** UC-31 delete all data. The PIN check and the wipe come from the app container, so they can be faked in tests. */
class DeleteAllViewModel(
    private val confirmPin: (CharArray) -> Boolean,
    private val wipe: suspend () -> Unit,
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(false)

    /** True while the PIN is checked and the data deleted. */
    val busy: StateFlow<Boolean> = mutable

    /** Deletes everything only if [pin] is right. Returns true when the data is gone (the caller restarts the app). */
    suspend fun deleteAll(pin: String): Boolean {
        mutable.value = true
        try {
            val ok = withContext(io) { confirmPin(pin.toCharArray()) }
            if (ok) withContext(io) { wipe() }
            return ok
        } finally {
            mutable.value = false
        }
    }
}

data class InsightsState(
    val loading: Boolean = true,
    val report: InsightsReport? = null,
)

/** INS-01..03: read-only figures computed on this phone. */
class InsightsViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(InsightsState())
    val state: StateFlow<InsightsState> = mutable

    fun refresh() {
        viewModelScope.launch { load() }
    }

    suspend fun load() {
        mutable.value = InsightsState(false, s.insights.report())
    }
}
