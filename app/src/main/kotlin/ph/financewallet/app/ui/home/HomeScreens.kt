package ph.financewallet.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.categories.CategoryIcon
import ph.financewallet.app.ui.categories.categoryStyleFor
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.CardList
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FigureCard
import ph.financewallet.app.ui.common.IconDisc
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.NavRow
import ph.financewallet.app.ui.common.ScreenTitle
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.Tables
import ph.financewallet.app.ui.common.TxnAmount
import ph.financewallet.app.ui.common.TxnIcon
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.reloadOn
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalFinanceColors
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.ledger.TransactionListItem
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dashboard figures (PRD §17). Each is a different concept with its own caption, and none is added to another:
 * account money, amounts owed, net worth, free money (after unlinked goal earmarks), and this month's income
 * and spending (transfers and savings moves excluded by the ledger classification).
 */
data class HomeState(
    val loading: Boolean = true,
    val inAccounts: Money = Money.zero(CurrencyCode.PHP),
    val owed: Money = Money.zero(CurrencyCode.PHP),
    val netWorth: Money = Money.zero(CurrencyCode.PHP),
    val freeMoney: Money = Money.zero(CurrencyCode.PHP),
    val monthIncome: Money = Money.zero(CurrencyCode.PHP),
    val monthSpending: Money = Money.zero(CurrencyCode.PHP),
    val recent: List<TransactionListItem> = emptyList(),
    val hasAccounts: Boolean = false,
    /** Unpaid bills due in the next 7 days or overdue (never counted as paid until a transaction is linked). */
    val upcomingBills: List<ph.financewallet.data.advanced.UpcomingItem> = emptyList(),
    /** R-10: there are transactions but no backup, or the last one is older than 30 days. */
    val backupDue: Boolean = false,
    val today: java.time.LocalDate? = null,
    /** Icon and colour per category id (archived included) for recent rows titled by their category. */
    val categoryStyles: Map<String, CategoryRow> = emptyMap(),
)

class HomeViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = mutable

    init {
        refresh()
        reloadOn(s, *Tables.HOME) { mutable.value = load() }
    }

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    /** PHP figures (initial market). Accounts in other currencies are not converted or mixed in. */
    suspend fun load(): HomeState {
        val php = CurrencyCode.PHP
        val today = s.ctx.today()
        val accounts = s.accounts.accounts().filter { it.currency == php.code }
        val balances = accounts.map { it to s.ledger.balance(it.id, today) }
        val assets = balances.filter { it.first.accountClass == AccountClass.ASSET.name }.map { it.second }
        val liabilities = balances.filter { it.first.accountClass == AccountClass.LIABILITY.name }.map { it.second }
        // Same source as Reports: included accounts, liabilities and user-entered asset values (NW-01). One number, one rule.
        val netWorth =
            s.reports
                .netWorth(today)
                .firstOrNull { it.currency == php }
                ?.total ?: Money.zero(php)
        val month = s.ledger.summary(today.withDayOfMonth(1), today)[php]
        val recent = s.transactions.page(limit = RECENT).items
        val lastBackup = s.backup.lastBackupAt()
        return HomeState(
            loading = false,
            today = today,
            inAccounts = Money.sum(assets, php),
            owed = -Money.sum(liabilities, php),
            netWorth = netWorth,
            freeMoney = s.goals.freeMoney(php),
            monthIncome = month?.income ?: Money.zero(php),
            monthSpending = month?.spending ?: Money.zero(php),
            recent = recent,
            categoryStyles = s.categories.categories(includeArchived = true).associateBy { it.id },
            backupDue = recent.isNotEmpty() && (lastBackup == null || s.ctx.nowMillis() - lastBackup > BACKUP_REMINDER_MS),
            hasAccounts = accounts.isNotEmpty(),
            upcomingBills =
                s.recurring
                    .upcoming(today.minusDays(BILLS_BACK), today.plusDays(BILLS_AHEAD))
                    .filter { it.kind != ph.financewallet.data.advanced.RuleKind.EXPECTED_INCOME }
                    .filter {
                        it.state !in
                            setOf(
                                ph.financewallet.data.advanced.OccurrenceState.PAID,
                                ph.financewallet.data.advanced.OccurrenceState.SKIPPED,
                            )
                    },
        )
    }

    private companion object {
        const val RECENT = 5
        const val BILLS_BACK = 31L
        const val BILLS_AHEAD = 7L
        const val BACKUP_REMINDER_MS = 30L * 24 * 60 * 60 * 1000
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onAddTransaction: () -> Unit,
    onBills: () -> Unit,
    onBackup: () -> Unit,
    onOpenTransaction: (String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.loading) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    val spacing = LocalSpacing.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = spacing.md, end = spacing.md, bottom = FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(spacing.sm + spacing.xs),
        ) {
            item { ScreenTitle(stringResource(R.string.home_title), state.today?.format(HEADER_DATE)) }
            if (!state.hasAccounts) item { NoticeCard(AppIcons.Wallet, stringResource(R.string.home_no_accounts), null, null) }
            if (state.backupDue) {
                item {
                    NoticeCard(
                        AppIcons.Save,
                        stringResource(R.string.home_backup_due),
                        stringResource(R.string.home_backup_due_caption),
                        stringResource(R.string.backup_title) to onBackup,
                    )
                }
            }
            item { HeroCard(state.inAccounts) }
            item { SideBySideFigures(state) }
            item {
                FigureCard(
                    stringResource(R.string.home_net_worth),
                    state.netWorth,
                    stringResource(R.string.home_net_worth_caption),
                    icon = AppIcons.BarChart,
                )
            }
            item { MonthCard(state) }
            if (state.upcomingBills.isNotEmpty()) item { UpcomingBills(state.upcomingBills, onBills) }
            if (state.recent.isNotEmpty()) item { RecentCard(state.recent, state.categoryStyles, onOpenTransaction) }
        }
        ExtendedFloatingActionButton(
            onClick = onAddTransaction,
            icon = { Icon(AppIcons.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.txn_add)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.md),
        )
    }
}

