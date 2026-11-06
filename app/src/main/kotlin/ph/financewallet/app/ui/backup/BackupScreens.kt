package ph.financewallet.app.ui.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.accounts.CheckRow
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.backup.BackupSummary
import ph.financewallet.data.backup.PassphrasePolicy
import ph.financewallet.data.insight.InsightsReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BACKUP_MIME = "application/octet-stream"
private val MANILA: ZoneId = ZoneId.of("Asia/Manila")
private val WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH)

private fun formatMillis(ms: Long): String = Instant.ofEpochMilli(ms).atZone(MANILA).format(WHEN)

/** Removes a half-written file the picker created, so a failed backup never looks like a usable one. */
private fun deletePartial(
    context: Context,
    uri: Uri,
) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
}

@Composable
fun BackupScreen(vm: BackupViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    if (state.loading) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item { SectionTitle(stringResource(R.string.backup_title)) }
        item { Text(stringResource(R.string.backup_explain), style = MaterialTheme.typography.bodySmall) }
        item {
            val last = state.lastBackupAt
            Text(
                if (last == null) stringResource(R.string.backup_never) else stringResource(R.string.backup_last, formatMillis(last)),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        item { CreateSection(vm, state) }
        item { RestoreSection(vm, state) }
    }
}

@Composable
private fun CreateSection(
    vm: BackupViewModel,
    state: BackupState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                error = vm.create({ context.contentResolver.openOutputStream(uri, "wt") }, pass, confirm)
                if (error == null) {
                    pass = ""
                    confirm = ""
                } else {
                    deletePartial(context, uri)
                }
            }
        }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
            Text(stringResource(R.string.backup_create), style = MaterialTheme.typography.titleSmall)
            PassphraseField(pass, { pass = it }, stringResource(R.string.backup_passphrase))
            StrengthMeter(vm.strength(pass))
            PassphraseField(confirm, { confirm = it }, stringResource(R.string.backup_passphrase_confirm))
            Text(stringResource(R.string.backup_unrecoverable), style = MaterialTheme.typography.bodySmall)
            CheckRow(stringResource(R.string.backup_understood), understood) { understood = it }
            ErrorLine(error)
            state.createdRows?.let { Text(pluralStringResource(R.plurals.backup_created, it, it)) }
            Button(
                onClick = {
                    error = vm.passphraseProblem(pass, confirm)
                    if (error == null) picker.launch("finance-manager-${LocalDate.now(MANILA)}.fwbak")
                },
                enabled = understood && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (state.busy) R.string.backup_working else R.string.backup_choose_save)) }
        }
    }
}

@Composable
private fun PassphraseField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
) = OutlinedTextField(
    value = value,
    onValueChange = onValue,
    label = { Text(label) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun StrengthMeter(strength: Int) {
    val labels =
        listOf(
            R.string.backup_strength_0,
            R.string.backup_strength_1,
            R.string.backup_strength_2,
            R.string.backup_strength_3,
            R.string.backup_strength_4,
        )
    LinearProgressIndicator(
        progress = { strength.toFloat() / PassphrasePolicy.MAX_STRENGTH },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(labels[strength.coerceIn(0, labels.lastIndex)]), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RestoreSection(
    vm: BackupViewModel,
    state: BackupState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val open: () -> java.io.InputStream? = { file?.let { context.contentResolver.openInputStream(it) } }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            file = uri
            error = null
        }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
            Text(stringResource(R.string.backup_restore), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.backup_restore_explain), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (file == null) R.string.backup_choose_file else R.string.backup_file_chosen))
            }
            if (file != null) {
                PassphraseField(pass, { pass = it }, stringResource(R.string.backup_passphrase))
                Button(
                    onClick = { scope.launch { error = vm.inspect(open, pass) } },
                    enabled = pass.isNotEmpty() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (state.busy) R.string.backup_working else R.string.backup_check)) }
            }
            ErrorLine(error)
            state.restored?.let { Text(stringResource(R.string.backup_restored, formatMillis(it.createdAt))) }
        }
    }
    state.pending?.let { summary ->
        ConfirmRestore(summary, busy = state.busy, onDismiss = vm::cancelRestore) {
            scope.launch {
                error = vm.restore(open, pass)
                if (error == null) {
                    pass = ""
                    file = null
                } else {
                    vm.cancelRestore()
                }
            }
        }
    }
}

