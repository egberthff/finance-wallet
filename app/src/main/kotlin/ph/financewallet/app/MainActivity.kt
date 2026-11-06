package ph.financewallet.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.financewallet.app.security.LockState
import ph.financewallet.app.ui.components.ErrorState
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.lock.LockScreen
import ph.financewallet.app.ui.navigation.AppNavigation
import ph.financewallet.app.ui.navigation.PrivacyActions
import ph.financewallet.app.ui.theme.FinanceTheme
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
    private var openError by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
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
        when {
            current != null -> AppNavigation(current, privacyActions())
            openError -> ErrorState(getString(R.string.db_open_error_title), getString(R.string.db_open_error_message))
            else -> LoadingState(getString(R.string.loading))
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { openServices() }
    }

    private fun privacyActions(): PrivacyActions {
        val container = (application as FinanceApp).container
        return PrivacyActions(confirmPin = lock::confirmPin, wipe = container::wipeAll, onDeleted = ::restartAfterWipe)
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
            android.util.Log.e("FinanceWallet", "database open failed", e)
            openError = true
        }
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
