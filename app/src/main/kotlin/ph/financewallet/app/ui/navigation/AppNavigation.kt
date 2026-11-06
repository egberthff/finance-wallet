package ph.financewallet.app.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.accounts.AccountsScreen
import ph.financewallet.app.ui.accounts.AccountsViewModel
import ph.financewallet.app.ui.advanced.AssetsScreen
import ph.financewallet.app.ui.advanced.AssetsViewModel
import ph.financewallet.app.ui.advanced.BillsScreen
import ph.financewallet.app.ui.advanced.BillsViewModel
import ph.financewallet.app.ui.advanced.DebtsScreen
import ph.financewallet.app.ui.advanced.DebtsViewModel
import ph.financewallet.app.ui.advanced.ReportsScreen
import ph.financewallet.app.ui.advanced.ReportsViewModel
import ph.financewallet.app.ui.backup.BackupScreen
import ph.financewallet.app.ui.backup.BackupViewModel
import ph.financewallet.app.ui.backup.DeleteAllViewModel
import ph.financewallet.app.ui.backup.InsightsScreen
import ph.financewallet.app.ui.backup.InsightsViewModel
import ph.financewallet.app.ui.backup.PrivacyScreen
import ph.financewallet.app.ui.budgets.BudgetsScreen
import ph.financewallet.app.ui.budgets.BudgetsViewModel
import ph.financewallet.app.ui.budgets.GoalsScreen
import ph.financewallet.app.ui.budgets.GoalsViewModel
import ph.financewallet.app.ui.home.CategoriesScreen
import ph.financewallet.app.ui.home.CategoriesViewModel
import ph.financewallet.app.ui.home.HomeScreen
import ph.financewallet.app.ui.home.HomeViewModel
import ph.financewallet.app.ui.home.MoreScreen
import ph.financewallet.app.ui.imports.ImportHistoryScreen
import ph.financewallet.app.ui.imports.ImportHistoryViewModel
import ph.financewallet.app.ui.imports.ImportScreen
import ph.financewallet.app.ui.imports.ImportViewModel
import ph.financewallet.app.ui.imports.RulesScreen
import ph.financewallet.app.ui.imports.RulesViewModel
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.transactions.EntryScreen
import ph.financewallet.app.ui.transactions.EntryViewModel
import ph.financewallet.app.ui.transactions.TransactionsScreen
import ph.financewallet.app.ui.transactions.TransactionsViewModel

private const val ENTRY = "entry"
private const val EDIT = "edit"
private const val GOALS = "goals"
private const val CATEGORIES = "categories"
private const val DEBTS = "debts"
private const val BILLS = "bills"
private const val ASSETS = "assets"
private const val REPORTS = "reports"
private const val IMPORT = "import"
private const val IMPORT_HISTORY = "import-history"
private const val RULES = "rules"
private const val INSIGHTS = "insights"
private const val BACKUP = "backup"
private const val PRIVACY = "privacy"

/** What delete-all-data needs from outside the services (UC-31): the PIN re-check, the wipe and the restart. */
class PrivacyActions(
    val confirmPin: (CharArray) -> Boolean,
    val wipe: suspend () -> Unit,
    val onDeleted: () -> Unit,
    val crashLog: ph.financewallet.app.diagnostics.CrashLog,
)

