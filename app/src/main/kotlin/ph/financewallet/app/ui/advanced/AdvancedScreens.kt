package ph.financewallet.app.ui.advanced

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.reminders.BillReminders
import ph.financewallet.app.ui.accounts.CheckRow
import ph.financewallet.app.ui.categories.CategoryPicker
import ph.financewallet.app.ui.categories.categoryOptions
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.CardList
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.EmptyCard
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FieldLabel
import ph.financewallet.app.ui.common.FigureCard
import ph.financewallet.app.ui.common.IconDisc
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.StatusPill
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalFinanceColors
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.advanced.AssetKind
import ph.financewallet.data.advanced.AssetSummary
import ph.financewallet.data.advanced.DebtKind
import ph.financewallet.data.advanced.DebtSummary
import ph.financewallet.data.advanced.OccurrenceState
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.advanced.RuleStatus
import ph.financewallet.data.advanced.UpcomingItem
import ph.financewallet.data.db.entity.RecurringRuleEntity
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.money.MoneyFormatter

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
    ListScreen(stringResource(R.string.debt_add), { adding = true }) {
        if (!state.loading && state.debts.isEmpty()) {
            item {
                EmptyCard(AppIcons.CreditCard, stringResource(R.string.debt_empty_title), stringResource(R.string.debt_empty_message))
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
    val fc = LocalFinanceColors.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            CardHeader(AppIcons.CreditCard, fc.expense, fc.expenseSoft, d.name, stringResource(R.string.debt_outstanding)) {
                AmountText(-d.outstanding)
            }
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                stringResource(R.string.debt_detail, d.original.display(), d.interestAndFeesPaid.display()),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            d.remainingInstallments?.let {
                Text(pluralStringResource(R.plurals.debt_remaining, it, it), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            val nextDue = d.nextDue
            val nextAmount = d.nextDueAmount
            if (nextDue != null && nextAmount != null) {
                Text(
                    stringResource(R.string.debt_next_due, nextAmount.display(), nextDue.display()),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DebtStatusPill(d.status)
                Spacer(Modifier.weight(1f))
                if (d.outstanding.isPositive) OutlinedButton(onClick = onPay) { Text(stringResource(R.string.debt_pay)) }
            }
        }
    }
}

/** Card heading shared by debts and bills: icon disc, name, a muted second line and the amount on the right. */
@Composable
private fun CardHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    disc: Color,
    title: String,
    subtitle: String,
    amount: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.md)) {
        IconDisc(icon, tint, disc)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        amount()
    }
}

@Composable
private fun DebtStatusPill(status: ph.financewallet.data.advanced.DebtStatus) {
    val fc = LocalFinanceColors.current
    val cs = MaterialTheme.colorScheme
    val (bg, fg) =
        when (status) {
            ph.financewallet.data.advanced.DebtStatus.ACTIVE -> cs.secondaryContainer to cs.onSecondaryContainer
            ph.financewallet.data.advanced.DebtStatus.PAID_OFF -> fc.incomeSoft to fc.income
            ph.financewallet.data.advanced.DebtStatus.DEFAULTED -> fc.expenseSoft to fc.expense
            ph.financewallet.data.advanced.DebtStatus.CLOSED -> cs.surfaceVariant to cs.onSurfaceVariant
        }
    StatusPill(stringResource(debtStatusLabel(status)), bg, fg)
}

