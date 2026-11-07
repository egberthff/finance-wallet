package ph.financewallet.app.ui.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.accounts.CheckRow
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.EmptyCard
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FigureCard
import ph.financewallet.app.ui.common.IconDisc
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.ScreenTitle
import ph.financewallet.app.ui.common.ShareBar
import ph.financewallet.app.ui.common.StatusPill
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.dateRange
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalFinanceColors
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.budget.GoalSummary
import ph.financewallet.data.budget.Leftover
import ph.financewallet.data.budget.LeftoverResolution
import ph.financewallet.domain.allocation.BudgetMath
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.money.Money
import java.time.temporal.ChronoUnit

/** Which action dialog is open on a budget card. */
private sealed interface BudgetAction {
    val periodId: String

    data class Allocate(
        override val periodId: String,
    ) : BudgetAction

    data class Fund(
        override val periodId: String,
    ) : BudgetAction

    data class SaveToGoal(
        override val periodId: String,
    ) : BudgetAction
}

@Composable
fun BudgetsScreen(
    vm: BudgetsViewModel,
    newKey: () -> String,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<BudgetAction?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.refresh() }
    if (state.loading) {
        LoadingState(stringResource(R.string.loading))
    } else {
        ListScreen(stringResource(R.string.budget_add), { creating = true }) {
            item { ScreenTitle(stringResource(R.string.nav_budgets)) }
            if (state.budgets.isEmpty()) {
                item {
                    EmptyCard(AppIcons.PieChart, stringResource(R.string.budget_empty_title), stringResource(R.string.budget_empty_message))
                }
            }
            items(state.budgets, key = { it.id }) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                    BudgetCardView(card, state.categoryNames, state.today, { action = it }, { id -> scope.launch { vm.close(id) } })
                    state.leftovers[card.id]?.let { list ->
                        LeftoverList(list, state.categoryNames) { l, r -> scope.launch { vm.resolve(l, r) } }
                    }
                }
            }
        }
    }
    if (creating) CreateBudgetDialog(vm, state.expenseCategories, onDismiss = { creating = false })
    action?.let { a -> BudgetActionDialog(vm, state, a, newKey, onDismiss = { action = null }) }
}

@Composable
private fun BudgetActionDialog(
    vm: BudgetsViewModel,
    state: BudgetsState,
    a: BudgetAction,
    newKey: () -> String,
    onDismiss: () -> Unit,
) {
    val key = remember(a) { newKey() }
    val budgetCategories =
        state.budgets
            .firstOrNull { it.current?.period?.id == a.periodId }
            ?.current
            ?.categories
            .orEmpty()
            .map { Option(it.categoryId, state.categoryNames[it.categoryId].orEmpty()) }
    val (title, choices) =
        when (a) {
            is BudgetAction.Allocate -> R.string.budget_allocate to budgetCategories
            is BudgetAction.Fund -> R.string.budget_fund to emptyList()
            is BudgetAction.SaveToGoal -> R.string.budget_save_to_goal to state.goals.map { Option(it.id, it.name) }
        }
    AmountActionDialog(
        title = stringResource(title),
        choices = choices,
        onSubmit = { choice, amount ->
            when (a) {
                is BudgetAction.Allocate -> vm.allocateTo(a.periodId, choice, amount, key)
                is BudgetAction.Fund -> vm.fund(a.periodId, amount, key)
                is BudgetAction.SaveToGoal -> vm.saveToGoalChoice(a.periodId, choice, amount, key)
            }
        },
        onDismiss = onDismiss,
    )
}

/** MANUAL rollover: leftovers of closed periods, each resolved once (BUD-07). */
@Composable
private fun LeftoverList(
    leftovers: List<Leftover>,
    names: Map<String, String>,
    onResolve: (Leftover, LeftoverResolution) -> Unit,
) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(stringResource(R.string.budget_leftovers_title), style = MaterialTheme.typography.titleSmall)
            leftovers.forEach { l ->
                LabeledRow(names[l.categoryId].orEmpty()) { AmountText(l.amount) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(
                        onClick = { onResolve(l, LeftoverResolution.CarryToNextPeriod) },
                    ) { Text(stringResource(R.string.budget_leftover_carry)) }
                    TextButton(
                        onClick = { onResolve(l, LeftoverResolution.Expire) },
                    ) { Text(stringResource(R.string.budget_leftover_expire)) }
                }
            }
        }
    }
}

@Composable
private fun BudgetCardView(
    card: BudgetCard,
    names: Map<String, String>,
    today: java.time.LocalDate?,
    onAction: (BudgetAction) -> Unit,
    onClose: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            val o = card.current
            BudgetHeader(card.name, o?.period?.start, o?.period?.endInclusive, today)
            if (o == null) {
                Text(stringResource(R.string.budget_no_current_period))
                return@Column
            }
            AvailablePanel(o.availableToBudget, o.funding)
            o.categories.forEach { line ->
                CategoryLine(names[line.categoryId].orEmpty(), line.remaining, line.spent, line.allocated)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OutlinedButton(
                    onClick = { onAction(BudgetAction.Allocate(o.period.id)) },
                ) { Text(stringResource(R.string.budget_allocate)) }
                if (card.incomeMode == IncomeMode.MANUAL) {
                    OutlinedButton(onClick = { onAction(BudgetAction.Fund(o.period.id)) }) { Text(stringResource(R.string.budget_fund)) }
                }
                OutlinedButton(
                    onClick = { onAction(BudgetAction.SaveToGoal(o.period.id)) },
                ) { Text(stringResource(R.string.budget_save_to_goal)) }
                TextButton(onClick = { onClose(o.period.id) }) { Text(stringResource(R.string.budget_close_period)) }
            }
        }
    }
}

