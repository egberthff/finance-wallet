package ph.financewallet.app.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.categories.CategoryIcon
import ph.financewallet.app.ui.categories.CategoryPicker
import ph.financewallet.app.ui.categories.categoryOptions
import ph.financewallet.app.ui.categories.categoryStyleFor
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
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.ledger.TransactionListItem
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun TransactionsScreen(
    vm: TransactionsViewModel,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    showTitle: Boolean = true,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.loading) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    ListScreen(stringResource(R.string.txn_add), onAdd, listState = vm.listState) {
        if (showTitle) item { ScreenTitle(stringResource(R.string.nav_transactions)) }
        item { FilterBar(state, vm) }
        if (state.items.isEmpty()) {
            item {
                if (state.filtered) {
                    EmptyCard(
                        AppIcons.List,
                        stringResource(R.string.txn_filter_empty_title),
                        stringResource(R.string.txn_filter_empty_message),
                    )
                } else {
                    EmptyCard(AppIcons.List, stringResource(R.string.txn_empty_title), stringResource(R.string.txn_empty_message))
                }
            }
        }
        // Newest first, so grouping keeps the days in order; a day split across pages joins up once both are loaded.
        state.items.groupBy { it.date }.forEach { (date, rows) ->
            item(key = "h$date") { DayHeader(date, state.today) }
            item(key = "d$date") { CardList(rows) { TransactionRow(it, state.categoryStyles, onSelect = { t -> onOpen(t.id) }) } }
        }
        if (state.next != null) {
            item {
                OutlinedButton(onClick = vm::loadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.txn_load_more))
                }
            }
        }
    }
}

/**
 * Account, category (subcategories included) and date-range filters over the same keyset paging (blueprint H). Folded
 * away until wanted; when any filter is on, a summary and "Clear" stay visible so a filtered list is never mistaken for
 * the whole history.
 */
@Composable
private fun FilterBar(
    state: TransactionsState,
    vm: TransactionsViewModel,
) {
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { open = !open }) {
                Text(stringResource(if (open) R.string.txn_filter_hide else R.string.txn_filter))
            }
            if (state.filtered) {
                Text(
                    filterSummary(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { vm.clearFilters() } }) { Text(stringResource(R.string.txn_filter_clear)) }
            }
        }
        if (!open) return@Column
        FieldLabel(stringResource(R.string.field_account))
        val all = Option("", stringResource(R.string.txn_filter_all_accounts))
        val accounts = listOf(all) + state.accounts
        ChoiceChips(accounts, accounts.firstOrNull { it.id == (state.accountId ?: "") }, { optionLabel(it) }, { o ->
            scope.launch { vm.setFilter(accountId = o.id.ifEmpty { null }) }
        })
        FieldLabel(stringResource(R.string.field_category))
        CategoryPicker(
            categoryOptions(state.categoryRows, includeIds = state.categoryRows.map { it.id }.toSet()),
            state.filter.categoryId,
            onSelect = { id -> scope.launch { vm.setFilter(categoryId = id) } },
            onClear = { scope.launch { vm.setFilter(categoryId = null) } },
            placeholder = stringResource(R.string.txn_filter_all_categories),
            noneLabel = stringResource(R.string.txn_filter_all_categories),
        )
        FieldLabel(stringResource(R.string.txn_filter_dates))
        DateRangeField(state.filter.from, state.filter.to) { from, to -> scope.launch { vm.setFilter(from = from, to = to) } }
    }
}

@Composable
private fun filterSummary(state: TransactionsState): String {
    val parts = mutableListOf<String>()
    state.accountId?.let { id -> state.accounts.firstOrNull { it.id == id }?.let { parts += it.label } }
    state.filter.categoryId?.let { id -> state.categoryRows.firstOrNull { it.id == id }?.let { parts += it.name } }
    if (state.filter.from != null || state.filter.to != null) {
        parts += listOfNotNull(state.filter.from?.display(), state.filter.to?.display()).joinToString(" – ")
    }
    return parts.joinToString(" · ")
}