private fun debtStatusLabel(status: ph.financewallet.data.advanced.DebtStatus): Int =
    when (status) {
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
    var editingRule by remember { mutableStateOf<RecurringRuleEntity?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.refresh() }
    ListScreen(stringResource(R.string.bill_add), { adding = true }) {
        item { NotificationPermissionRow() }
        if (!state.loading && state.items.isEmpty()) {
            item { EmptyCard(AppIcons.Event, stringResource(R.string.bill_empty_title), stringResource(R.string.bill_empty_message)) }
        }
        items(state.items, key = { it.occurrenceId }) { item ->
            BillRow(item, onPay = { paying = item }, onSkip = { scope.launch { vm.skip(item) } })
        }
        if (state.rules.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.bill_rules_title)) }
            item {
                CardList(state.rules) { r ->
                    ListRow(r.name, subtitle = stringResource(ruleStatusLabel(r.status)), onClick = { editingRule = r }) {
                        AmountText(
                            Money(
                                if (r.kind ==
                                    RuleKind.EXPECTED_INCOME.name
                                ) {
                                    r.amountMinor
                                } else {
                                    -r.amountMinor
                                },
                                CurrencyCode.of(r.currency),
                            ),
                        )
                    }
                }
            }
        }
        if (state.costs.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.bill_yearly_title)) }
            item {
                CardList(state.costs) { c ->
                    val income = c.kind == RuleKind.EXPECTED_INCOME
                    ListRow(c.name) { AmountText(if (income) c.nextTwelveMonths else -c.nextTwelveMonths) }
                }
            }
        }
    }
    if (adding) AddBillDialog(vm, state, onDismiss = { adding = false })
    paying?.let { item -> PayBillDialog(vm, item, state.accounts, newKey, onDismiss = { paying = null }) }
    editingRule?.let { r -> EditRuleDialog(vm, state, r, onDismiss = { editingRule = null }) }
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
        val spacing = LocalSpacing.current
        SurfaceCard {
            Row(Modifier.padding(spacing.md), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                IconDisc(AppIcons.Event, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(stringResource(R.string.bill_notifications_explain), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text(stringResource(R.string.bill_notifications_allow)) }
                }
            }
        }
    }
}

@Composable
private fun BillRow(
    item: UpcomingItem,
    onPay: () -> Unit,
    onSkip: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val income = item.kind == RuleKind.EXPECTED_INCOME
    val fc = LocalFinanceColors.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            val estimate = if (item.amountIsEstimate) " · " + stringResource(R.string.bill_estimate) else ""
            CardHeader(
                if (income) AppIcons.TrendingUp else AppIcons.Event,
                if (income) fc.income else MaterialTheme.colorScheme.tertiary,
                if (income) fc.incomeSoft else MaterialTheme.colorScheme.tertiaryContainer,
                item.name,
                item.dueDate.display() + estimate,
            ) { AmountText(if (income) item.amount else -item.amount) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                BillStatePill(item.state)
                Spacer(Modifier.weight(1f))
                if (item.state != OccurrenceState.PAID && item.state != OccurrenceState.SKIPPED) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.bill_skip)) }
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
                }
            }
        }
    }
}

@Composable
private fun BillStatePill(state: OccurrenceState) {
    val fc = LocalFinanceColors.current
    val cs = MaterialTheme.colorScheme
    val (bg, fg) =
        when (state) {
            OccurrenceState.OVERDUE -> fc.expenseSoft to fc.expense
            OccurrenceState.DUE_TODAY -> cs.tertiaryContainer to cs.onTertiaryContainer
            OccurrenceState.PAID -> fc.incomeSoft to fc.income
            OccurrenceState.UPCOMING, OccurrenceState.SKIPPED -> cs.surfaceVariant to cs.onSurfaceVariant
        }
    StatusPill(stringResource(stateLabel(state)), bg, fg)
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
        FieldLabel(stringResource(R.string.field_category_optional))
        val income = kind == RuleKind.EXPECTED_INCOME
        CategoryPicker(
            categoryOptions(state.categoryRows, if (income) "INCOME" else "EXPENSE", setOfNotNull(category)),
            category,
            onSelect = { category = it },
            creator = vm.creator,
            newCategoryClass = if (income) AccountClass.INCOME else AccountClass.EXPENSE,
            onClear = { category = null },
        )
        if (kind != RuleKind.EXPECTED_INCOME) TextInput(reminder, { reminder = it }, stringResource(R.string.bill_reminder_days))
        Text(stringResource(R.string.bill_never_auto_paid), style = MaterialTheme.typography.bodySmall)
    }
}

/** The editable fields of a bill rule, as typed. */
private data class RuleDraft(
    val name: String,
    val amount: String,
    val day: String,
    val account: String?,
    val category: String?,
    val reminder: String,
    val estimate: Boolean,
)

private fun draftOf(rule: RecurringRuleEntity) =
    RuleDraft(
        name = rule.name,
        amount = MoneyFormatter.plain(Money(rule.amountMinor, CurrencyCode.of(rule.currency))),
        day = rule.day1?.toString().orEmpty(),
        account = rule.accountId,
        category = rule.categoryId,
        reminder = rule.reminderDaysBefore?.toString().orEmpty(),
        estimate = rule.amountIsEstimate,
    )

/**
 * Edit a bill, subscription or expected income (UC-22), and pause, resume or end it. Ending asks first; it stops new
 * due dates and keeps every past payment.
 */
