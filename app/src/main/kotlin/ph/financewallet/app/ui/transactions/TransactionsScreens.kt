package ph.financewallet.app.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.components.EmptyState
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.ledger.TransactionListItem
import ph.financewallet.domain.ledger.TxnType

@Composable
fun TransactionsScreen(
    vm: TransactionsViewModel,
    onAdd: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<TransactionListItem?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    Scaffold(
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Text(stringResource(R.string.txn_add)) } },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when {
                state.loading -> {
                    LoadingState(stringResource(R.string.loading))
                }

                state.items.isEmpty() -> {
                    EmptyState(
                        stringResource(R.string.txn_empty_title),
                        stringResource(R.string.txn_empty_message),
                        actionLabel = stringResource(R.string.txn_add),
                        onAction = onAdd,
                    )
                }

                else -> {
                    TransactionList(state, onLoadMore = vm::loadMore, onSelect = { deleting = it })
                }
            }
        }
    }
    deleting?.let { item -> DeleteDialog(vm, item, onDismiss = { deleting = null }) }
}

@Composable
private fun TransactionList(
    state: TransactionsState,
    onLoadMore: () -> Unit,
    onSelect: (TransactionListItem) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md)) {
        items(state.items, key = { it.id }) { item ->
            TransactionRow(item, onSelect)
            HorizontalDivider()
        }
        if (state.next != null) {
            item {
                OutlinedButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth().padding(spacing.sm)) {
                    Text(stringResource(R.string.txn_load_more))
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(
    item: TransactionListItem,
    onSelect: (TransactionListItem) -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(item) }.padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title(item), style = MaterialTheme.typography.bodyLarge)
            val subtitle =
                listOfNotNull(
                    item.date.toString(),
                    item.counterpartyName?.let { "${item.accountName} → $it" } ?: item.accountName,
                    if (item.pending) stringResource(R.string.txn_pending) else null,
                ).joinToString(" · ")
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AmountText(item.amount)
    }
}

@Composable
private fun title(item: TransactionListItem): String {
    val label = item.description.ifBlank { item.merchant.orEmpty() }
    if (label.isNotBlank()) return label
    if (item.categoryNames.isNotEmpty()) return item.categoryNames.joinToString(", ")
    return stringResource(typeLabel(item.type))
}

@Composable
private fun DeleteDialog(
    vm: TransactionsViewModel,
    item: TransactionListItem,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.txn_delete_title)) },
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
            }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** TXN-13 fast entry. Amount first, then account and category chips; everything else is optional. */
@Composable
fun EntryScreen(
    vm: EntryViewModel,
    onDone: () -> Unit,
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    val spacing = LocalSpacing.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        ChoiceChips(EntryKind.entries.toList(), form.kind, { stringResource(kindLabel(it)) }, { k ->
            vm.edit { it.copy(kind = k, categoryId = null) }
        })
        AmountField(
            form.amount,
            { t -> vm.edit { it.copy(amount = t) } },
            stringResource(R.string.field_amount),
            null,
            Modifier.testTag("amount"),
        )
        EntryChoices(vm, form, options)
        OutlinedTextField(form.date, { t ->
            vm.edit {
                it.copy(date = t)
            }
        }, label = { Text(stringResource(R.string.field_date)) }, singleLine = true)
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

@Composable
private fun EntryChoices(
    vm: EntryViewModel,
    form: EntryForm,
    options: EntryOptions,
) {
    Text(stringResource(if (form.kind == EntryKind.TRANSFER) R.string.field_from_account else R.string.field_account))
    OptionChips(options.accounts, form.accountId) { id -> vm.edit { it.copy(accountId = id) } }
    when (form.kind) {
        EntryKind.TRANSFER -> {
            Text(stringResource(R.string.field_to_account))
            OptionChips(
                options.accounts.filter { it.id != form.accountId },
                form.toAccountId,
            ) { id -> vm.edit { it.copy(toAccountId = id) } }
        }

        else -> {
            Text(stringResource(R.string.field_category))
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
