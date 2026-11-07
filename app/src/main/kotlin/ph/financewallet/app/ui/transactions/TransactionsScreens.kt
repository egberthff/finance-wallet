package ph.financewallet.app.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.CardList
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.EmptyCard
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FieldLabel
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.ScreenTitle
import ph.financewallet.app.ui.common.TxnAmount
import ph.financewallet.app.ui.common.TxnIcon
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.common.displayWithWeekday
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.ledger.TransactionListItem
import ph.financewallet.domain.ledger.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun TransactionsScreen(
    vm: TransactionsViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<TransactionListItem?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    if (state.loading) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    ListScreen(stringResource(R.string.txn_add), onAdd) {
        item { ScreenTitle(stringResource(R.string.nav_transactions)) }
        if (state.items.isEmpty()) {
            item { EmptyCard(AppIcons.List, stringResource(R.string.txn_empty_title), stringResource(R.string.txn_empty_message)) }
        }
        // Newest first, so grouping keeps the days in order; a day split across pages joins up once both are loaded.
        state.items.groupBy { it.date }.forEach { (date, rows) ->
            item(key = "h$date") { DayHeader(date, state.today) }
            item(key = "d$date") { CardList(rows) { TransactionRow(it, onSelect = { t -> deleting = t }) } }
        }
        if (state.next != null) {
            item {
                OutlinedButton(onClick = vm::loadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.txn_load_more))
                }
            }
        }
    }
    deleting?.let { item -> DeleteDialog(vm, item, onEdit = { onEdit(item.id) }, onDismiss = { deleting = null }) }
}

@Composable
private fun DayHeader(
    date: LocalDate,
    today: LocalDate?,
) {
    val label =
        when (date) {
            today -> stringResource(R.string.txn_today)
            today?.minusDays(1) -> stringResource(R.string.txn_yesterday)
            else -> date.displayWithWeekday()
        }
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LocalSpacing.current.sm).semantics { heading() },
    )
}

@Composable
private fun TransactionRow(
    item: TransactionListItem,
    onSelect: (TransactionListItem) -> Unit,
) {
    // The date is in the day heading above, so the row shows where the money moved.
    val subtitle =
        listOfNotNull(
            item.counterpartyName?.let { "${item.accountName} → $it" } ?: item.accountName,
            if (item.pending) stringResource(R.string.txn_pending) else null,
        ).joinToString(" · ")
    ListRow(
        txnTitle(item),
        subtitle = subtitle,
        leading = { TxnIcon(item.type, item.amount) },
        onClick = { onSelect(item) },
    ) { TxnAmount(item.type, item.amount) }
}

@Composable
fun txnTitle(item: TransactionListItem): String {
    val label = item.description.ifBlank { item.merchant.orEmpty() }
    if (label.isNotBlank()) return label
    if (item.categoryNames.isNotEmpty()) return item.categoryNames.joinToString(", ")
    return stringResource(typeLabel(item.type))
}

@Composable
private fun DeleteDialog(
    vm: TransactionsViewModel,
    item: TransactionListItem,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.txn_actions_title)) },
        text = {
            Column {
                Text(stringResource(R.string.txn_delete_message))
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.delete(item.id)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onEdit()
            }) { Text(stringResource(R.string.action_edit)) }
        },
    )
}

