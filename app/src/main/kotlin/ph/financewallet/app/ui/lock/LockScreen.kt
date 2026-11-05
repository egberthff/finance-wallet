package ph.financewallet.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ph.financewallet.app.R
import ph.financewallet.app.security.LockState
import ph.financewallet.app.security.PinHasher
import ph.financewallet.app.ui.theme.LocalSpacing

/**
 * PIN setup / unlock screen (SEC-01). Digits are kept in a CharArray-backed buffer and handed off
 * on the 6th digit; the controller wipes them. Touch targets are >= 64 dp (UX-04).
 */
@Composable
fun LockScreen(
    state: LockState,
    busy: Boolean,
    biometricAvailable: Boolean,
    onPinComplete: (CharArray) -> Unit,
    onBiometric: () -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val spacing = LocalSpacing.current
    var entered by remember(state) { mutableStateOf("") }
    var remaining by remember { mutableLongStateOf(0L) }

    val lockedUntil = (state as? LockState.Locked)?.lockedUntilMillis ?: 0L
    LaunchedEffect(lockedUntil) {
        while (true) {
            remaining = (lockedUntil - nowMillis()).coerceAtLeast(0L)
            if (remaining == 0L) break
            delay(500)
        }
    }
    LaunchedEffect(state, biometricAvailable) {
        if (state is LockState.Locked && !state.wrongPin && biometricAvailable) onBiometric()
    }

    val title = when (state) {
        is LockState.NeedsSetup -> stringResource(R.string.lock_setup_title)
        LockState.ConfirmSetup -> stringResource(R.string.lock_setup_confirm_title)
        else -> stringResource(R.string.lock_unlock_title)
    }
    val message = when {
        state is LockState.NeedsSetup && state.mismatch -> stringResource(R.string.lock_setup_mismatch)
        state is LockState.NeedsSetup -> stringResource(R.string.lock_setup_explainer)
        remaining > 0 -> stringResource(R.string.lock_locked_out, ((remaining + 999) / 1000).toInt())
        state is LockState.Locked && state.wrongPin -> stringResource(R.string.lock_wrong_pin)
        else -> ""
    }
    val inputEnabled = !busy && remaining == 0L

    fun press(digit: Char) {
        if (!inputEnabled || entered.length >= PinHasher.PIN_LENGTH) return
        entered += digit
        if (entered.length == PinHasher.PIN_LENGTH) {
            val pin = entered.toCharArray()
            entered = ""
            onPinComplete(pin)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(spacing.md))
            val dotsLabel = stringResource(R.string.lock_digit_entered, entered.length)
            Row(Modifier.semantics { contentDescription = dotsLabel }, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                repeat(PinHasher.PIN_LENGTH) { i ->
                    Surface(
                        Modifier.size(14.dp),
                        shape = CircleShape,
                        color = if (i < entered.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ) {}
                }
            }
            Spacer(Modifier.height(spacing.md))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(48.dp),
            )
            Spacer(Modifier.height(spacing.md))
            val rows = listOf("123", "456", "789")
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    row.forEach { d -> KeyButton(d.toString(), inputEnabled) { press(d) } }
                }
                Spacer(Modifier.height(spacing.md))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                    if (state is LockState.Locked && biometricAvailable) {
                        TextButton(onClick = onBiometric, enabled = !busy) {
                            Text(stringResource(R.string.lock_use_biometric), textAlign = TextAlign.Center)
                        }
                    }
                }
                KeyButton("0", inputEnabled) { press('0') }
                Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { entered = entered.dropLast(1) }, enabled = entered.isNotEmpty()) {
                        Text(stringResource(R.string.lock_delete))
                    }
                }
            }
        }
    }
}

private val KEY_SIZE = 72.dp

@Composable
private fun KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(KEY_SIZE)) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}