/** Budget name, its current period as a compact range and how many days of it are left. */
@Composable
private fun BudgetHeader(
    name: String,
    start: java.time.LocalDate?,
    end: java.time.LocalDate?,
    today: java.time.LocalDate?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (start != null && end != null) {
                Text(dateRange(start, end), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (end != null && today != null && !today.isAfter(end)) {
            // Inclusive of today: on the last day of the period, 1 day is left.
            val days = (ChronoUnit.DAYS.between(today, end) + 1).toInt()
            StatusPill(
                pluralStringResource(R.plurals.budget_days_left, days, days),
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Money in this period not yet given to a category (its own concept, never added to account balances). */
@Composable
private fun AvailablePanel(
    available: Money,
    funding: Money,
) {
    val spacing = LocalSpacing.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            stringResource(R.string.budget_available),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        AmountText(available, emphasize = true)
        Text(
            stringResource(R.string.budget_available_caption, funding.display()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** One budget category: remaining amount, a usage bar (red once overspent) and spent of allocated. */
@Composable
private fun CategoryLine(
    name: String,
    remaining: Money,
    spent: Money,
    allocated: Money,
) {
    val over = remaining.isNegative
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = LocalSpacing.current.xs)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        AmountText(remaining)
        Text(
            " " + stringResource(if (over) R.string.budget_over else R.string.budget_left),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Integer basis points (floor, capped at 100 %); the float is only the bar's drawing fraction.
    val usedBp = if (allocated.isPositive) BudgetMath.goalProgressBp(spent, allocated) else 0
    // Overspent (including spending with nothing allocated) always reads as a full red bar.
    ShareBar(
        if (over) 1f else usedBp / FULL_BP,
        if (over) LocalFinanceColors.current.expense else MaterialTheme.colorScheme.secondary,
    )
    Text(
        stringResource(R.string.budget_line_detail, spent.display(), allocated.display()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AmountActionDialog(
    title: String,
    choices: List<Option>,
    onSubmit: suspend (String?, String) -> Int?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<Option?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                if (choices.isNotEmpty()) ChoiceChips(choices, choice, { it.label }, { choice = it })
                AmountField(amount, { amount = it }, stringResource(R.string.field_amount), null)
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = onSubmit(choice?.id, amount)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CreateBudgetDialog(
    vm: BudgetsViewModel,
    categories: List<Option>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("1") }
    var manual by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.budget_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                OutlinedTextField(
                    day,
                    { day = it },
                    label = { Text(stringResource(R.string.budget_start_day)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                CheckRow(stringResource(R.string.budget_manual_income), manual) { manual = it }
                Text(stringResource(R.string.budget_pick_categories), style = MaterialTheme.typography.bodySmall)
                if (categories.isEmpty()) Text(stringResource(R.string.entry_no_categories), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
                    categories.forEach { c ->
                        FilterChip(c.id in picked, onClick = {
                            if (c.id in
                                picked
                            ) {
                                picked.remove(c.id)
                            } else {
                                picked.add(c.id)
                            }
                        }, label = { Text(c.label) })
                    }
                }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.createMonthly(name, day, picked.toList(), if (manual) IncomeMode.MANUAL else IncomeMode.TRACKED)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun GoalsScreen(
    vm: GoalsViewModel,
    newKey: () -> String,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<GoalSummary?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    ListScreen(stringResource(R.string.goal_add), { creating = true }) {
        item {
            FigureCard(
                stringResource(R.string.goal_free_money),
                state.freeMoney,
                stringResource(R.string.goal_free_money_caption),
                icon = AppIcons.Wallet,
            )
        }
        if (!state.loading && state.goals.isEmpty()) {
            item { EmptyCard(AppIcons.Flag, stringResource(R.string.goal_empty_title), stringResource(R.string.goal_empty_message)) }
        }
        items(state.goals, key = { it.id }) { g -> GoalCard(g, onClick = { selected = g }) }
    }
    if (creating) CreateGoalDialog(vm, onDismiss = { creating = false })
    selected?.let { GoalDialog(vm, it, newKey, onDismiss = { selected = null }) }
}

@Composable
private fun GoalCard(
    g: GoalSummary,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    SurfaceCard(onClick = onClick) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                IconDisc(AppIcons.Flag, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.goal_amounts, g.allocated.display(), g.target.display()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.goal_progress, g.progressBp / BP_PER_PERCENT),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            ShareBar(g.progressBp / FULL_BP, MaterialTheme.colorScheme.secondary)
            g.linkedHeadroom?.let {
                Text(
                    stringResource(R.string.goal_linked_room, it.display()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CreateGoalDialog(
    vm: GoalsViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                AmountField(target, { target = it }, stringResource(R.string.goal_target), null)
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.create(name, target)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun GoalDialog(
    vm: GoalsViewModel,
    g: GoalSummary,
    newKey: () -> String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val key = remember(g.id) { newKey() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(g.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                AmountField(amount, { amount = it }, stringResource(R.string.field_amount), null)
                CheckRow(stringResource(R.string.goal_release_confirm), confirmed) { confirmed = it }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.earmark(g.id, amount, key)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.goal_add_money)) }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.release(g.id, amount, confirmed, key + ":release")
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.goal_release)) }
        },
    )
}

private const val BP_PER_PERCENT = 100
private const val FULL_BP = 10_000f