@Composable
private fun EditRuleDialog(
    vm: BillsViewModel,
    state: BillsState,
    rule: RecurringRuleEntity,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(draftOf(rule)) }
    var ending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val run: (suspend () -> Int?) -> Unit = { action ->
        scope.launch {
            error = action()
            if (error == null) onDismiss()
        }
    }
    if (ending) {
        EndRuleDialog(rule.name, onEnd = { run { vm.setStatus(rule.id, RuleStatus.ENDED) } }, onCancel = { ending = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bill_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                RuleFields(vm, state, rule, draft) { draft = it }
                ErrorLine(error)
                RuleStatusButtons(rule.status, onStatus = { st -> run { vm.setStatus(rule.id, st) } }, onEnd = { ending = true })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                run { with(draft) { vm.editRule(rule, name, amount, day, account, category, reminder, estimate) } }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RuleFields(
    vm: BillsViewModel,
    state: BillsState,
    rule: RecurringRuleEntity,
    d: RuleDraft,
    onChange: (RuleDraft) -> Unit,
) {
    val income = rule.kind == RuleKind.EXPECTED_INCOME.name
    TextInput(d.name, { onChange(d.copy(name = it)) }, stringResource(R.string.field_name))
    AmountField(d.amount, { onChange(d.copy(amount = it)) }, stringResource(R.string.field_amount), null)
    CheckRow(stringResource(R.string.bill_amount_estimate), d.estimate) { onChange(d.copy(estimate = it)) }
    if (rule.frequency == "MONTHLY") TextInput(d.day, { onChange(d.copy(day = it)) }, stringResource(R.string.bill_due_day))
    FieldLabel(stringResource(R.string.field_account))
    OptionPicker(state.accounts, d.account) { onChange(d.copy(account = it)) }
    FieldLabel(stringResource(R.string.field_category_optional))
    CategoryPicker(
        categoryOptions(state.categoryRows, if (income) "INCOME" else "EXPENSE", setOfNotNull(d.category)),
        d.category,
        onSelect = { onChange(d.copy(category = it)) },
        creator = vm.creator,
        newCategoryClass = if (income) AccountClass.INCOME else AccountClass.EXPENSE,
        onClear = { onChange(d.copy(category = null)) },
    )
    if (!income) TextInput(d.reminder, { onChange(d.copy(reminder = it)) }, stringResource(R.string.bill_reminder_days))
}

/** Pause an active rule, resume a paused or ended one, and End (asks first) anything not yet ended. */
@Composable
private fun RuleStatusButtons(
    status: String,
    onStatus: (RuleStatus) -> Unit,
    onEnd: () -> Unit,
) {
    Row {
        if (status == RuleStatus.ACTIVE.name) {
            TextButton(onClick = { onStatus(RuleStatus.PAUSED) }) { Text(stringResource(R.string.bill_pause)) }
        } else {
            TextButton(onClick = { onStatus(RuleStatus.ACTIVE) }) { Text(stringResource(R.string.bill_resume)) }
        }
        if (status != RuleStatus.ENDED.name) {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.bill_end), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun EndRuleDialog(
    name: String,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.bill_end_title, name)) },
        text = { Text(stringResource(R.string.bill_end_message)) },
        confirmButton = { TextButton(onClick = onEnd) { Text(stringResource(R.string.bill_end)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun ruleStatusLabel(status: String): Int =
    when (status) {
        RuleStatus.ACTIVE.name -> R.string.bill_status_active
        RuleStatus.PAUSED.name -> R.string.bill_status_paused
        else -> R.string.bill_status_ended
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
    val shown = state.assets.filter { !it.archived }
    ListScreen(stringResource(R.string.asset_add), { adding = true }) {
        if (!state.loading && shown.isEmpty()) {
            item { EmptyCard(AppIcons.Bank, stringResource(R.string.asset_empty_title), stringResource(R.string.assets_explain)) }
        } else {
            item {
                Text(
                    stringResource(R.string.assets_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.id }) { a ->
            val value = a.latestValue
            val asOf = a.latestAsOf
            if (value != null && asOf != null) {
                FigureCard(
                    a.name,
                    value,
                    stringResource(R.string.asset_value_on, asOf.display()),
                    icon = AppIcons.Bank,
                    onClick = { valuing = a },
                )
            } else {
                SurfaceCard(onClick = { valuing = a }) {
                    Column(Modifier.padding(spacing.md)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.asset_no_value),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
