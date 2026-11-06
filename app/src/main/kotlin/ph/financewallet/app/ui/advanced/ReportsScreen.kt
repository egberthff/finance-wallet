package ph.financewallet.app.ui.advanced

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.FigureCard
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.LocalFinanceColors
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.advanced.CategoryReportLine
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.report.RangeKind
import java.time.LocalDate

@Composable
fun ReportsScreen(vm: ReportsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var exportWarning by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val export = rememberCsvExport(vm) { exportResult = it }
    if (state.loading || state.range == null) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    val range = checkNotNull(state.range)
    ReportsContent(vm, state, onExport = { exportWarning = true }, exportResult = exportResult)
    if (exportWarning) {
        ExportWarning(onConfirm = {
            exportWarning = false
            export("finance-wallet-${range.start}-to-${range.endInclusive}.csv")
        }, onDismiss = { exportWarning = false })
    }
    state.drillDown?.let { (name, items) -> DrillDownDialog(name, items, onDismiss = vm::closeDrillDown) }
}

@Composable
private fun ReportsContent(
    vm: ReportsViewModel,
    state: ReportsState,
    onExport: () -> Unit,
    exportResult: String?,
) {
    val spacing = LocalSpacing.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item { RangeHeader(vm, state) }
        item { IncomeExpenseCard(state) }
        item { SectionTitle(stringResource(R.string.reports_spending_by_category)) }
        items(state.spending, key = { "s" + it.categoryId }) { CategoryRow(it, negative = true, onClick = { vm.drillDown(it) }) }
        item { SectionTitle(stringResource(R.string.reports_income_by_category)) }
        items(state.income, key = { "i" + it.categoryId }) { CategoryRow(it, negative = false, onClick = { vm.drillDown(it) }) }
        item { SectionTitle(stringResource(R.string.reports_by_account)) }
        items(state.byAccount, key = { "a" + it.name }) { LabeledRow(it.name) { AmountText(-it.amount) } }
        state.netWorth?.let { nw -> item { NetWorthSection(nw, state.netWorthHistory) } }
        if (state.recurring.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.bill_yearly_title)) }
            items(state.recurring, key = { "r" + it.ruleId }) { c ->
                val income = c.kind == ph.financewallet.data.advanced.RuleKind.EXPECTED_INCOME
                LabeledRow(c.name) { AmountText(if (income) c.nextTwelveMonths else -c.nextTwelveMonths) }
            }
        }
        item {
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reports_export)) }
            exportResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.reports_basis_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RangeHeader(
    vm: ReportsViewModel,
    state: ReportsState,
) {
    val range = checkNotNull(state.range)
    val spacing = LocalSpacing.current
    SectionTitle(stringResource(R.string.reports_title))
    ChoiceChips(RangeKind.entries.toList(), state.kind, { stringResource(rangeLabel(it)) }, vm::select)
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        TextButton(onClick = { vm.shift(-1) }) { Text(stringResource(R.string.reports_previous)) }
        Text(
            stringResource(R.string.budget_period, range.start.toString(), range.endInclusive.toString()),
            Modifier.padding(top = spacing.sm),
        )
        TextButton(onClick = { vm.shift(1) }) { Text(stringResource(R.string.reports_next)) }
    }
}

@Composable
private fun NetWorthSection(
    nw: ph.financewallet.data.advanced.NetWorthBreakdown,
    history: List<Pair<LocalDate, Money>>,
) {
    SectionTitle(stringResource(R.string.home_net_worth))
    val parts = stringResource(R.string.reports_net_worth_parts, nw.accounts.display(), nw.otherAssets.display(), nw.liabilities.display())
    FigureCard(stringResource(R.string.home_net_worth), nw.total, parts)
    NetWorthChart(history)
}