/** The account-money figure, given visual priority. Other figures stay in their own cards and are never added to it. */
@Composable
private fun HeroCard(inAccounts: Money) {
    val spacing = LocalSpacing.current
    val fc = LocalFinanceColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(fc.heroStart, fc.heroEnd)))
            .padding(spacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(stringResource(R.string.home_in_accounts), style = MaterialTheme.typography.labelLarge, color = fc.onHeroMuted)
        Text(
            inAccounts.display(),
            style = MaterialTheme.typography.displaySmall,
            color = fc.onHero,
            maxLines = 1,
        )
        Text(stringResource(R.string.home_in_accounts_caption), style = MaterialTheme.typography.bodySmall, color = fc.onHeroMuted)
    }
}

@Composable
private fun SideBySideFigures(state: HomeState) {
    val spacing = LocalSpacing.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(spacing.sm + spacing.xs)) {
        FigureCard(
            stringResource(R.string.home_free_money),
            state.freeMoney,
            stringResource(R.string.home_free_money_caption),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = AppIcons.Wallet,
        )
        FigureCard(
            stringResource(R.string.home_owed),
            state.owed,
            stringResource(R.string.home_owed_caption),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = AppIcons.CreditCard,
        )
    }
}

@Composable
private fun MonthCard(state: HomeState) {
    val spacing = LocalSpacing.current
    val fc = LocalFinanceColors.current
    SurfaceCard {
        Column(Modifier.padding(vertical = spacing.md)) {
            Text(
                stringResource(R.string.home_this_month),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = spacing.md),
            )
            ListRow(
                stringResource(R.string.home_income),
                leading = { IconDisc(AppIcons.TrendingUp, fc.income, fc.incomeSoft) },
            ) { AmountText(state.monthIncome) }
            ListRow(
                stringResource(R.string.home_spending),
                leading = { IconDisc(AppIcons.TrendingDown, fc.expense, fc.expenseSoft) },
            ) { AmountText(-state.monthSpending) }
            Text(
                stringResource(R.string.home_month_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.md),
            )
        }
    }
}

