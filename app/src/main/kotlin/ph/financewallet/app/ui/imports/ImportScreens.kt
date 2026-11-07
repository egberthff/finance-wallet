package ph.financewallet.app.ui.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.financewallet.app.R
import ph.financewallet.app.ui.accounts.CheckRow
import ph.financewallet.app.ui.categories.CategoryCreator
import ph.financewallet.app.ui.categories.CategoryPicker
import ph.financewallet.app.ui.categories.categoryOptions
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.CardList
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.EmptyCard
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FieldLabel
import ph.financewallet.app.ui.common.IconDisc
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.dateRange
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.imports.PreviewRow
import ph.financewallet.data.imports.RowClass
import ph.financewallet.data.imports.RowDecision
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.importers.FileLimits
import ph.financewallet.importers.RowError

private const val MIME_CSV = "text/csv"
private val PICKER_TYPES =
    arrayOf(
        MIME_CSV,
        "text/comma-separated-values",
        "text/plain",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel",
        "application/pdf",
        "application/octet-stream",
    )

/** Reads at most the import size limit (+1 byte to detect larger files); the rest of the file is never loaded. */
private fun readPicked(
    context: Context,
    uri: Uri,
): Pair<String, ByteArray>? {
    val name =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "statement"
    val cap = FileLimits().maxFileBytes + 1
    val bytes =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var total = 0L
            var n = input.read(buffer)
            while (n >= 0 && total < cap) {
                out.write(buffer, 0, n)
                total += n
                n = input.read(buffer)
            }
            out.toByteArray()
        } ?: return null
    return name to bytes
}

private const val BUFFER = 64 * 1024

@Composable
fun ImportScreen(vm: ImportViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val picked = withContext(Dispatchers.IO) { runCatching { readPicked(context, uri) }.getOrNull() }
                    if (picked != null) vm.load(picked.first, picked.second)
                }
            }
        }
    val spacing = LocalSpacing.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = spacing.md).padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        when (state.step) {
            ImportStep.CHOOSE -> ChooseStep(state, vm, onPick = { picker.launch(PICKER_TYPES) })
            ImportStep.MAP -> MapStep(state, vm)
            ImportStep.PREVIEW -> PreviewStep(state, vm)
            ImportStep.DONE -> DoneStep(state, vm)
        }
    }
}

@Composable
private fun ChooseStep(
    state: ImportState,
    vm: ImportViewModel,
    onPick: () -> Unit,
) {
    Text(stringResource(R.string.import_explain), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.import_account), style = MaterialTheme.typography.labelLarge)
    ChoiceChips(state.accounts, state.accounts.firstOrNull { it.id == state.accountId }, { it.label }, { vm.chooseAccount(it.id) })
    Button(onClick = onPick, enabled = state.accountId != null, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.import_pick))
    }
    ErrorLine(state.error)
}