@Composable
private fun IncomeExpenseCard(state: ReportsState) {
    val spacing = LocalSpacing.current
    val zero = Money.zero(ph.financewallet.domain.money.CurrencyCode.PHP)
    val s = state.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            LabeledRow(stringResource(R.string.home_income)) { AmountText(s?.income ?: zero) }
            LabeledRow(stringResource(R.string.home_spending)) { AmountText(-(s?.spending ?: zero)) }
            LabeledRow(stringResource(R.string.reports_difference)) { AmountText((s?.income ?: zero) - (s?.spending ?: zero)) }
            state.cashFlow?.let { cf ->
                LabeledRow(stringResource(R.string.reports_cash_in)) { AmountText(cf.inflow) }
                LabeledRow(stringResource(R.string.reports_cash_out)) { AmountText(-cf.outflow) }
            }
            if (s != null && !s.adjustments.isZero) {
                LabeledRow(stringResource(R.string.reports_adjustments)) { AmountText(s.adjustments) }
            }
            Text(stringResource(R.string.home_month_caption), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CategoryRow(
    line: CategoryReportLine,
    negative: Boolean,
    onClick: () -> Unit,
) {
    val name = line.systemKey?.let { stringResource(systemCategoryLabel(it)) } ?: line.name
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = LocalSpacing.current.xs)) {
        LabeledRow(name) { AmountText(if (negative) -line.total else line.total) }
        line.children.forEach { c ->
            Text("   ${c.name}: ${c.amount.display()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun systemCategoryLabel(key: String): Int =
    when (key) {
        "expense:interest_fees" -> R.string.category_interest_fees
        "expense:uncategorized" -> R.string.category_uncategorized
        "income:uncategorized" -> R.string.category_uncategorized
        else -> R.string.category_uncategorized
    }

/** Month-end net worth as bars. Each bar also has a text description for screen readers (UX-04). */
@Composable
private fun NetWorthChart(points: List<Pair<LocalDate, Money>>) {
    if (points.isEmpty()) return
    val colors = LocalFinanceColors.current
    val maxAbs = points.maxOf { kotlin.math.abs(it.second.minor) }.coerceAtLeast(1L)
    val description = points.joinToString { "${it.first}: ${it.second.display()}" }
    Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT.dp).semantics { contentDescription = description }) {
        val slot = size.width / points.size
        val baseline = size.height / 2
        points.forEachIndexed { i, (_, value) ->
            // Display-only scaling of integer amounts to pixels; no money arithmetic happens here.
            val h = (kotlin.math.abs(value.minor).toDouble() / maxAbs * baseline).toFloat()
            val top = if (value.isNegative) baseline else baseline - h
            drawRect(
                color = if (value.isNegative) colors.expense else colors.income,
                topLeft = Offset(i * slot + slot * BAR_GAP, top),
                size = Size(slot * (1 - 2 * BAR_GAP), h),
            )
        }
        drawLine(colors.neutral, Offset(0f, baseline), Offset(size.width, baseline))
    }
    Text(
        stringResource(R.string.reports_chart_range, points.first().first.toString(), points.last().first.toString()),
        style = MaterialTheme.typography.bodySmall,
    )
}

private const val CHART_HEIGHT = 120
private const val BAR_GAP = 0.15f

@Composable
private fun ExportWarning(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reports_export)) },
        text = { Text(stringResource(R.string.reports_export_warning)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.reports_export_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Creates the file through the system picker (SAF), so the user chooses where plaintext data goes (UC-30). */
@Composable
private fun rememberCsvExport(
    vm: ReportsViewModel,
    onResult: (String) -> Unit,
): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val failed = stringResource(R.string.reports_export_failed)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val rows =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver
                                .openOutputStream(uri)
                                ?.bufferedWriter(Charsets.UTF_8)
                                ?.use { vm.export(it) }
                        }.getOrNull()
                    }
                onResult(if (rows != null) resources.getQuantityString(R.plurals.reports_export_done, rows, rows) else failed)
            }
        }
    return { name -> launcher.launch(name) }
}

@Composable
private fun DrillDownDialog(
    name: String,
    items: List<ph.financewallet.data.ledger.TransactionListItem>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            LazyColumn {
                items(items, key = { it.id }) { t ->
                    LabeledRow("${t.date} · ${t.description.ifBlank { t.accountName }}") { AmountText(t.amount) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private fun rangeLabel(k: RangeKind): Int =
    when (k) {
        RangeKind.WEEK -> R.string.range_week
        RangeKind.MONTH -> R.string.range_month
        RangeKind.QUARTER -> R.string.range_quarter
        RangeKind.YEAR -> R.string.range_year
    }
