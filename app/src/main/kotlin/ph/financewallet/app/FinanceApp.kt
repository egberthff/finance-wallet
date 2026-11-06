package ph.financewallet.app

import android.app.Application
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ph.financewallet.app.diagnostics.CrashLog
import ph.financewallet.app.security.AppLockController
import ph.financewallet.app.security.PinStore
import ph.financewallet.data.db.EncryptedDatabaseFactory
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.PreMigrationBackups
import ph.financewallet.data.security.AndroidKeystoreKeyWrapper
import java.io.File

/**
 * Manual dependency container (deliberately not Hilt for now: fewer dependencies, see PROGRESS.md decision log).
 * The database opens lazily, so nothing touches the key store until it is first needed.
 */
class AppContainer(
    private val app: Application,
    /** Deletes the Keystore keys (database key wrapper and PIN wrapper); replaceable in tests without a Keystore. */
    private val destroyKeys: () -> Unit = {
        EncryptedDatabaseFactory.destroyKey()
        AndroidKeystoreKeyWrapper(PIN_ALIAS).destroy()
    },
) {
    /** Local crash reports (no network): types and code locations only, saved by the user from Privacy & data. */
    val crashLog: CrashLog by lazy {
        val version =
            app.packageManager
                .getPackageInfo(app.packageName, 0)
                .versionName
                .orEmpty()
        CrashLog(
            File(app.noBackupFilesDir, "crash"),
            appVersion = version,
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
        )
    }

    val lockController: AppLockController by lazy {
        AppLockController(PinStore(File(app.noBackupFilesDir, "lock"), AndroidKeystoreKeyWrapper(PIN_ALIAS)))
    }

    private val databaseLock = Mutex()
    private var openDatabase: FinanceDatabase? = null

    /** Opens the database once (migrating with a pre-migration backup if needed). Must be called off the main thread. */
    suspend fun database(): FinanceDatabase =
        databaseLock.withLock {
            openDatabase ?: EncryptedDatabaseFactory.open(app).also { openDatabase = it }
        }

    /** Builds the services over the open database, with the pre-restore safety copy wired in (docs/14-backup-format.md §4). */
    suspend fun services(): AppServices {
        val db = database()
        return AppServices.create(db, appVersion = appVersion(), safetyCopy = { safetyCopy(db) })
    }

    private fun appVersion(): Int =
        androidx.core.content.pm.PackageInfoCompat
            .getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
            .toInt()

    /** Copies the (SQLCipher-encrypted) database file to app-private storage before a restore; the last 2 copies are kept. */
    private fun safetyCopy(db: FinanceDatabase) {
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }
        PreMigrationBackups(File(app.noBackupFilesDir, "premigration"))
            .createCopy(app.getDatabasePath(FinanceDatabase.FILE_NAME), FinanceDatabase.SCHEMA_VERSION, System.currentTimeMillis())
    }

    /**
     * R-11: the database key could not be unwrapped. Moves the unreadable database and its wrapped key aside (kept, not
     * deleted); after a restart the app has a new empty database, into which the user restores a backup file.
     */
    suspend fun setAsideUnreadableData() {
        databaseLock.withLock {
            openDatabase?.close()
            openDatabase = null
            EncryptedDatabaseFactory.setAsideUnreadable(app, System.currentTimeMillis())
        }
    }

    /**
     * UC-31: deletes everything this app stores on the phone: the database and its key, the PIN, local copies and
     * reminders. The caller restarts the app afterwards, which then starts from PIN setup.
     */
    suspend fun wipeAll() {
        databaseLock.withLock {
            openDatabase?.close()
            openDatabase = null
            app.deleteDatabase(FinanceDatabase.FILE_NAME)
            app.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
            app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            destroyKeys()
            ph.financewallet.app.reminders.BillReminders
                .cancel(app)
        }
    }

    private companion object {
        const val PIN_ALIAS = "fw_pin_v1"
    }
}

class FinanceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.crashLog.install()
        // Re-arm the daily bill check (BILL-03); inexact, so the OS may batch or delay it.
        ph.financewallet.app.reminders.BillReminders
            .schedule(this)
    }
}
