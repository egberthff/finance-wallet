package ph.financewallet.app.ui.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.financewallet.app.R
import ph.financewallet.app.ui.accounts.CheckRow
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.CardList
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.IconDisc
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.ShareBar
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalFinanceColors
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
    ListScreen {
        item { MutedText(stringResource(R.string.backup_explain)) }
        item {
            val last = state.lastBackupAt
            SurfaceCard {
                ListRow(
                    if (last == null) stringResource(R.string.backup_never) else stringResource(R.string.backup_last, formatMillis(last)),
                    leading = {
                        val fc = LocalFinanceColors.current
                        if (last == null) {
                            IconDisc(AppIcons.Warning, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
                        } else {
                            IconDisc(AppIcons.Save, fc.income, fc.incomeSoft)
                        }
                    },
                )
            }
        }
        item { CreateSection(vm, state) }
        item { RestoreSection(vm, state) }
    }
}

/** Explanatory paragraph under a heading: secondary colour so the figures and actions stay prominent. */
@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    SurfaceCard {
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
    val fc = LocalFinanceColors.current
    val color =
        when {
            strength >= PassphrasePolicy.MAX_STRENGTH - 1 -> fc.income
            strength >= 2 -> fc.warning
            else -> fc.expense
        }
    ShareBar(strength.toFloat() / PassphrasePolicy.MAX_STRENGTH, color)
    Text(
        stringResource(labels[strength.coerceIn(0, labels.lastIndex)]),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    SurfaceCard {
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
                val first = summary.firstTxnDate
                val last = summary.lastTxnDate
                if (first != null && last != null) {
                    Text(stringResource(R.string.backup_summary_range, first.display(), last.display()))
                }
                Text(stringResource(R.string.backup_confirm_warning), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(
                    stringResource(if (busy) R.string.backup_working else R.string.backup_replace_all),
                    color = if (busy) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
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
    crashLog: ph.financewallet.app.diagnostics.CrashLog,
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
    ListScreen {
        item { MutedText(stringResource(R.string.privacy_explain)) }
        item {
            SectionCard(AppIcons.Upload, stringResource(R.string.privacy_export)) {
                MutedText(stringResource(R.string.privacy_export_explain))
                OutlinedButton(
                    onClick = { exportWarning = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.privacy_export)) }
                exportResult?.let { Text(pluralStringResource(R.plurals.privacy_export_done, it, it)) }
                if (exportFailed) ErrorLine(R.string.backup_err_write)
            }
        }
        item { CrashReportsSection(crashLog) }
        item {
            SectionCard(AppIcons.Warning, stringResource(R.string.privacy_delete), danger = true) {
                MutedText(stringResource(R.string.privacy_delete_explain))
                Button(
                    onClick = { deleting = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.privacy_delete)) }
            }
        }
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

/** Local crash reports: the user saves them through the system picker; nothing is sent by the app (ADR-0010). */
@Composable
private fun CrashReportsSection(crashLog: ph.financewallet.app.diagnostics.CrashLog) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(crashLog.reports().size) }
    var saved by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok =
                    withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openOutputStream(uri, "wt")?.let { crashLog.exportTo(it) } }.getOrNull()
                    }
                saved = ok != null
                failed = ok == null
            }
        }
    SectionCard(AppIcons.Shield, stringResource(R.string.crash_title)) {
        MutedText(stringResource(R.string.crash_explain))
        Text(
            if (count == 0) stringResource(R.string.crash_none) else pluralStringResource(R.plurals.crash_count, count, count),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (count > 0) {
            OutlinedButton(
                onClick = { picker.launch("finance-manager-crash-reports-${LocalDate.now(MANILA)}.txt") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.crash_save)) }
            TextButton(onClick = {
                crashLog.clear()
                count = 0
            }) { Text(stringResource(R.string.crash_delete)) }
        }
        if (saved) Text(stringResource(R.string.crash_saved), style = MaterialTheme.typography.bodySmall)
        if (failed) ErrorLine(R.string.backup_err_write)
    }
}

/** A titled card on the privacy screen: icon, heading, then its explanation and actions. */
@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    danger: Boolean = false,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    val fc = LocalFinanceColors.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                if (danger) {
                    IconDisc(icon, fc.expense, fc.expenseSoft)
                } else {
                    IconDisc(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
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
            ) {
                val ready = acknowledged && pin.length == PIN_LENGTH && !busy
                Text(
                    stringResource(R.string.privacy_delete_confirm),
                    color = if (ready) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
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
    ListScreen {
        item { MutedText(stringResource(R.string.insights_explain, r.period.start.display(), r.period.endInclusive.display())) }
        item { FlowCard(r) }
        insightLists(r)
        item { EstimateCard(r) }
    }
}

/** A short "nothing found" line in its own card, so every insight section has the same shape. */
@Composable
private fun NoneCard(text: String) {
    SurfaceCard { Text(text, Modifier.padding(LocalSpacing.current.md), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun FlowCard(r: InsightsReport) {
    SurfaceCard {
        Column(Modifier.padding(LocalSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
            Text(stringResource(R.string.insights_where), style = MaterialTheme.typography.labelLarge)
            LabeledRow(stringResource(R.string.home_income)) { AmountText(r.flow.income) }
            LabeledRow(stringResource(R.string.home_spending)) { AmountText(-r.flow.spending) }
            LabeledRow(stringResource(R.string.insights_moved_savings)) { AmountText(-r.flow.movedToSavings) }
            LabeledRow(stringResource(R.string.insights_left_over)) { AmountText(r.flow.leftOver) }
            Text(
                stringResource(R.string.insights_flow_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.insightLists(r: InsightsReport) {
    val name: (String) -> String = { id -> r.categoryNames[id] ?: id }
    item { SectionTitle(stringResource(R.string.insights_top)) }
    item {
        if (r.topCategories.isEmpty()) {
            NoneCard(stringResource(R.string.insights_none_spending))
        } else {
            CardList(r.topCategories) { (id, total) -> ListRow(name(id)) { AmountText(-total) } }
        }
    }
    item {
        SectionTitle(
            stringResource(R.string.insights_increases, r.previousPeriod.start.display(), r.previousPeriod.endInclusive.display()),
        )
    }
    item {
        if (r.increases.isEmpty()) {
            NoneCard(stringResource(R.string.insights_none_increase))
        } else {
            CardList(r.increases) { c ->
                ListRow(
                    name(c.categoryId),
                    subtitle = stringResource(R.string.insights_increase_line, c.previous.display(), c.current.display()),
                )
            }
        }
    }
    item { SectionTitle(stringResource(R.string.insights_unusual)) }
    item {
        if (r.unusual.isEmpty()) {
            NoneCard(stringResource(R.string.insights_none_unusual))
        } else {
            CardList(r.unusual) { u ->
                ListRow(
                    name(u.expense.categoryId),
                    subtitle =
                        u.expense.date.display() + " · " +
                            stringResource(R.string.insights_unusual_line, u.expense.amount.display(), u.typical.display()),
                )
            }
        }
    }
    item { SectionTitle(stringResource(R.string.insights_bills)) }
    item {
        val b = r.bills
        NoneCard(
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
    SurfaceCard {
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
