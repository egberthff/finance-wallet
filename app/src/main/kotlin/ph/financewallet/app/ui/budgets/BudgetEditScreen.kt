package ph.financewallet.app.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.accounts.CheckRow
import ph.financewallet.app.ui.categories.CategoryChecklist
import ph.financewallet.app.ui.categories.CategoryOption
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FieldLabel
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.dateRange
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.app.ui.transactions.DateField
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.budget.AllocationMode
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.allocation.RolloverPolicy
import java.time.LocalDate

/**
 * The period type and its inputs, with a "Next periods" preview computed by the same PeriodCalculator the budget will
 * use, so the user sees the real dates (year ends, short months, 29 February) before saving (BUD-01/02).
 */
@Composable
fun PeriodSelector(
    form: PeriodForm,
    earliest: LocalDate,
    forNewBudget: Boolean,
    onChange: (PeriodForm) -> Unit,
) {
    ChoiceChips(PeriodChoice.entries.toList(), form.choice, { stringResource(it.label) }, { onChange(form.copy(choice = it)) })
    when (form.choice) {
        PeriodChoice.MONTHLY -> {
            NumberInput(form.day1, R.string.budget_start_day) { onChange(form.copy(day1 = it)) }
        }

        PeriodChoice.SEMI_MONTHLY -> {
            NumberInput(form.day1, R.string.period_first_day) { onChange(form.copy(day1 = it)) }
            NumberInput(form.day2, R.string.period_second_day) { onChange(form.copy(day2 = it)) }
        }

        PeriodChoice.WEEKLY, PeriodChoice.BIWEEKLY -> {
            DateInput(R.string.period_starts_on, form.start ?: earliest) { onChange(form.copy(start = it)) }
        }

        PeriodChoice.EVERY_N_DAYS -> {
            NumberInput(form.everyNDays, R.string.period_days) { onChange(form.copy(everyNDays = it)) }
            DateInput(R.string.period_starts_on, form.start ?: earliest) { onChange(form.copy(start = it)) }
        }

        PeriodChoice.CUSTOM_ONCE -> {
            DateInput(R.string.period_starts_on, form.start ?: earliest) { onChange(form.copy(start = it)) }
            DateInput(R.string.period_ends_on, withDefaultEnd(form, earliest).end ?: earliest) { onChange(form.copy(end = it)) }
        }
    }
    PeriodPreview(withDefaultEnd(form, earliest), earliest, forNewBudget)
}

