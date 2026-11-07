package ph.financewallet.app.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.accounts.CheckRow
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FieldLabel
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.transactions.DateField
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.budget.GoalStatus
import ph.financewallet.data.budget.GoalSummary
import ph.financewallet.domain.money.MoneyFormatter
import java.time.LocalDate

/** The goal actions beyond add/release (blueprint I; SAV-01..04). */
enum class GoalAction { EDIT, LINK, WITHDRAW, TRANSFER }

/** Opens the dialog for one [GoalAction]. */
@Composable
fun GoalActionDialog(
    vm: GoalsViewModel,
    state: GoalsState,
    g: GoalSummary,
    action: GoalAction,
    newKey: () -> String,
    onDismiss: () -> Unit,
) {
    when (action) {
        GoalAction.EDIT -> EditGoalDialog(vm, g, onDismiss)
        GoalAction.LINK -> LinkAccountsDialog(vm, state, g, onDismiss)
        GoalAction.WITHDRAW -> MoveMoneyDialog(vm, state, g, toGoal = false, newKey, onDismiss)
        GoalAction.TRANSFER -> MoveMoneyDialog(vm, state, g, toGoal = true, newKey, onDismiss)
    }
}

@Composable
private fun EditGoalDialog(
    vm: GoalsViewModel,
    g: GoalSummary,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(g.name) }
    var target by remember { mutableStateOf(MoneyFormatter.plain(g.target)) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(g.status) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(g.id) {
        vm.details(g.id)?.let {
            date = it.targetDate
            notes = it.notes.orEmpty()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_edit)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ph.financewallet.app.ui.theme.LocalSpacing.current.sm),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                AmountField(target, { target = it }, stringResource(R.string.goal_target), null)
                FieldLabel(stringResource(R.string.goal_target_date))
                val current = date
                if (current != null) {
                    DateField(current.toString()) { date = LocalDate.parse(it) }
                    TextButton(onClick = { date = null }) { Text(stringResource(R.string.goal_no_date)) }
                } else {
                    TextButton(onClick = { date = vm.today().plusMonths(DEFAULT_MONTHS) }) { Text(stringResource(R.string.goal_set_date)) }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.field_notes)) })
                FieldLabel(stringResource(R.string.goal_status))
                ChoiceChips(GoalStatus.entries.toList(), status, { stringResource(statusLabel(it)) }, { status = it })
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.edit(g, name, target, date, notes, status)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Linked accounts hold the goal's money for real; the goal may then not exceed their combined balance (SAV-03). */
@Composable
private fun LinkAccountsDialog(
    vm: GoalsViewModel,
    state: GoalsState,
    g: GoalSummary,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(g.linkedAccountIds) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_link_accounts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ph.financewallet.app.ui.theme.LocalSpacing.current.xs)) {
                Text(stringResource(R.string.goal_link_explain), style = MaterialTheme.typography.bodySmall)
                state.accounts.forEach { a ->
                    CheckRow(a.label, a.id in picked) { on -> picked = if (on) picked + a.id else picked - a.id }
                }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.linkAccounts(g, picked)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Withdraw to a budget period, or move to another goal (CLAUDE.md §9): amount, destination, optional reason, and an
 * explicit confirmation. The allocation ledger records previous and new amounts, source, destination and reason.
 */
@Composable
private fun MoveMoneyDialog(
    vm: GoalsViewModel,
    state: GoalsState,
    g: GoalSummary,
    toGoal: Boolean,
    newKey: () -> String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val key = remember(g.id, toGoal) { newKey() }
    var amount by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val choices: List<Option> =
        if (toGoal) state.goals.filter { it.id != g.id && it.status == GoalStatus.ACTIVE }.map { Option(it.id, it.name) } else state.periods
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (toGoal) R.string.goal_transfer else R.string.goal_withdraw)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ph.financewallet.app.ui.theme.LocalSpacing.current.sm),
            ) {
                Text(
                    stringResource(R.string.goal_amounts, g.allocated.display(), g.target.display()),
                    style = MaterialTheme.typography.bodySmall,
                )
                AmountField(amount, { amount = it }, stringResource(R.string.field_amount), null)
                FieldLabel(stringResource(if (toGoal) R.string.goal_transfer_to else R.string.goal_withdraw_to))
                if (choices.isEmpty()) {
                    Text(
                        stringResource(if (toGoal) R.string.goal_no_other_goals else R.string.goal_no_periods),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ChoiceChips(choices, choices.firstOrNull { it.id == destination }, { it.label }, { destination = it.id })
                OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.goal_reason)) }, singleLine = true)
                CheckRow(stringResource(R.string.goal_move_confirm), confirmed) { confirmed = it }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error =
                        if (toGoal) {
                            vm.transfer(g, destination, amount, reason, confirmed, key)
                        } else {
                            vm.withdraw(g, destination, amount, reason, confirmed, key)
                        }
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(if (toGoal) R.string.goal_transfer else R.string.goal_withdraw)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun statusLabel(s: GoalStatus): Int =
    when (s) {
        GoalStatus.ACTIVE -> R.string.goal_status_active
        GoalStatus.PAUSED -> R.string.goal_status_paused
        GoalStatus.ACHIEVED -> R.string.goal_status_achieved
        GoalStatus.ARCHIVED -> R.string.goal_status_archived
    }

private const val DEFAULT_MONTHS = 6L
