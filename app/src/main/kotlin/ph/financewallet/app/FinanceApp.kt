package ph.financewallet.app

import android.app.Application
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ph.financewallet.app.security.AppLockController
import ph.financewallet.app.security.PinStore
import ph.financewallet.data.db.EncryptedDatabaseFactory
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.security.AndroidKeystoreKeyWrapper
import java.io.File

/**
 * Manual dependency container (deliberately not Hilt for now: fewer dependencies, see PROGRESS.md decision log).
 * The database opens lazily, so nothing touches the key store until it is first needed.
 */
class AppContainer(
    private val app: Application,
) {
    val lockController: AppLockController by lazy {
        AppLockController(PinStore(File(app.noBackupFilesDir, "lock"), AndroidKeystoreKeyWrapper("fw_pin_v1")))
    }

    private val databaseLock = Mutex()
    private var openDatabase: FinanceDatabase? = null

    /** Opens the database once (migrating with a pre-migration backup if needed). Must be called off the main thread. */
    suspend fun database(): FinanceDatabase =
        databaseLock.withLock {
            openDatabase ?: EncryptedDatabaseFactory.open(app).also { openDatabase = it }
        }
}

class FinanceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
