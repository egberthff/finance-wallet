package ph.financewallet.app.ui.advanced

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.CardList
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.FigureCard
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.ShareBar
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.dateRange
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.common.monthShort
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.AppIcons
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
            export("finance-manager-${range.start}-to-${range.endInclusive}.csv")
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
    val fc = LocalFinanceColors.current
    ListScreen {
        item { RangeHeader(vm, state) }
        item { IncomeExpenseCard(state) }
        if (state.spending.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.reports_spending_by_category)) }
            item { CategoryCard(state.spending, negative = true, color = fc.expense, onClick = vm::drillDown) }
        }
        if (state.income.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.reports_income_by_category)) }
            item { CategoryCard(state.income, negative = false, color = fc.income, onClick = vm::drillDown) }
        }
        if (state.byAccount.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.reports_by_account)) }
            item { CardList(state.byAccount) { ListRow(it.name) { AmountText(-it.amount) } } }
        }
        state.netWorth?.let { nw -> item { NetWorthSection(nw, state.netWorthHistory) } }
        if (state.recurring.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.bill_yearly_title)) }
            item {
                CardList(state.recurring) { c ->
                    val income = c.kind == ph.financewallet.data.advanced.RuleKind.EXPECTED_INCOME
                    ListRow(c.name) { AmountText(if (income) c.nextTwelveMonths else -c.nextTwelveMonths) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reports_export)) }
                exportResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    stringResource(R.string.reports_basis_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Week / month / quarter / year, then the period itself with arrows to step through earlier and later ones. */
@Composable
private fun RangeHeader(
    vm: ReportsViewModel,
    state: ReportsState,
) {
    val range = checkNotNull(state.range)
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        ChoiceChips(RangeKind.entries.toList(), state.kind, { stringResource(rangeLabel(it)) }, vm::select)
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shift(-1) }) {
                    Icon(AppIcons.ChevronRight, stringResource(R.string.reports_earlier), Modifier.rotate(HALF_TURN))
                }
                Text(
                    dateRange(range.start, range.endInclusive),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.shift(1) }) { Icon(AppIcons.ChevronRight, stringResource(R.string.reports_later)) }
            }
        }
    }
}

private const val HALF_TURN = 180f

/** Category totals with a bar for each one's share of the largest; tap a category to see its transactions. */
@Composable
private fun CategoryCard(
    lines: List<CategoryReportLine>,
    negative: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onClick: (CategoryReportLine) -> Unit,
) {
    val largest = lines.maxOf { kotlin.math.abs(it.total.minor) }.coerceAtLeast(1L)
    CardList(lines) { line ->
        // Display-only scaling of integer amounts to a bar length; no money arithmetic happens here.
        val share = (kotlin.math.abs(line.total.minor).toDouble() / largest).toFloat()
        CategoryRow(line, negative, share, color, onClick = { onClick(line) })
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
    SurfaceCard {
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
    share: Float,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val name = line.systemKey?.let { stringResource(systemCategoryLabel(it)) } ?: line.name
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = spacing.md, vertical = spacing.sm + spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            AmountText(if (negative) -line.total else line.total)
        }
        ShareBar(share, color)
        line.children.forEach { c ->
            Row(Modifier.padding(start = spacing.md)) {
                Text(
                    c.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(c.amount.display(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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

/**
 * Month-end net worth as bars, from the first month with any records (leading empty months are left out so one month
 * of data does not sit at the edge of a blank chart). Each bar also has a text description for screen readers (UX-04).
 */
@Composable
private fun NetWorthChart(history: List<Pair<LocalDate, Money>>) {
    val points = history.dropWhile { it.second.isZero }
    if (points.isEmpty()) return
    val colors = LocalFinanceColors.current
    val spacing = LocalSpacing.current
    val maxAbs = points.maxOf { kotlin.math.abs(it.second.minor) }.coerceAtLeast(1L)
    val hasNegative = points.any { it.second.isNegative }
    val description = points.joinToString { "${it.first.display()}: ${it.second.display()}" }
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT.dp).semantics { contentDescription = description }) {
                val slot = size.width / points.size
                val baseline = if (hasNegative) size.height / 2 else size.height
                val barWidth = (slot * (1 - 2 * BAR_GAP)).coerceAtMost(MAX_BAR.dp.toPx())
                points.forEachIndexed { i, (_, value) ->
                    // Display-only scaling of integer amounts to pixels; no money arithmetic happens here.
                    val h = (kotlin.math.abs(value.minor).toDouble() / maxAbs * baseline).toFloat()
                    val top = if (value.isNegative) baseline else baseline - h
                    drawRoundRect(
                        color = if (value.isNegative) colors.expense else colors.income,
                        topLeft = Offset(i * slot + (slot - barWidth) / 2, top),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(BAR_RADIUS.dp.toPx()),
                    )
                }
                drawLine(colors.neutral, Offset(0f, baseline), Offset(size.width, baseline))
            }
            Row(Modifier.fillMaxWidth()) {
                points.forEach { (date, _) ->
                    Text(
                        date.monthShort(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                stringResource(R.string.reports_chart_range, points.first().first.display(), points.last().first.display()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val CHART_HEIGHT = 140
private const val MAX_BAR = 32
private const val BAR_RADIUS = 4
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
                    LabeledRow("${t.date.display()} · ${t.description.ifBlank { t.accountName }}") { AmountText(t.amount) }
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
