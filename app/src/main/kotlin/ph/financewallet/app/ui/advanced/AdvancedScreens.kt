package ph.financewallet.app.ui.advanced

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.reminders.BillReminders
import ph.financewallet.app.ui.accounts.CheckRow
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FigureCard
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.advanced.AssetKind
import ph.financewallet.data.advanced.AssetSummary
import ph.financewallet.data.advanced.DebtKind
import ph.financewallet.data.advanced.DebtSummary
import ph.financewallet.data.advanced.OccurrenceState
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.advanced.UpcomingItem

/** Dialog with Save/Cancel; [onSave] returns an error message resource or null (then the dialog closes). */
@Composable
internal fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: suspend () -> Int?,
    saveLabel: String = stringResource(R.string.action_save),
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                content()
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = onSave()
                    if (error == null) onDismiss()
                }
            }) { Text(saveLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
) = OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())

@Composable
private fun OptionPicker(
    options: List<Option>,
    selected: String?,
    onSelect: (String?) -> Unit,
) = ChoiceChips(options, options.firstOrNull { it.id == selected }, { it.label }, { onSelect(if (it.id == selected) null else it.id) })

// ---- debts ---------------------------------------------------------------------------------------------------------

@Composable
fun DebtsScreen(
    vm: DebtsViewModel,
    newKey: () -> String,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<DebtSummary?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item {
            SectionTitle(stringResource(R.string.debts_title))
            Button(onClick = { adding = true }) { Text(stringResource(R.string.debt_add)) }
            if (!state.loading &&
                state.debts.isEmpty()
            ) {
                Text(stringResource(R.string.debt_empty), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.debts, key = { it.id }) { d -> DebtCard(d, onPay = { paying = d }) }
    }
    if (adding) AddDebtDialog(vm, state.accounts, newKey, onDismiss = { adding = false })
    paying?.let { d -> PayDebtDialog(vm, d, state.accounts, newKey, onDismiss = { paying = null }) }
}

@Composable
private fun DebtCard(
    d: DebtSummary,
    onPay: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            LabeledRow(d.name) { AmountText(-d.outstanding) }
            Text(
                stringResource(R.string.debt_detail, d.original.display(), d.interestAndFeesPaid.display()),
                style = MaterialTheme.typography.bodySmall,
            )
            d.remainingInstallments?.let {
                Text(
                    pluralStringResource(R.plurals.debt_remaining, it, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val nextDue = d.nextDue
            val nextAmount = d.nextDueAmount
            if (nextDue != null && nextAmount != null) {
                Text(
                    stringResource(R.string.debt_next_due, nextAmount.display(), nextDue.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(debtStatusLabel(d)), style = MaterialTheme.typography.labelMedium)
            if (d.outstanding.isPositive) OutlinedButton(onClick = onPay) { Text(stringResource(R.string.debt_pay)) }
        }
    }
}

private fun debtStatusLabel(d: DebtSummary): Int =
    when (d.status) {
        ph.financewallet.data.advanced.DebtStatus.ACTIVE -> R.string.debt_status_active
        ph.financewallet.data.advanced.DebtStatus.PAID_OFF -> R.string.debt_status_paid_off
        ph.financewallet.data.advanced.DebtStatus.DEFAULTED -> R.string.debt_status_defaulted
        ph.financewallet.data.advanced.DebtStatus.CLOSED -> R.string.debt_status_closed
    }

@Composable
private fun AddDebtDialog(
    vm: DebtsViewModel,
    accounts: List<Option>,
    newKey: () -> String,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(DebtKind.PERSONAL_LOAN) }
    var amount by remember { mutableStateOf("") }
    var into by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    val key = remember { newKey() }
    FormDialog(stringResource(R.string.debt_add), onDismiss, { vm.create(name, kind, amount, into, count, day, key) }) {
        TextInput(name, { name = it }, stringResource(R.string.field_name))
        ChoiceChips(DebtKind.entries.toList(), kind, { stringResource(debtKindLabel(it)) }, { kind = it })
        AmountField(
            amount,
            { amount = it },
            stringResource(
                if (into ==
                    null
                ) {
                    R.string.debt_owed_now
                } else {
                    R.string.debt_amount_received
                },
            ),
            null,
        )
        Text(stringResource(R.string.debt_received_into), style = MaterialTheme.typography.bodySmall)
        OptionPicker(accounts, into) { into = it }
        TextInput(count, { count = it }, stringResource(R.string.debt_installments))
        if (count.isNotBlank()) TextInput(day, { day = it }, stringResource(R.string.debt_due_day))
        Text(stringResource(R.string.debt_interest_note), style = MaterialTheme.typography.bodySmall)
    }
}

private fun debtKindLabel(k: DebtKind): Int =
    when (k) {
        DebtKind.PERSONAL_LOAN -> R.string.debt_kind_loan
        DebtKind.CREDIT_CARD -> R.string.type_credit_card
        DebtKind.INSTALLMENT -> R.string.debt_kind_installment
        DebtKind.BORROWED -> R.string.type_borrowed
        DebtKind.OTHER -> R.string.type_other
    }

@Composable
private fun PayDebtDialog(
    vm: DebtsViewModel,
    d: DebtSummary,
    accounts: List<Option>,
    newKey: () -> String,
    onDismiss: () -> Unit,
) {
    var from by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var principal by remember { mutableStateOf("") }
    var interest by remember { mutableStateOf("") }
    var fees by remember { mutableStateOf("") }
    val key = remember(d.id) { newKey() }
    FormDialog(stringResource(R.string.debt_pay_title, d.name), onDismiss, { vm.pay(d, from, principal, interest, fees, key) }) {
        Text(stringResource(R.string.field_from_account), style = MaterialTheme.typography.bodySmall)
        OptionPicker(accounts, from) { from = it }
        AmountField(principal, { principal = it }, stringResource(R.string.debt_principal), null)
        AmountField(interest, { interest = it }, stringResource(R.string.debt_interest), null)
        AmountField(fees, { fees = it }, stringResource(R.string.debt_fees), null)
        Text(stringResource(R.string.debt_split_note), style = MaterialTheme.typography.bodySmall)
    }
}

// ---- bills ---------------------------------------------------------------------------------------------------------

@Composable
fun BillsScreen(
    vm: BillsViewModel,
    newKey: () -> String,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<UpcomingItem?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.refresh() }
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item {
            SectionTitle(stringResource(R.string.bills_title))
            Button(onClick = { adding = true }) { Text(stringResource(R.string.bill_add)) }
            NotificationPermissionRow()
            if (!state.loading &&
                state.items.isEmpty()
            ) {
                Text(stringResource(R.string.bill_empty), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.items, key = { it.occurrenceId }) { item ->
            BillRow(item, onPay = { paying = item }, onSkip = { scope.launch { vm.skip(item) } })
        }
        if (state.costs.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.bill_yearly_title)) }
            items(state.costs, key = { it.ruleId }) { c -> LabeledRow(c.name) { AmountText(-c.nextTwelveMonths) } }
        }
    }
    if (adding) AddBillDialog(vm, state, onDismiss = { adding = false })
    paying?.let { item -> PayBillDialog(vm, item, state.accounts, newKey, onDismiss = { paying = null }) }
}