@Composable
fun AppNavigation(
    services: AppServices,
    privacy: PrivacyActions,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val isTopLevel = current == null || TopLevel.entries.any { it.route == current }
    // The tab that owns the visible screen (e.g. More while Bills is open), so the bar always shows where the user is.
    // Sub-screens are pushed on top of the tab they were opened from, so the last top-level screen shown owns them.
    var owningTab by rememberSaveable { mutableStateOf(TopLevel.HOME.route) }
    LaunchedEffect(current) { if (current != null && TopLevel.entries.any { it.route == current }) owningTab = current }
    Scaffold(
        topBar = { if (!isTopLevel) BackBar(onBack = { nav.popBackStack() }) },
        bottomBar = {
            NavigationBar(tonalElevation = 0.dp) {
                TopLevel.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = owningTab == dest.route,
                        onClick = {
                            // Re-tapping the current tab returns to its first screen; another tab restores its own stack.
                            if (owningTab == dest.route) nav.popBackStack(dest.route, inclusive = false) else nav.goTopLevel(dest.route)
                        },
                        icon = { Icon(dest.icon(), contentDescription = null) },
                        label = { Text(stringResource(dest.label), maxLines = 1) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = TopLevel.HOME.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            topLevelRoutes(nav, services)
            featureRoutes(nav, services)
            dataRoutes(services, privacy)
        }
    }
}

/** Sub-screens show a back arrow; their own heading stays in the content, so the bar has no title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackBar(onBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.action_back)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

private fun NavHostController.goTopLevel(route: String) =
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

/** The five bottom-bar destinations (UX-02). */
private fun NavGraphBuilder.topLevelRoutes(
    nav: NavHostController,
    services: AppServices,
) {
    composable(TopLevel.HOME.route) {
        HomeScreen(
            viewModel { HomeViewModel(services) },
            onAddTransaction = { nav.navigate(ENTRY) },
            onBills = { nav.navigate(BILLS) },
            onBackup = { nav.navigate(BACKUP) },
        )
    }
    composable(TopLevel.TRANSACTIONS.route) {
        TransactionsScreen(
            viewModel { TransactionsViewModel(services) },
            onAdd = { nav.navigate(ENTRY) },
            onEdit = { id -> nav.navigate("$EDIT/$id") },
        )
    }
    composable(TopLevel.BUDGETS.route) { BudgetsScreen(viewModel { BudgetsViewModel(services) }, services::newKey) }
    composable(TopLevel.ACCOUNTS.route) { AccountsScreen(viewModel { AccountsViewModel(services) }, services::newKey) }
    composable(TopLevel.MORE.route) {
        MoreScreen(
            onGoals = { nav.navigate(GOALS) },
            onCategories = { nav.navigate(CATEGORIES) },
            onDebts = { nav.navigate(DEBTS) },
            onBills = { nav.navigate(BILLS) },
            onAssets = { nav.navigate(ASSETS) },
            onReports = { nav.navigate(REPORTS) },
            onImport = { nav.navigate(IMPORT) },
            onImportHistory = { nav.navigate(IMPORT_HISTORY) },
            onRules = { nav.navigate(RULES) },
            onInsights = { nav.navigate(INSIGHTS) },
            onBackup = { nav.navigate(BACKUP) },
            onPrivacy = { nav.navigate(PRIVACY) },
        )
    }
}

/** Screens opened from a tab or from More. */
private fun NavGraphBuilder.featureRoutes(
    nav: NavHostController,
    services: AppServices,
) {
    composable(ENTRY) { EntryScreen(viewModel { EntryViewModel(services) }, onDone = { nav.popBackStack() }) }
    composable("$EDIT/{txnId}") { entry ->
        val id = entry.arguments?.getString("txnId")
        EntryScreen(viewModel { EntryViewModel(services) }, onDone = { nav.popBackStack() }, editTxnId = id)
    }
    composable(GOALS) { GoalsScreen(viewModel { GoalsViewModel(services) }, services::newKey) }
    composable(CATEGORIES) { CategoriesScreen(viewModel { CategoriesViewModel(services) }) }
    composable(DEBTS) { DebtsScreen(viewModel { DebtsViewModel(services) }, services::newKey) }
    composable(BILLS) { BillsScreen(viewModel { BillsViewModel(services) }, services::newKey) }
    composable(ASSETS) { AssetsScreen(viewModel { AssetsViewModel(services) }) }
    composable(REPORTS) { ReportsScreen(viewModel { ReportsViewModel(services) }) }
    composable(IMPORT) { ImportScreen(viewModel { ImportViewModel(services) }) }
    composable(IMPORT_HISTORY) { ImportHistoryScreen(viewModel { ImportHistoryViewModel(services) }) }
    composable(RULES) { RulesScreen(viewModel { RulesViewModel(services) }) }
}

/** Insights, backup and privacy (Phase 7). */
private fun NavGraphBuilder.dataRoutes(
    services: AppServices,
    privacy: PrivacyActions,
) {
    composable(INSIGHTS) { InsightsScreen(viewModel { InsightsViewModel(services) }) }
    composable(BACKUP) { BackupScreen(viewModel { BackupViewModel(services) }) }
    composable(PRIVACY) {
        PrivacyScreen(
            backup = viewModel { BackupViewModel(services) },
            delete = viewModel { DeleteAllViewModel(privacy.confirmPin, privacy.wipe) },
            onDeleted = privacy.onDeleted,
            crashLog = privacy.crashLog,
        )
    }
}
