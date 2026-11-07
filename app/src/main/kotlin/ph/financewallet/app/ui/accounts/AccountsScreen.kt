package ph.financewallet.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.AddFab
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FigureCard
import ph.financewallet.app.ui.common.IconDisc
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ScreenTitle
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.components.EmptyState
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.MoneyFormatter

@Composable
fun AccountsScreen(
    vm: AccountsViewModel,
    newKey: () -> String,
    onShowTransactions: (String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AccountUi?>(null) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            AddFab(stringResource(R.string.accounts_add)) { adding = true }
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
    var editing by remember { mutableStateOf<AccountUi?>(null) }
    selected?.let { a ->
        AccountDialog(
            vm,
            a,
            newKey,
            onDismiss = { selected = null },
            onShowTransactions = {
                selected = null
                onShowTransactions(a.row.id)
            },
            onEdit = {
                selected = null
                editing = a
            },
        )
    }
    editing?.let { EditAccountDialog(vm, it, onDismiss = { editing = null }) }
}

@Composable
private fun AccountList(
    state: AccountsState,
    onSelect: (AccountUi) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = spacing.md),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item { ScreenTitle(stringResource(R.string.nav_accounts), stringResource(R.string.accounts_calculated_note)) }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                FigureCard(
                    stringResource(R.string.home_in_accounts),
                    state.inAccounts,
                    null,
                    Modifier.weight(1f).fillMaxHeight(),
                    icon = AppIcons.Wallet,
                )
                FigureCard(
                    stringResource(R.string.home_owed),
                    state.owed,
                    null,
                    Modifier.weight(1f).fillMaxHeight(),
                    icon = AppIcons.CreditCard,
                )
            }
        }
        item { SectionTitle(stringResource(R.string.accounts_active)) }
        items(state.accounts, key = { it.row.id }) { AccountCard(it, onSelect) }
        if (state.archived.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.accounts_archived)) }
            items(state.archived, key = { it.row.id }) { AccountCard(it, onSelect) }
        }
    }
}

private val FAB_CLEARANCE = 96.dp

private fun accountIcon(type: FinancialAccountType) =
    when (type) {
        FinancialAccountType.BANK -> AppIcons.Bank
        FinancialAccountType.SAVINGS -> AppIcons.Save
        FinancialAccountType.CREDIT_CARD, FinancialAccountType.LOAN, FinancialAccountType.BORROWED -> AppIcons.CreditCard
        else -> AppIcons.Wallet
    }

/** One account: calculated balance first; pending and the last reconciled statement balance are shown separately (ACC). */
@Composable
private fun AccountCard(
    a: AccountUi,
    onSelect: (AccountUi) -> Unit,
) {
    val spacing = LocalSpacing.current
    val type = FinancialAccountType.valueOf(a.row.type)
    SurfaceCard(onClick = { onSelect(a) }) {
        ListRow(
            a.row.name,
            subtitle = stringResource(typeLabel(type)),
            leading = { IconDisc(accountIcon(type), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer) },
        ) { AmountText(a.balance) }
        if (!a.pending.isZero || a.reconciled != null) {
            Column(
                Modifier.padding(start = spacing.md, end = spacing.md, bottom = spacing.sm + spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (!a.pending.isZero) {
                    LabeledRow(stringResource(R.string.accounts_pending)) { AmountText(a.pending) }
                }
                a.reconciled?.let {
                    Text(
                        stringResource(R.string.accounts_reconciled_on, it.statementBalance.display(), it.asOf.display()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    onShowTransactions: () -> Unit,
    onEdit: () -> Unit,
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
                Row {
                    TextButton(onClick = onShowTransactions) { Text(stringResource(R.string.accounts_view_transactions)) }
                    if (!archived) TextButton(onClick = onEdit) { Text(stringResource(R.string.accounts_edit)) }
                }
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

/**
 * UC-02 edit: name, institution and opening balance. The currency is shown but locked. Changing the opening balance
 * edits the account's opening transaction, which keeps a revision, so the change is never silent.
 */
@Composable
private fun EditAccountDialog(
    vm: AccountsViewModel,
    a: AccountUi,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ph.financewallet.data.account.AccountEditState?>(null) }
    var name by rememberSaveable { mutableStateOf(a.row.name) }
    var institution by rememberSaveable { mutableStateOf(a.row.institution.orEmpty()) }
    var opening by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(a.row.id) {
        state = vm.editState(a.row.id)
        if (opening == null) opening = state?.edit?.openingAmount?.let { MoneyFormatter.plain(it) }
    }
    val loaded = state ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accounts_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                OutlinedTextField(
                    institution,
                    { institution = it },
                    label = { Text(stringResource(R.string.field_institution)) },
                    singleLine = true,
                )
                AmountField(opening.orEmpty(), { opening = it }, stringResource(openingLabel(a.row.accountClass)), null)
                Text(stringResource(R.string.accounts_opening_revision), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.accounts_currency_locked, loaded.edit.currency.code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.edit(a.row.id, loaded, name, institution, opening.orEmpty())
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun openingLabel(accountClass: String): Int =
    if (accountClass == AccountClass.LIABILITY.name) R.string.accounts_opening_owed else R.string.accounts_opening_balance

@Composable
fun CheckRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    // The whole line toggles, not only the small box, and reads as one checkbox to TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(LocalSpacing.current.sm))
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