@Composable
private fun NotificationPermissionRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(BillReminders.canNotify(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted =
                BillReminders.canNotify(context)
        }
    if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Text(stringResource(R.string.bill_notifications_explain), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) { Text(stringResource(R.string.bill_notifications_allow)) }
    }
}

@Composable
private fun BillRow(
    item: UpcomingItem,
    onPay: () -> Unit,
    onSkip: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            LabeledRow(item.name) { AmountText(if (item.kind == RuleKind.EXPECTED_INCOME) item.amount else -item.amount) }
            val estimate = if (item.amountIsEstimate) " · " + stringResource(R.string.bill_estimate) else ""
            Text(
                item.dueDate.toString() + " · " + stringResource(stateLabel(item.state)) + estimate,
                style = MaterialTheme.typography.bodySmall,
            )
            if (item.state != OccurrenceState.PAID && item.state != OccurrenceState.SKIPPED) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(onClick = onPay) {
                        Text(
                            stringResource(
                                if (item.kind ==
                                    RuleKind.EXPECTED_INCOME
                                ) {
                                    R.string.bill_mark_received
                                } else {
                                    R.string.bill_mark_paid
                                },
                            ),
                        )
                    }
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.bill_skip)) }
                }
            }
        }
    }
}

private fun stateLabel(s: OccurrenceState): Int =
    when (s) {
        OccurrenceState.UPCOMING -> R.string.bill_state_upcoming
        OccurrenceState.DUE_TODAY -> R.string.bill_state_due_today
        OccurrenceState.OVERDUE -> R.string.bill_state_overdue
        OccurrenceState.PAID -> R.string.bill_state_paid
        OccurrenceState.SKIPPED -> R.string.bill_state_skipped
    }