/** TXN-13 fast entry. Amount first, then account and category chips; everything else is optional. */
@Composable
fun EntryScreen(
    vm: EntryViewModel,
    onDone: () -> Unit,
    editTxnId: String? = null,
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(editTxnId) {
        if (editTxnId != null && !vm.isEditing && !vm.loadExisting(editTxnId)) error = R.string.err_edit_not_supported
    }
    val spacing = LocalSpacing.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md)
            .padding(bottom = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        ChoiceChips(EntryKind.entries.toList(), form.kind, { stringResource(kindLabel(it)) }, { k ->
            vm.edit { it.copy(kind = k, categoryId = null) }
            // A message about the previous type (e.g. "Choose a category") no longer applies.
            error = null
        })
        AmountField(
            form.amount,
            { t -> vm.edit { it.copy(amount = t) } },
            stringResource(R.string.field_amount),
            null,
            Modifier.testTag("amount"),
        )
        EntryChoices(vm, form, options)
        DateField(form.date) { iso -> vm.edit { it.copy(date = iso) } }
        OutlinedTextField(
            form.description,
            { t -> vm.edit { it.copy(description = t) } },
            label = { Text(stringResource(R.string.field_description)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(error)
        Button(
            onClick = {
                scope.launch {
                    error = vm.save()
                    if (error == null) onDone()
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("save"),
        ) { Text(stringResource(R.string.action_save)) }
    }
}

/**
 * The transaction date, shown as "7 Nov 2026" and chosen with the system date picker. The form keeps ISO text, which
 * the view model validates as before. The picker works in UTC midnight millis, so the day never shifts by time zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    iso: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(iso) }.getOrNull()
    val pickLabel = stringResource(R.string.date_pick)
    Box {
        OutlinedTextField(
            date?.display() ?: iso,
            {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_date)) },
            trailingIcon = { Icon(AppIcons.Event, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(onClickLabel = pickLabel) { open = true })
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        onPick(
                            Instant
                                .ofEpochMilli(it)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString(),
                        )
                    }
                    open = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(picker, title = { Text(pickLabel, Modifier.padding(LocalSpacing.current.md)) }) }
    }
}

@Composable
private fun EntryChoices(
    vm: EntryViewModel,
    form: EntryForm,
    options: EntryOptions,
) {
    FieldLabel(stringResource(if (form.kind == EntryKind.TRANSFER) R.string.field_from_account else R.string.field_account))
    OptionChips(options.accounts, form.accountId) { id -> vm.edit { it.copy(accountId = id) } }
    when (form.kind) {
        EntryKind.TRANSFER -> {
            FieldLabel(stringResource(R.string.field_to_account))
            OptionChips(
                options.accounts.filter { it.id != form.accountId },
                form.toAccountId,
            ) { id -> vm.edit { it.copy(toAccountId = id) } }
        }

        else -> {
            FieldLabel(stringResource(R.string.field_category))
            val categories = if (form.kind == EntryKind.EXPENSE) options.expenseCategories else options.incomeCategories
            if (categories.isEmpty()) Text(stringResource(R.string.entry_no_categories), style = MaterialTheme.typography.bodySmall)
            OptionChips(categories, form.categoryId) { id -> vm.edit { it.copy(categoryId = id) } }
        }
    }
}

@Composable
private fun OptionChips(
    options: List<Option>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    ChoiceChips(options, options.firstOrNull { it.id == selected }, { it.label }, { onSelect(it.id) })
}

private fun kindLabel(k: EntryKind): Int =
    when (k) {
        EntryKind.EXPENSE -> R.string.kind_expense
        EntryKind.INCOME -> R.string.kind_income
        EntryKind.TRANSFER -> R.string.kind_transfer
    }

fun typeLabel(t: TxnType): Int =
    when (t) {
        TxnType.INCOME -> R.string.kind_income
        TxnType.EXPENSE -> R.string.kind_expense
        TxnType.TRANSFER -> R.string.kind_transfer
        TxnType.SAVINGS_CONTRIBUTION -> R.string.txn_type_savings_contribution
        TxnType.SAVINGS_WITHDRAWAL -> R.string.txn_type_savings_withdrawal
        TxnType.REFUND -> R.string.txn_type_refund
        TxnType.REVERSAL -> R.string.txn_type_reversal
        TxnType.BALANCE_ADJUSTMENT -> R.string.txn_type_adjustment
        TxnType.DEBT_PAYMENT -> R.string.txn_type_debt_payment
        TxnType.DEBT_DISBURSEMENT -> R.string.txn_type_debt_disbursement
        TxnType.OPENING_BALANCE -> R.string.txn_type_opening
    }