@Composable
private fun UpcomingBills(
    bills: List<ph.financewallet.data.advanced.UpcomingItem>,
    onBills: () -> Unit,
) {
    val fc = LocalFinanceColors.current
    Column {
        SectionTitle(stringResource(R.string.home_upcoming_bills))
        SurfaceCard {
            bills.forEach { b ->
                ListRow(
                    b.name,
                    subtitle = b.dueDate.display(),
                    leading = { IconDisc(AppIcons.Event, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer) },
                ) { AmountText(-b.amount) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(stringResource(R.string.bills_title), onClick = onBills) {
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = fc.neutral)
            }
        }
    }
}

@Composable
private fun RecentCard(
    recent: List<TransactionListItem>,
    categoryStyles: Map<String, CategoryRow>,
    onOpen: (String) -> Unit,
) {
    Column {
        SectionTitle(stringResource(R.string.home_recent))
        SurfaceCard {
            recent.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    ph.financewallet.app.ui.transactions
                        .txnTitle(t),
                    subtitle = "${t.date.display()} · ${t.accountName}",
                    onClick = { onOpen(t.id) },
                    leading = {
                        val style = categoryStyleFor(t, categoryStyles)
                        if (style != null) CategoryIcon(style) else TxnIcon(t.type, t.amount)
                    },
                ) { TxnAmount(t.type, t.amount) }
            }
        }
    }
}

/** A highlighted message with an optional action (getting started, backup reminder). */
@Composable
private fun NoticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String?,
    action: Pair<String, () -> Unit>?,
) {
    val spacing = LocalSpacing.current
    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
    ) {
        Row(Modifier.padding(spacing.md), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Icon(icon, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body != null) Text(body, style = MaterialTheme.typography.bodySmall)
                if (action != null) {
                    Button(onClick = action.second) { Text(action.first) }
                }
            }
        }
    }
}

private val FAB_CLEARANCE = 96.dp
private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

@Composable
fun MoreScreen(
    onGoals: () -> Unit,
    onCategories: () -> Unit,
    onDebts: () -> Unit,
    onBills: () -> Unit,
    onAssets: () -> Unit,
    onReports: () -> Unit,
    onImport: () -> Unit,
    onImportHistory: () -> Unit,
    onRules: () -> Unit,
    onInsights: () -> Unit,
    onBackup: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sections =
        listOf(
            R.string.more_section_understand to
                listOf(
                    MoreEntry(AppIcons.BarChart, R.string.reports_title, onReports),
                    MoreEntry(AppIcons.Insights, R.string.insights_title, onInsights),
                ),
            R.string.more_section_plan to
                listOf(
                    MoreEntry(AppIcons.Flag, R.string.goals_title, onGoals),
                    MoreEntry(AppIcons.Event, R.string.bills_title, onBills),
                    MoreEntry(AppIcons.CreditCard, R.string.debts_title, onDebts),
                    MoreEntry(AppIcons.Bank, R.string.assets_title, onAssets),
                    MoreEntry(AppIcons.Label, R.string.categories_title, onCategories),
                ),
            R.string.more_section_import to
                listOf(
                    MoreEntry(AppIcons.Upload, R.string.import_title, onImport),
                    MoreEntry(AppIcons.History, R.string.import_history_title, onImportHistory),
                    MoreEntry(AppIcons.Tune, R.string.rules_title, onRules),
                ),
            R.string.more_section_data to
                listOf(
                    MoreEntry(AppIcons.Save, R.string.backup_title, onBackup),
                    MoreEntry(AppIcons.Shield, R.string.privacy_title, onPrivacy),
                ),
        )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = spacing.md, end = spacing.md, bottom = spacing.lg)) {
        item { ScreenTitle(stringResource(R.string.nav_more)) }
        sections.forEach { (title, entries) ->
            item { SectionTitle(stringResource(title)) }
            item {
                SurfaceCard {
                    entries.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        NavRow(e.icon, stringResource(e.title), e.onClick)
                    }
                }
            }
        }
    }
}

private class MoreEntry(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    @param:androidx.annotation.StringRes val title: Int,
    val onClick: () -> Unit,
)