@Composable
private fun AddBillDialog(
    vm: BillsViewModel,
    state: BillsState,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(RuleKind.BILL) }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var account by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var reminder by remember { mutableStateOf("3") }
    var estimate by remember { mutableStateOf(false) }
    FormDialog(
        stringResource(R.string.bill_add),
        onDismiss,
        { vm.create(kind, name, amount, day, account, category, reminder, estimate) },
    ) {
        ChoiceChips(listOf(RuleKind.BILL, RuleKind.SUBSCRIPTION, RuleKind.EXPECTED_INCOME), kind, { stringResource(ruleKindLabel(it)) }, {
            kind = it
            category = null
        })
        TextInput(name, { name = it }, stringResource(R.string.field_name))
        AmountField(amount, { amount = it }, stringResource(R.string.field_amount), null)
        CheckRow(stringResource(R.string.bill_amount_estimate), estimate) { estimate = it }
        TextInput(day, { day = it }, stringResource(R.string.bill_due_day))
        Text(stringResource(R.string.field_account), style = MaterialTheme.typography.bodySmall)
        OptionPicker(state.accounts, account) { account = it }
        Text(stringResource(R.string.field_category), style = MaterialTheme.typography.bodySmall)
        OptionPicker(if (kind == RuleKind.EXPECTED_INCOME) state.incomeCategories else state.expenseCategories, category) { category = it }
        if (kind != RuleKind.EXPECTED_INCOME) TextInput(reminder, { reminder = it }, stringResource(R.string.bill_reminder_days))
        Text(stringResource(R.string.bill_never_auto_paid), style = MaterialTheme.typography.bodySmall)
    }
}

private fun ruleKindLabel(k: RuleKind): Int =
    when (k) {
        RuleKind.BILL -> R.string.rule_kind_bill
        RuleKind.SUBSCRIPTION -> R.string.rule_kind_subscription
        RuleKind.RECURRING_TXN -> R.string.rule_kind_recurring
        RuleKind.EXPECTED_INCOME -> R.string.rule_kind_income
    }

@Composable
private fun PayBillDialog(
    vm: BillsViewModel,
    item: UpcomingItem,
    accounts: List<Option>,
    newKey: () -> String,
    onDismiss: () -> Unit,
) {
    var account by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    val key = remember(item.occurrenceId) { newKey() }
    FormDialog(item.name, onDismiss, { vm.markPaid(item, account, amount, key) }) {
        Text(stringResource(R.string.bill_pay_explain, item.amount.display()), style = MaterialTheme.typography.bodySmall)
        AmountField(amount, { amount = it }, stringResource(R.string.bill_actual_amount), null)
        Text(stringResource(R.string.bill_pay_account), style = MaterialTheme.typography.bodySmall)
        OptionPicker(accounts, account) { account = it }
    }
}

// ---- assets --------------------------------------------------------------------------------------------------------

@Composable
fun AssetsScreen(vm: AssetsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var valuing by remember { mutableStateOf<AssetSummary?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item {
            SectionTitle(stringResource(R.string.assets_title))
            Text(stringResource(R.string.assets_explain), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { adding = true }) { Text(stringResource(R.string.asset_add)) }
        }
        items(state.assets.filter { !it.archived }, key = { it.id }) { a ->
            val value = a.latestValue
            Card(onClick = { valuing = a }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(spacing.md)) {
                    if (value != null) {
                        FigureCard(a.name, value, stringResource(R.string.asset_value_on, a.latestAsOf.toString()))
                    } else {
                        Text(a.name)
                        Text(stringResource(R.string.asset_no_value), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (adding) AddAssetDialog(vm, onDismiss = { adding = false })
    valuing?.let { a ->
        var value by remember(a.id) { mutableStateOf("") }
        FormDialog(stringResource(R.string.asset_update_value, a.name), { valuing = null }, { vm.addValuation(a.id, value) }) {
            AmountField(value, { value = it }, stringResource(R.string.asset_value_today), null)
        }
    }
}

@Composable
private fun AddAssetDialog(
    vm: AssetsViewModel,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AssetKind.INVESTMENT) }
    var value by remember { mutableStateOf("") }
    FormDialog(stringResource(R.string.asset_add), onDismiss, { vm.create(name, kind, value) }) {
        TextInput(name, { name = it }, stringResource(R.string.field_name))
        ChoiceChips(AssetKind.entries.toList(), kind, { stringResource(assetKindLabel(it)) }, { kind = it })
        AmountField(value, { value = it }, stringResource(R.string.asset_value_today), null)
    }
}

private fun assetKindLabel(k: AssetKind): Int =
    when (k) {
        AssetKind.INVESTMENT -> R.string.asset_kind_investment
        AssetKind.PROPERTY -> R.string.asset_kind_property
        AssetKind.VEHICLE -> R.string.asset_kind_vehicle
        AssetKind.RECEIVABLE -> R.string.asset_kind_receivable
        AssetKind.OTHER -> R.string.type_other
    }
