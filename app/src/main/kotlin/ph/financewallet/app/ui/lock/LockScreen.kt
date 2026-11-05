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

private val KEY_SIZE = 72.dp
private val DOT_SIZE = 14.dp
private val MESSAGE_HEIGHT = 48.dp
private const val COUNTDOWN_TICK_MS = 500L
private const val MILLIS_PER_SECOND = 1_000L
private val KEYPAD_ROWS = listOf("123", "456", "789")

/**
 * PIN setup / unlock screen (SEC-01). Digits are collected locally and handed off on the 6th digit;
 * the controller wipes them. Keys are 72 dp (UX-04 minimum is 48 dp).
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
    val remaining = rememberLockoutRemaining((state as? LockState.Locked)?.lockedUntilMillis ?: 0L, nowMillis)

    LaunchedEffect(state, biometricAvailable) {
        if (state is LockState.Locked && !state.wrongPin && biometricAvailable) onBiometric()
    }

    val inputEnabled = !busy && remaining == 0L
    val onDigit: (Char) -> Unit = { digit ->
        if (inputEnabled && entered.length < PinHasher.PIN_LENGTH) {
            entered += digit
            if (entered.length == PinHasher.PIN_LENGTH) {
                val pin = entered.toCharArray()
                entered = ""
                onPinComplete(pin)
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(lockTitle(state), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(spacing.md))
            PinDots(entered.length)
            Spacer(Modifier.height(spacing.md))
            Text(
                lockMessage(state, remaining),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(MESSAGE_HEIGHT),
            )
            Spacer(Modifier.height(spacing.md))
            Keypad(
                enabled = inputEnabled,
                showBiometric = state is LockState.Locked && biometricAvailable && !busy,
                canDelete = entered.isNotEmpty(),
                onDigit = onDigit,
                onDelete = { entered = entered.dropLast(1) },
                onBiometric = onBiometric,
            )
        }
    }
}

/** Milliseconds left in an active lockout, ticking down while displayed. */
@Composable
private fun rememberLockoutRemaining(
    lockedUntil: Long,
    nowMillis: () -> Long,
): Long {
    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lockedUntil) {
        do {
            remaining = (lockedUntil - nowMillis()).coerceAtLeast(0L)
            if (remaining > 0L) delay(COUNTDOWN_TICK_MS)
        } while (remaining > 0L)
    }
    return remaining
}

@Composable
private fun lockTitle(state: LockState): String =
    when (state) {
        is LockState.NeedsSetup -> stringResource(R.string.lock_setup_title)
        LockState.ConfirmSetup -> stringResource(R.string.lock_setup_confirm_title)
        else -> stringResource(R.string.lock_unlock_title)
    }

@Composable
private fun lockMessage(
    state: LockState,
    remainingMillis: Long,
): String =
    when {
        state is LockState.NeedsSetup && state.mismatch -> {
            stringResource(R.string.lock_setup_mismatch)
        }

        state is LockState.NeedsSetup -> {
            stringResource(R.string.lock_setup_explainer)
        }

        remainingMillis > 0 -> {
            val seconds = ((remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
            stringResource(R.string.lock_locked_out, seconds)
        }

        state is LockState.Locked && state.wrongPin -> {
            stringResource(R.string.lock_wrong_pin)
        }

        else -> {
            ""
        }
    }

@Composable
private fun PinDots(entered: Int) {
    val spacing = LocalSpacing.current
    val label = stringResource(R.string.lock_digit_entered, entered)
    Row(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        repeat(PinHasher.PIN_LENGTH) { i ->
            val filled = i < entered
            Surface(
                Modifier.size(DOT_SIZE),
                shape = CircleShape,
                color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    showBiometric: Boolean,
    canDelete: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onBiometric: () -> Unit,
) {
    val spacing = LocalSpacing.current
    KEYPAD_ROWS.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            row.forEach { d -> KeyButton(d.toString(), enabled) { onDigit(d) } }
        }
        Spacer(Modifier.height(spacing.md))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
            if (showBiometric) {
                TextButton(onClick = onBiometric) {
                    Text(stringResource(R.string.lock_use_biometric), textAlign = TextAlign.Center)
                }
            }
        }
        KeyButton("0", enabled) { onDigit('0') }
        Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
            TextButton(onClick = onDelete, enabled = canDelete) {
                Text(stringResource(R.string.lock_delete))
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(KEY_SIZE)) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}
