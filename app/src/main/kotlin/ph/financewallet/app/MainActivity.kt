package ph.financewallet.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.financewallet.app.security.LockState
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.components.OpenErrorScreen
import ph.financewallet.app.ui.lock.LockScreen
import ph.financewallet.app.ui.navigation.AppNavigation
import ph.financewallet.app.ui.navigation.PrivacyActions
import ph.financewallet.app.ui.theme.FinanceTheme
import ph.financewallet.data.security.DatabaseKeyUnavailableException
import kotlin.system.exitProcess

/**
 * Single activity. FragmentActivity because BiometricPrompt requires it.
 * FLAG_SECURE hides content from recents and screenshots (T2, T11; on by default, a user toggle comes later).
 */
class MainActivity : FragmentActivity() {
    private val lock get() = (application as FinanceApp).container.lockController

    private var lockState by mutableStateOf<LockState>(LockState.NeedsSetup())
    private var busy by mutableStateOf(false)
    private var services by mutableStateOf<AppServices?>(null)
    private var openFailure by mutableStateOf<OpenFailure?>(null)
    private var openAttempt by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Release: always on. Debug builds turn it off (res/debug) so UI screenshots can be taken during development.
        if (resources.getBoolean(R.bool.secure_window)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        lockState = lock.state
        setContent {
            FinanceTheme {
                if (lockState == LockState.Unlocked) {
                    UnlockedContent()
                } else {
                    LockScreen(
                        state = lockState,
                        busy = busy,
                        biometricAvailable = biometricAvailable(),
                        onPinComplete = ::submitPin,
                        onBiometric = ::showBiometricPrompt,
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun UnlockedContent() {
        val current = services
        val failure = openFailure
        when {
            current != null -> {
                AppNavigation(current, privacyActions())
            }

            failure != null -> {
                OpenErrorScreen(
                    keyLost = failure == OpenFailure.KEY_LOST,
                    onRetry = {
                        openFailure = null
                        openAttempt++
                    },
                    onStartOver = ::startOverAfterKeyLoss,
                )
            }

            else -> {
                LoadingState(getString(R.string.loading))
            }
        }
        androidx.compose.runtime.LaunchedEffect(openAttempt) { openServices() }
    }

    private fun privacyActions(): PrivacyActions {
        val container = (application as FinanceApp).container
        return PrivacyActions(
            confirmPin = lock::confirmPin,
            wipe = container::wipeAll,
            onDeleted = ::restartAfterWipe,
            crashLog = container.crashLog,
        )
    }

    /** R-11: keep the unreadable data aside, restart with an empty database; the user then restores a backup file. */
    private fun startOverAfterKeyLoss() {
        lifecycleScope.launch {
            val moved =
                runCatching { withContext(Dispatchers.IO) { (application as FinanceApp).container.setAsideUnreadableData() } }
            if (moved.isSuccess) restartAfterWipe() else openFailure = OpenFailure.OTHER
        }
    }

    /**
     * After delete-all the process still holds stale state (open services, the unlocked lock), so the app starts again in
     * a new process, which begins at PIN setup.
     */
    private fun restartAfterWipe() {
        packageManager.getLaunchIntentForPackage(packageName)?.component?.let { startActivity(Intent.makeRestartActivityTask(it)) }
        finishAffinity()
        exitProcess(0)
    }

    /** Opens the encrypted database (and migrates with a backup) off the main thread, once per process. */
    private suspend fun openServices() {
        if (services != null) return
        try {
            val container = (application as FinanceApp).container
            services = withContext(Dispatchers.IO) { container.services() }
        } catch (
            // Any failure to open must show an error screen, never a crash or a half-working app (UX-05).
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Type only: exception messages can contain file names or SQL values (SEC logging rule).
            android.util.Log.e("FinanceWallet", "database open failed: ${e.javaClass.name}")
            openFailure = if (e.isKeyLoss()) OpenFailure.KEY_LOST else OpenFailure.OTHER
        }
    }

    private fun Throwable.isKeyLoss(): Boolean =
        generateSequence(this) {
            it.cause
        }.take(MAX_CAUSES).any { it is DatabaseKeyUnavailableException }

    private enum class OpenFailure { KEY_LOST, OTHER }

    private companion object {
        const val MAX_CAUSES = 8
    }

    override fun onStart() {
        super.onStart()
        lock.onForegrounded()
        lockState = lock.state
    }

    override fun onStop() {
        lock.onBackgrounded()
        super.onStop()
    }

    private fun submitPin(pin: CharArray) {
        busy = true
        lifecycleScope.launch {
            // PBKDF2 is deliberately slow: never run it on the main thread.
            val next = withContext(Dispatchers.Default) { lock.submitPin(pin) }
            lockState = next
            busy = false
        }
    }

    private fun biometricAvailable(): Boolean =
        lock.state !is LockState.NeedsSetup && lock.state != LockState.ConfirmSetup &&
            BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun showBiometricPrompt() {
        if (!biometricAvailable()) return
        val prompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        lockState = lock.onBiometricSuccess()
                    }
                },
            )
        prompt.authenticate(
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(getString(R.string.lock_biometric_title))
                .setNegativeButtonText(getString(R.string.lock_biometric_negative))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build(),
        )
    }
}