@Composable
private fun NumberInput(
    value: String,
    label: Int,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun DateInput(
    label: Int,
    date: LocalDate,
    onPick: (LocalDate) -> Unit,
) {
    FieldLabel(stringResource(label))
    DateField(date.toString()) { onPick(LocalDate.parse(it)) }
}

/** "Next periods: …" from the rule the form describes, or the reason it is not valid yet. */
@Composable
private fun PeriodPreview(
    form: PeriodForm,
    earliest: LocalDate,
    forNewBudget: Boolean,
) {
    val (rule, error) = form.toRule(earliest, forNewBudget)
    if (rule == null) {
        ErrorLine(error)
        return
    }
    val periods = previewPeriods(rule, from = earliest)
    Text(
        stringResource(R.string.period_preview, periods.joinToString(" · ") { dateRange(it.start, it.endInclusive) }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** New budget: name, period type with preview, income mode, categories (checklist grouped by main category). */
@Composable
internal fun CreateBudgetDialog(
    vm: BudgetsViewModel,
    categories: List<CategoryOption>,
    today: LocalDate,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(PeriodForm()) }
    var manual by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.budget_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                FieldLabel(stringResource(R.string.period_title))
                PeriodSelector(period, today, forNewBudget = true) { period = it }
                CheckRow(stringResource(R.string.budget_manual_income), manual) { manual = it }
                FieldLabel(stringResource(R.string.budget_pick_categories))
                if (categories.isEmpty()) Text(stringResource(R.string.entry_no_categories), style = MaterialTheme.typography.bodySmall)
                CategoryChecklist(categories, picked) { id -> picked = if (id in picked) picked - id else picked + id }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val ids = categories.map { it.id }.filter { it in picked }
                    error = vm.create(name, withDefaultEnd(period, today), ids, if (manual) IncomeMode.MANUAL else IncomeMode.TRACKED)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A custom period shows a default end date; use the same one when saving, so what was previewed is what is stored. */
private fun withDefaultEnd(
    form: PeriodForm,
    earliest: LocalDate,
): PeriodForm =
    if (form.choice == PeriodChoice.CUSTOM_ONCE &&
        form.end == null
    ) {
        form.copy(end = (form.start ?: earliest).plusMonths(1).minusDays(1))
    } else {
        form
    }

/** Budget edit screen (UC-16; blueprint I). */
@Composable
fun BudgetEditScreen(
    vm: BudgetEditViewModel,
    onClosed: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<Int?>(null) }
    var archiving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }
    if (state.loading) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    if (state.loaded == null) {
        Text(stringResource(R.string.err_not_found), Modifier.padding(LocalSpacing.current.md))
        return
    }
    val form = state.form
    ListScreen {
        item { SettingsCard(form, vm) }
        item { SectionTitle(stringResource(R.string.budget_pick_categories)) }
        item {
            SurfaceCard {
                Column(Modifier.padding(LocalSpacing.current.sm)) {
                    CategoryChecklist(state.categories, form.specs.map { it.categoryId }.toSet(), vm::toggleCategory)
                }
            }
        }
        items(form.specs.size) { i ->
            val spec = form.specs[i]
            val title =
                state.categories
                    .firstOrNull { it.id == spec.categoryId }
                    ?.path
                    .orEmpty()
            SpecCard(spec, title, state.goals) { change -> vm.editSpec(spec.categoryId, change) }
        }
        item {
            ErrorLine(message)
            Button(onClick = {
                scope.launch {
                    message = vm.save()
                    if (message == null) onClosed()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
        item { RuleSection(vm) }
        item {
            TextButton(onClick = { archiving = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.budget_archive), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (archiving) {
        ArchiveBudgetDialog(form.name, onCancel = { archiving = false }) {
            scope.launch {
                message = vm.archive()
                archiving = false
                if (message == null) onClosed()
            }
        }
    }
}

@Composable
private fun SettingsCard(
    form: BudgetEditForm,
    vm: BudgetEditViewModel,
) {
    SurfaceCard {
        Column(Modifier.padding(LocalSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
            OutlinedTextField(
                form.name,
                { t -> vm.edit { it.copy(name = t) } },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CheckRow(stringResource(R.string.budget_manual_income), form.manualIncome) { v -> vm.edit { it.copy(manualIncome = v) } }
        }
    }
}

/** Archive keeps the budget and its periods; it asks first. */
@Composable
private fun ArchiveBudgetDialog(
    name: String,
    onCancel: () -> Unit,
    onArchive: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.budget_archive_title, name)) },
        text = { Text(stringResource(R.string.budget_archive_message)) },
        confirmButton = {
            TextButton(onClick = onArchive) { Text(stringResource(R.string.budget_archive), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Per-category settings: allocation mode and amount, rollover policy, carry negative, and the goal for a sweep. */
@Composable
private fun SpecCard(
    spec: SpecForm,
    title: String,
    goals: List<Option>,
    onChange: ((SpecForm) -> SpecForm) -> Unit,
) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            FieldLabel(stringResource(R.string.budget_allocation_mode))
            ChoiceChips(
                AllocationMode.entries.toList(),
                spec.mode,
                { stringResource(allocationLabel(it)) },
                { m -> onChange { it.copy(mode = m) } },
            )
            when (spec.mode) {
                AllocationMode.FIXED -> {
                    AmountField(
                        spec.amount,
                        { t -> onChange { it.copy(amount = t) } },
                        stringResource(R.string.budget_default_amount),
                        null,
                    )
                }

                AllocationMode.PERCENT -> {
                    OutlinedTextField(spec.percent, { t ->
                        onChange {
                            it.copy(percent = t)
                        }
                    }, label = {
                        Text(stringResource(R.string.budget_percent))
                    }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }

                AllocationMode.NONE -> {} // nothing to enter
            }
            FieldLabel(stringResource(R.string.budget_rollover))
            ChoiceChips(RolloverPolicy.entries.toList(), spec.rollover, {
                stringResource(rolloverLabel(it))
            }, { r -> onChange { it.copy(rollover = r) } })
            if (spec.rollover == RolloverPolicy.SWEEP_TO_GOAL) {
                FieldLabel(stringResource(R.string.budget_sweep_goal))
                if (goals.isEmpty()) Text(stringResource(R.string.budget_no_goals), style = MaterialTheme.typography.bodySmall)
                ChoiceChips(
                    goals,
                    goals.firstOrNull { it.id == spec.sweepGoalId },
                    { it.label },
                    { g -> onChange { it.copy(sweepGoalId = g.id) } },
                )
            }
            CheckRow(stringResource(R.string.budget_carry_negative), spec.carryNegative) { v -> onChange { it.copy(carryNegative = v) } }
        }
    }
}

/** Period rule change: applies from the first day after the current period, with a preview of the new periods. */
@Composable
private fun RuleSection(vm: BudgetEditViewModel) {
    val scope = rememberCoroutineScope()
    val from = vm.ruleChangeFrom() ?: return
    var editing by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(vm.currentRule()?.toForm() ?: PeriodForm()) }
    var error by remember { mutableStateOf<Int?>(null) }
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(stringResource(R.string.period_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.period_change_from, from.display()), style = MaterialTheme.typography.bodySmall)
            if (!editing) {
                OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.period_change)) }
                return@Column
            }
            PeriodSelector(form, from, forNewBudget = false) { form = it }
            ErrorLine(error)
            Button(onClick = {
                scope.launch {
                    error = vm.changeRule(withDefaultEnd(form, from))
                    if (error == null) editing = false
                }
            }) { Text(stringResource(R.string.period_change_save)) }
        }
    }
}

private fun allocationLabel(m: AllocationMode): Int =
    when (m) {
        AllocationMode.NONE -> R.string.budget_alloc_none
        AllocationMode.FIXED -> R.string.budget_alloc_fixed
        AllocationMode.PERCENT -> R.string.budget_alloc_percent
    }

private fun rolloverLabel(r: RolloverPolicy): Int =
    when (r) {
        RolloverPolicy.EXPIRE -> R.string.budget_rollover_expire
        RolloverPolicy.CARRY -> R.string.budget_rollover_carry
        RolloverPolicy.SWEEP_TO_GOAL -> R.string.budget_rollover_sweep
        RolloverPolicy.MANUAL -> R.string.budget_rollover_manual
    }
