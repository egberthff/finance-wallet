package ph.financewallet.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.components.EmptyState
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.domain.ledger.FinancialAccountType

@Composable
fun AccountsScreen(
    vm: AccountsViewModel,
    newKey: () -> String,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AccountUi?>(null) }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }) { Text(stringResource(R.string.accounts_add)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when {
                state.loading -> {
                    LoadingState(stringResource(R.string.loading))
                }

                state.accounts.isEmpty() && state.archived.isEmpty() -> {
                    EmptyState(
                        stringResource(R.string.accounts_empty_title),
                        stringResource(R.string.accounts_empty_message),
                        actionLabel = stringResource(R.string.accounts_add),
                        onAction = { adding = true },
                    )
                }

                else -> {
                    AccountList(state, onSelect = { selected = it })
                }
            }
        }
    }
    if (adding) AddAccountDialog(vm, onDismiss = { adding = false })
    selected?.let { AccountDialog(vm, it, newKey, onDismiss = { selected = null }) }
}

@Composable
private fun AccountList(
    state: AccountsState,
    onSelect: (AccountUi) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item { SectionTitle(stringResource(R.string.accounts_active)) }
        items(state.accounts, key = { it.row.id }) { AccountCard(it, onSelect) }
        if (state.archived.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.accounts_archived)) }
            items(state.archived, key = { it.row.id }) { AccountCard(it, onSelect) }
        }
        item { Text(stringResource(R.string.accounts_calculated_note), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AccountCard(
    a: AccountUi,
    onSelect: (AccountUi) -> Unit,
) {
    val spacing = LocalSpacing.current
    Card(Modifier.fillMaxWidth().clickable { onSelect(a) }) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            LabeledRow(a.row.name) { AmountText(a.balance) }
            Text(stringResource(typeLabel(FinancialAccountType.valueOf(a.row.type))), style = MaterialTheme.typography.bodySmall)
            if (!a.pending.isZero) {
                LabeledRow(stringResource(R.string.accounts_pending)) { AmountText(a.pending) }
            }
            a.reconciled?.let {
                Text(
                    stringResource(R.string.accounts_reconciled_on, it.statementBalance.display(), it.asOf.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddAccountDialog(
    vm: AccountsViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(FinancialAccountType.EWALLET) }
    var opening by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accounts_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                ChoiceChips(FinancialAccountType.entries.toList(), type, { stringResource(typeLabel(it)) }, { type = it })
                val liability = type.accountClass == ph.financewallet.domain.ledger.AccountClass.LIABILITY
                AmountField(
                    opening,
                    { opening = it },
                    stringResource(if (liability) R.string.accounts_opening_owed else R.string.accounts_opening),
                    null,
                )
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.create(name, type, opening)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AccountDialog(
    vm: AccountsViewModel,
    a: AccountUi,
    newKey: () -> String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var statement by rememberSaveable { mutableStateOf("") }
    var adjust by rememberSaveable { mutableStateOf(true) }
    var acknowledge by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val key = remember(a.row.id) { newKey() }
    val archived = a.row.archivedAt != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(a.row.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                LabeledRow(stringResource(R.string.accounts_balance_calculated)) { AmountText(a.balance) }
                if (!archived) {
                    Text(stringResource(R.string.accounts_reconcile_title), style = MaterialTheme.typography.titleSmall)
                    AmountField(statement, { statement = it }, stringResource(R.string.accounts_statement_balance), null)
                    CheckRow(stringResource(R.string.accounts_reconcile_adjust), adjust) { adjust = it }
                    if (!a.balance.isZero) CheckRow(stringResource(R.string.accounts_archive_ack), acknowledge) { acknowledge = it }
                }
                ErrorLine(error)
            }
        },
        confirmButton = {
            if (!archived) {
                TextButton(onClick = {
                    scope.launch {
                        error = vm.reconcile(a.row.id, statement, adjust, key)
                        if (error == null) onDismiss()
                    }
                }) { Text(stringResource(R.string.accounts_reconcile_action)) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    error = if (archived) vm.unarchive(a.row.id) else vm.archive(a.row.id, acknowledge)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(if (archived) R.string.accounts_unarchive else R.string.accounts_archive)) }
        },
    )
}

@Composable
fun CheckRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

fun typeLabel(t: FinancialAccountType): Int =
    when (t) {
        FinancialAccountType.CASH -> R.string.type_cash
        FinancialAccountType.BANK -> R.string.type_bank
        FinancialAccountType.EWALLET -> R.string.type_ewallet
        FinancialAccountType.SAVINGS -> R.string.type_savings
        FinancialAccountType.CREDIT_CARD -> R.string.type_credit_card
        FinancialAccountType.LOAN -> R.string.type_loan
        FinancialAccountType.BORROWED -> R.string.type_borrowed
        FinancialAccountType.OTHER -> R.string.type_other
    }
