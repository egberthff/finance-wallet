package ph.financewallet.app

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
import ph.financewallet.app.ui.lock.LockScreen
import ph.financewallet.app.ui.navigation.AppNavigation
import ph.financewallet.app.ui.theme.FinanceTheme

/**
 * Single activity. FragmentActivity because BiometricPrompt requires it.
 * FLAG_SECURE hides content from recents and screenshots (T2, T11; on by default, a user toggle comes later).
 */
class MainActivity : FragmentActivity() {
    private val lock get() = (application as FinanceApp).container.lockController

    private var lockState by mutableStateOf<LockState>(LockState.NeedsSetup())
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lockState = lock.state
        setContent {
            FinanceTheme {
                if (lockState == LockState.Unlocked) {
                    AppNavigation()
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
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lockState = lock.onBiometricSuccess()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_biometric_title))
                .setNegativeButtonText(getString(R.string.lock_biometric_negative))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build(),
        )
    }
}