@Composable
private fun MapStep(
    state: ImportState,
    vm: ImportViewModel,
) {
    val scope = rememberCoroutineScope()
    var profileName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        item { FileInfo(state) }
        item { ColumnFields(state, vm) }
        item { DateFormatPicker(state, vm) }
        if (state.profileId == null) {
            item {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text(stringResource(R.string.import_save_profile)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            ErrorLine(error)
            Button(
                onClick = { scope.launch { error = vm.preview(profileName.ifBlank { null }) } },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.import_show_preview)) }
            TextButton(onClick = vm::restart) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun FileInfo(state: ImportState) {
    val file = state.file
    val detail = file?.table?.encoding ?: file?.format?.name.orEmpty()
    Text(stringResource(R.string.import_file_info, file?.name.orEmpty(), detail), style = MaterialTheme.typography.bodySmall)
    if (state.profileId != null) Text(stringResource(R.string.import_profile_matched), style = MaterialTheme.typography.bodySmall)
}

/** Column choices for date, description, amount (one signed column or debit/credit) and running balance. */
@Composable
private fun ColumnFields(
    state: ImportState,
    vm: ImportViewModel,
) {
    val d = state.draft
    val columns = state.header.mapIndexed { i, h -> Option(i.toString(), h.ifBlank { "#${i + 1}" }) }

    @Composable
    fun pick(
        label: Int,
        selected: Int?,
        change: (MappingDraft, Int?) -> MappingDraft,
    ) = ColumnPicker(stringResource(label), columns, selected) { c -> vm.editDraft { change(it, c) } }
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        pick(R.string.import_col_date, d.date) { m, c -> m.copy(date = c) }
        pick(R.string.import_col_description, d.description) { m, c -> m.copy(description = c) }
        val modeLabel: @Composable (AmountMode) -> String = {
            stringResource(if (it == AmountMode.SIGNED) R.string.import_mode_signed else R.string.import_mode_debit_credit)
        }
        ChoiceChips(AmountMode.entries.toList(), d.mode, modeLabel, { m -> vm.editDraft { it.copy(mode = m) } })
        if (d.mode == AmountMode.SIGNED) {
            pick(R.string.import_col_amount, d.amount) { m, c -> m.copy(amount = c) }
            CheckRow(
                stringResource(R.string.import_outflow_negative),
                d.outflowNegative,
            ) { v -> vm.editDraft { it.copy(outflowNegative = v) } }
        } else {
            pick(R.string.import_col_debit, d.debit) { m, c -> m.copy(debit = c) }
            pick(R.string.import_col_credit, d.credit) { m, c -> m.copy(credit = c) }
        }
        pick(R.string.import_col_balance, d.balance) { m, c -> m.copy(balance = c) }
    }
}

@Composable
private fun ColumnPicker(
    label: String,
    columns: List<Option>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    ChoiceChips(columns, columns.firstOrNull { it.id == selected?.toString() }, { it.label }, { o ->
        onSelect(o.id.toInt().takeIf { it != selected })
    })
}

/** Ambiguous dates (e.g. 03/04/2026) are never guessed: the user chooses between the formats that fit (§4.3). */
@Composable
private fun DateFormatPicker(
    state: ImportState,
    vm: ImportViewModel,
) {
    val candidates = state.dateCandidates
    Text(stringResource(R.string.import_date_format), style = MaterialTheme.typography.labelLarge)
    when {
        candidates.isEmpty() -> {
            Text(stringResource(R.string.import_date_none), style = MaterialTheme.typography.bodySmall)
        }

        candidates.size > 1 && state.draft.datePattern == null -> {
            Text(
                stringResource(R.string.import_date_ambiguous),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        candidates.forEach { p ->
            FilterChip(
                selected = state.draft.datePattern == p,
                onClick = { vm.editDraft { it.copy(datePattern = p) } },
                label = { Text(p) },
            )
        }
    }
}

@Composable
private fun PreviewStep(
    state: ImportState,
    vm: ImportViewModel,
) {
    val preview = state.preview ?: return
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    val importing = state.decisions.values.count { it is RowDecision.Import || it is RowDecision.Transfer }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        item { PreviewSummary(state) }
        items(
            preview.rows,
            key = { it.rowNo },
        ) { r -> PreviewRowView(r, state.decisions[r.rowNo], state.categoryRows, vm.creator) { vm.decide(r.rowNo, it) } }
        item {
            ErrorLine(error)
            Button(onClick = { scope.launch { error = vm.commit() } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(pluralStringResource(R.plurals.import_commit, importing, importing))
            }
            TextButton(onClick = vm::restart) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun PreviewSummary(state: ImportState) {
    val preview = checkNotNull(state.preview)
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                stringResource(
                    R.string.import_counts,
                    preview.count(RowClass.NEW),
                    preview.count(RowClass.EXACT_DUPLICATE),
                    preview.count(RowClass.PROBABLE_DUPLICATE),
                    preview.count(RowClass.REJECTED),
                ),
            )
            val from = preview.from
            val to = preview.to
            if (from != null && to != null) {
                Text(dateRange(from, to), style = MaterialTheme.typography.bodySmall)
            }
            LabeledRow(stringResource(R.string.reports_cash_in)) { AmountText(preview.totalIn) }
            LabeledRow(stringResource(R.string.reports_cash_out)) { AmountText(-preview.totalOut) }
            if (preview.alreadyImportedAt !=
                null
            ) {
                Text(stringResource(R.string.import_already_imported), color = MaterialTheme.colorScheme.error)
            }
            if (preview.balanceBreaks.isNotEmpty()) {
                Text(
                    stringResource(R.string.import_balance_breaks, preview.balanceBreaks.joinToString(", ")),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(stringResource(R.string.import_nothing_saved_yet), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreviewRowView(
    r: PreviewRow,
    decision: RowDecision?,
    categoryRows: List<CategoryRow>,
    creator: CategoryCreator,
    onDecide: (RowDecision) -> Unit,
) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            val row = r.row
            if (row == null) {
                Text(
                    stringResource(R.string.import_row_rejected, r.rowNo, stringResource(rowErrorText(r.error)), r.badCell.orEmpty()),
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            LabeledRow("${row.txnDate} · ${row.description}") { AmountText(row.amount) }
            Text(
                stringResource(classText(r.cls)) + if (row.pending) " · " + stringResource(R.string.txn_pending) else "",
                style = MaterialTheme.typography.bodySmall,
            )
            DecisionChips(r, decision, onDecide)
            if (decision is RowDecision.Import) {
                // Money in takes an income category, money out an expense category; offer only those.
                val income = row.amount.isPositive
                CategoryPicker(
                    categoryOptions(categoryRows, if (income) "INCOME" else "EXPENSE"),
                    decision.categoryId,
                    onSelect = { onDecide(RowDecision.Import(it)) },
                    creator = creator,
                    newCategoryClass = if (income) AccountClass.INCOME else AccountClass.EXPENSE,
                    onClear = { onDecide(RowDecision.Import(null)) },
                )
            }
        }
    }
}

@Composable
private fun DecisionChips(
    r: PreviewRow,
    decision: RowDecision?,
    onDecide: (RowDecision) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        FilterChip(decision is RowDecision.Import, onClick = { onDecide(RowDecision.Import(r.suggestedCategoryId)) }, label = {
            Text(stringResource(if (r.cls == RowClass.EXACT_DUPLICATE) R.string.import_anyway else R.string.import_row_import))
        })
        FilterChip(
            decision == RowDecision.Skip,
            onClick = { onDecide(RowDecision.Skip) },
            label = { Text(stringResource(R.string.bill_skip)) },
        )
        val match = r.matchTxnId
        if (r.cls == RowClass.PROBABLE_DUPLICATE && match != null) {
            FilterChip(decision is RowDecision.Link, onClick = {
                onDecide(RowDecision.Link(match))
            }, label = { Text(stringResource(R.string.import_link)) })
        }
    }
}

private fun classText(c: RowClass): Int =
    when (c) {
        RowClass.NEW -> R.string.import_class_new
        RowClass.EXACT_DUPLICATE -> R.string.import_class_exact
        RowClass.PROBABLE_DUPLICATE -> R.string.import_class_probable
        RowClass.REJECTED -> R.string.import_class_rejected
    }

fun rowErrorText(e: RowError?): Int =
    when (e) {
        RowError.DATE_UNPARSEABLE -> R.string.import_row_err_date
        RowError.DATE_OUT_OF_RANGE -> R.string.import_row_err_date_range
        RowError.AMOUNT_UNPARSEABLE -> R.string.import_row_err_amount
        RowError.AMOUNT_PRECISION -> R.string.import_row_err_precision
        RowError.AMOUNT_ZERO -> R.string.import_row_err_zero
        RowError.DEBIT_AND_CREDIT_BOTH_SET -> R.string.import_row_err_both
        RowError.TYPE_UNKNOWN -> R.string.import_row_err_type
        RowError.DESCRIPTION_EMPTY -> R.string.import_row_err_description
        RowError.CELL_UNREADABLE -> R.string.import_row_err_cell
        null -> R.string.err_generic
    }

@Composable
private fun DoneStep(
    state: ImportState,
    vm: ImportViewModel,
) {
    val result = state.result ?: return
    val scope = rememberCoroutineScope()
    var adjust by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val done = stringResource(R.string.import_reconciled)
    Text(stringResource(R.string.import_done, result.imported, result.duplicates, result.skipped, result.rejected))
    val closing = state.preview?.closingBalance
    if (closing != null) {
        Text(
            stringResource(
                R.string.import_offer_reconcile,
                closing.display(),
                state.preview.to
                    ?.display()
                    .orEmpty(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        CheckRow(stringResource(R.string.accounts_reconcile_adjust), adjust) { adjust = it }
        OutlinedButton(onClick = {
            scope.launch {
                message =
                    if (vm.reconcile(adjust) ==
                        null
                    ) {
                        done
                    } else {
                        null
                    }
            }
        }) { Text(stringResource(R.string.accounts_reconcile_action)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    Button(onClick = vm::restart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_another)) }
}

// ---- history ---------------------------------------------------------------------------------------------------

@Composable
fun ImportHistoryScreen(vm: ImportHistoryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.refresh() }
    val spacing = LocalSpacing.current
    ListScreen {
        if (!state.loading && state.batches.isEmpty()) {
            item {
                EmptyCard(AppIcons.History, stringResource(R.string.import_history_empty), stringResource(R.string.import_history_hint))
            }
        }
        items(state.batches, key = { it.id }) { b ->
            SurfaceCard {
                Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(b.fileName, style = MaterialTheme.typography.titleMedium)
                    val range = b.from?.let { f -> b.to?.let { t -> dateRange(f, t) } }
                    Text(
                        listOfNotNull(state.accountNames[b.accountId], range).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.import_done, b.imported, b.duplicates, b.skipped, b.rejected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (b.rolledBack) {
                        Text(stringResource(R.string.import_rolled_back), style = MaterialTheme.typography.labelMedium)
                    } else {
                        OutlinedButton(
                            onClick = { scope.launch { confirming = b.id to vm.editedCount(b.id) } },
                        ) { Text(stringResource(R.string.import_rollback)) }
                    }
                }
            }
        }
    }
    confirming?.let { (id, edited) -> RollbackDialog(vm, id, edited, onDismiss = { confirming = null }) }
}

@Composable
private fun RollbackDialog(
    vm: ImportHistoryViewModel,
    batchId: String,
    edited: Int,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_rollback)) },
        text = {
            Column {
                Text(stringResource(R.string.import_rollback_explain))
                if (edited >
                    0
                ) {
                    Text(
                        pluralStringResource(R.plurals.import_rollback_edited, edited, edited),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.rollback(batchId, acknowledgeEdited = true)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.import_rollback)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- rules -----------------------------------------------------------------------------------------------------

@Composable
fun RulesScreen(vm: RulesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pattern by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val names = state.names
    val spacing = LocalSpacing.current
    ListScreen {
        item {
            Text(
                stringResource(R.string.rules_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SurfaceCard {
                Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedTextField(pattern, {
                        pattern = it
                    }, label = { Text(stringResource(R.string.rules_pattern)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FieldLabel(stringResource(R.string.field_category))
                    CategoryPicker(state.categories, category, onSelect = { category = it }, creator = vm.creator)
                    CheckRow(stringResource(R.string.rules_auto), auto) { auto = it }
                    ErrorLine(error)
                    Button(onClick = {
                        scope.launch {
                            error = vm.create(pattern, category, auto)
                            if (error == null) pattern = ""
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rules_add)) }
                }
            }
        }
        if (state.rules.isNotEmpty()) {
            item {
                RuleList(
                    state.rules,
                    names,
                    onEnable = { id, v -> scope.launch { vm.setEnabled(id, v) } },
                    onDelete = { id -> scope.launch { vm.delete(id) } },
                )
            }
        }
    }
}

@Composable
private fun RuleList(
    rules: List<ph.financewallet.data.db.entity.CategoryRuleEntity>,
    names: Map<String, String>,
    onEnable: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    CardList(rules) { r ->
        ListRow(
            "\"${r.patternNorm}\"",
            subtitle = "→ " + names[r.categoryId].orEmpty(),
            leading = { IconDisc(AppIcons.Tune, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer) },
        ) {
            // The switch is the rule's on/off state; its label is the rule itself for screen readers.
            Switch(r.enabled, { v -> onEnable(r.id, v) }, Modifier.semantics { contentDescription = r.patternNorm })
            TextButton(onClick = { onDelete(r.id) }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