/** A from–to date range chosen with the system date-range picker; "Any dates" clears it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeField(
    from: LocalDate?,
    to: LocalDate?,
    onPick: (LocalDate?, LocalDate?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Text(
                if (from == null && to == null) {
                    stringResource(R.string.txn_filter_any_dates)
                } else {
                    listOfNotNull(from?.display(), to?.display()).joinToString(" – ")
                },
            )
        }
        if (from != null || to != null) TextButton(onClick = { onPick(null, null) }) { Text(stringResource(R.string.txn_filter_any_dates)) }
    }
    if (!open) return
    val picker =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = from?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = to?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                open = false
                onPick(picker.selectedStartDateMillis?.let(::utcDate), picker.selectedEndDateMillis?.let(::utcDate))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
    ) { DateRangePicker(picker, modifier = Modifier.weight(1f)) }
}

private fun utcDate(millis: Long): LocalDate =
    java.time.Instant
        .ofEpochMilli(millis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()

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
    categoryStyles: Map<String, CategoryRow>,
    onSelect: (TransactionListItem) -> Unit,
) {
    val categoryStyle = categoryStyleFor(item, categoryStyles)
    // The date is in the day heading above, so the row shows where the money moved.
    val subtitle =
        listOfNotNull(
            item.counterpartyName?.let { "${item.accountName} → $it" } ?: item.accountName,
            if (item.pending) stringResource(R.string.txn_pending) else null,
        ).joinToString(" · ")
    ListRow(
        txnTitle(item),
        subtitle = subtitle,
        leading = { if (categoryStyle != null) CategoryIcon(categoryStyle) else TxnIcon(item.type, item.amount) },
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

/** TXN-13 fast entry. Amount first, then account and category; everything else is optional ("More details"). */
@Composable
fun EntryScreen(
    vm: EntryViewModel,
    onDone: () -> Unit,
    editTxnId: String? = null,
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
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
            vm.setKind(k)
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
        MoreDetails(form, vm)
        ErrorLine(error)
        Button(
            onClick = {
                scope.launch {
                    error = vm.save()
                    if (error == null) onDone()
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().testTag("save"),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(SPINNER), strokeWidth = 2.dp)
            Text(stringResource(if (saving) R.string.entry_saving else R.string.action_save))
        }
    }
}

/** Optional header fields (TXN-02): merchant, reference number and notes, folded away until wanted. */
@Composable
private fun MoreDetails(
    form: EntryForm,
    vm: EntryViewModel,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val hasAny = form.merchant.isNotBlank() || form.notes.isNotBlank() || form.reference.isNotBlank()
    TextButton(onClick = { open = !open }) {
        Text(stringResource(if (open || hasAny) R.string.entry_less_details else R.string.entry_more_details))
    }
    if (!open && !hasAny) return
    OutlinedTextField(
        form.merchant,
        { t -> vm.edit { it.copy(merchant = t) } },
        label = { Text(stringResource(R.string.field_merchant)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        form.reference,
        { t -> vm.edit { it.copy(reference = t) } },
        label = { Text(stringResource(R.string.field_reference)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        form.notes,
        { t -> vm.edit { it.copy(notes = t) } },
        label = { Text(stringResource(R.string.field_notes)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

private val SPINNER = 18.dp

/**
 * The transaction date, shown as "7 Nov 2026" and chosen with the system date picker. The form keeps ISO text, which
 * the view model validates as before. The picker works in UTC midnight millis, so the day never shifts by time zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
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
            CategoryPicker(
                options.categories(form.kind),
                form.categoryId,
                onSelect = { id -> vm.edit { it.copy(categoryId = id) } },
                recentIds = options.recent(form.kind),
                creator = vm.creator,
                newCategoryClass = if (form.kind == EntryKind.INCOME) AccountClass.INCOME else AccountClass.EXPENSE,
            )
        }
    }
}

/** Accounts as wrapping chips; an archived account kept by an edit is labelled "(archived)". */
@Composable
private fun OptionChips(
    options: List<Option>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    ChoiceChips(options, options.firstOrNull { it.id == selected }, { optionLabel(it) }, { onSelect(it.id) })
}

@Composable
fun optionLabel(option: Option): String =
    if (option.archived) stringResource(R.string.picker_archived_suffix, option.label) else option.label

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
