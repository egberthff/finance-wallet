package ph.financewallet.app

import android.app.Application
import java.io.File
import ph.financewallet.app.security.AppLockController
import ph.financewallet.app.security.PinStore
import ph.financewallet.data.db.EncryptedDatabaseFactory
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.security.AndroidKeystoreKeyWrapper

/**
 * Manual dependency container (deliberately not Hilt for now: fewer dependencies, see PROGRESS.md decision log).
 * The database opens lazily, so nothing touches the key store until it is first needed.
 */
class AppContainer(app: Application) {
    val lockController: AppLockController by lazy {
        AppLockController(PinStore(File(app.noBackupFilesDir, "lock"), AndroidKeystoreKeyWrapper("fw_pin_v1")))
    }

    val database: FinanceDatabase by lazy { EncryptedDatabaseFactory.open(app) }
}

class FinanceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