/** Step 6: what the file contains, and an explicit "Replace all current data". */
@Composable
private fun ConfirmRestore(
    summary: BackupSummary,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
                Text(stringResource(R.string.backup_summary_made, formatMillis(summary.createdAt)))
                Text(pluralStringResource(R.plurals.backup_summary_accounts, summary.accounts, summary.accounts))
                Text(pluralStringResource(R.plurals.backup_summary_txns, summary.transactions, summary.transactions))
                if (summary.firstTxnDate != null && summary.lastTxnDate != null) {
                    Text(stringResource(R.string.backup_summary_range, summary.firstTxnDate.toString(), summary.lastTxnDate.toString()))
                }
                Text(stringResource(R.string.backup_confirm_warning), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(if (busy) R.string.backup_working else R.string.backup_replace_all))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** PRIV-01/02: plaintext export of everything, and delete all data (UC-31). */
@Composable
fun PrivacyScreen(
    backup: BackupViewModel,
    delete: DeleteAllViewModel,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportWarning by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<Int?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                exportResult = backup.exportPlaintext { context.contentResolver.openOutputStream(uri, "wt") }
                exportFailed = exportResult == null
                if (exportFailed) deletePartial(context, uri)
            }
        }
    val spacing = LocalSpacing.current
    Column(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionTitle(stringResource(R.string.privacy_title))
        Text(stringResource(R.string.privacy_explain), style = MaterialTheme.typography.bodySmall)
        SectionTitle(stringResource(R.string.privacy_export))
        Text(stringResource(R.string.privacy_export_explain), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { exportWarning = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.privacy_export)) }
        exportResult?.let { Text(pluralStringResource(R.plurals.privacy_export_done, it, it)) }
        if (exportFailed) ErrorLine(R.string.backup_err_write)
        SectionTitle(stringResource(R.string.privacy_delete))
        Text(stringResource(R.string.privacy_delete_explain), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { deleting = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.privacy_delete)) }
    }
    if (exportWarning) {
        AlertDialog(
            onDismissRequest = { exportWarning = false },
            title = { Text(stringResource(R.string.privacy_export)) },
            text = { Text(stringResource(R.string.privacy_export_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    exportWarning = false
                    picker.launch("finance-manager-export-${LocalDate.now(MANILA)}.jsonl")
                }) { Text(stringResource(R.string.privacy_export_continue)) }
            },
            dismissButton = { TextButton(onClick = { exportWarning = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleting) DeleteAllDialog(delete, onDismiss = { deleting = false }, onDeleted = onDeleted)
}

/** Two steps: an explicit acknowledgement, then the app PIN (failures count towards the lockout). */
@Composable
private fun DeleteAllDialog(
    vm: DeleteAllViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var acknowledged by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.privacy_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                Text(stringResource(R.string.privacy_delete_warning), color = MaterialTheme.colorScheme.error)
                CheckRow(stringResource(R.string.privacy_delete_ack), acknowledged) { acknowledged = it }
                if (acknowledged) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { v -> pin = v.filter(Char::isDigit).take(PIN_LENGTH) },
                        label = { Text(stringResource(R.string.privacy_delete_pin)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                if (wrong) ErrorLine(R.string.privacy_delete_wrong_pin)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        val done = vm.deleteAll(pin)
                        pin = ""
                        wrong = !done
                        if (done) onDeleted()
                    }
                },
                enabled = acknowledged && pin.length == PIN_LENGTH && !busy,
            ) { Text(stringResource(R.string.privacy_delete_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val PIN_LENGTH = 6

/** INS-01..03. Every figure comes from recorded transactions; only the saving estimate is labelled as an estimate. */
@Composable
fun InsightsScreen(vm: InsightsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val r = state.report
    if (state.loading || r == null) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item { SectionTitle(stringResource(R.string.insights_title)) }
        item {
            Text(
                stringResource(R.string.insights_explain, r.period.start.toString(), r.period.endInclusive.toString()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { FlowCard(r) }
        insightLists(r)
        item { EstimateCard(r) }
    }
}

@Composable
private fun FlowCard(r: InsightsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
            Text(stringResource(R.string.insights_where), style = MaterialTheme.typography.labelLarge)
            LabeledRow(stringResource(R.string.home_income)) { AmountText(r.flow.income) }
            LabeledRow(stringResource(R.string.home_spending)) { AmountText(-r.flow.spending) }
            LabeledRow(stringResource(R.string.insights_moved_savings)) { AmountText(-r.flow.movedToSavings) }
            LabeledRow(stringResource(R.string.insights_left_over)) { AmountText(r.flow.leftOver) }
            Text(stringResource(R.string.insights_flow_caption), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.insightLists(r: InsightsReport) {
    val name: (String) -> String = { id -> r.categoryNames[id] ?: id }
    item { SectionTitle(stringResource(R.string.insights_top)) }
    if (r.topCategories.isEmpty()) item { Text(stringResource(R.string.insights_none_spending)) }
    items(r.topCategories, key = { "t" + it.first }) { (id, total) -> LabeledRow(name(id)) { AmountText(-total) } }
    item {
        SectionTitle(
            stringResource(R.string.insights_increases, r.previousPeriod.start.toString(), r.previousPeriod.endInclusive.toString()),
        )
    }
    if (r.increases.isEmpty()) item { Text(stringResource(R.string.insights_none_increase)) }
    items(r.increases, key = { "c" + it.categoryId }) { c ->
        LabeledRow(name(c.categoryId)) {
            Text(stringResource(R.string.insights_increase_line, c.previous.display(), c.current.display()))
        }
    }
    item { SectionTitle(stringResource(R.string.insights_unusual)) }
    if (r.unusual.isEmpty()) item { Text(stringResource(R.string.insights_none_unusual)) }
    items(r.unusual, key = { "u" + it.expense.txnId }) { u ->
        LabeledRow("${name(u.expense.categoryId)} · ${u.expense.date}") {
            Text(stringResource(R.string.insights_unusual_line, u.expense.amount.display(), u.typical.display()))
        }
    }
    item { SectionTitle(stringResource(R.string.insights_bills)) }
    item {
        val b = r.bills
        Text(
            if (b.shortfall.isZero) {
                stringResource(R.string.insights_bills_covered, b.dueSoon.display(), b.freeMoney.display())
            } else {
                stringResource(R.string.insights_bills_short, b.dueSoon.display(), b.freeMoney.display(), b.shortfall.display())
            },
        )
    }
}

@Composable
private fun EstimateCard(r: InsightsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
            Text(stringResource(R.string.insights_capacity), style = MaterialTheme.typography.labelLarge)
            val c = r.savingCapacity
            if (c == null) {
                Text(stringResource(R.string.insights_capacity_none))
            } else {
                AmountText(c.perMonth, emphasize = true)
                Text(
                    pluralStringResource(R.plurals.insights_capacity_caption, c.monthsUsed, c.monthsUsed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
