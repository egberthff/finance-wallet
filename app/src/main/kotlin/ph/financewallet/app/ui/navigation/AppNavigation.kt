package ph.financewallet.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ph.financewallet.app.AppServices
import ph.financewallet.app.ui.accounts.AccountsScreen
import ph.financewallet.app.ui.accounts.AccountsViewModel
import ph.financewallet.app.ui.budgets.BudgetsScreen
import ph.financewallet.app.ui.budgets.BudgetsViewModel
import ph.financewallet.app.ui.budgets.GoalsScreen
import ph.financewallet.app.ui.budgets.GoalsViewModel
import ph.financewallet.app.ui.home.CategoriesScreen
import ph.financewallet.app.ui.home.CategoriesViewModel
import ph.financewallet.app.ui.home.HomeScreen
import ph.financewallet.app.ui.home.HomeViewModel
import ph.financewallet.app.ui.home.MoreScreen
import ph.financewallet.app.ui.transactions.EntryScreen
import ph.financewallet.app.ui.transactions.EntryViewModel
import ph.financewallet.app.ui.transactions.TransactionsScreen
import ph.financewallet.app.ui.transactions.TransactionsViewModel

private const val ENTRY = "entry"
private const val GOALS = "goals"
private const val CATEGORIES = "categories"

@Composable
fun AppNavigation(services: AppServices) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest.route,
                        onClick = { nav.goTopLevel(dest.route) },
                        // Icons arrive with the visual design pass; text labels keep the bar accessible meanwhile.
                        icon = {},
                        label = { Text(stringResource(dest.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = TopLevel.HOME.route, modifier = Modifier.padding(padding)) {
            composable(TopLevel.HOME.route) {
                HomeScreen(viewModel { HomeViewModel(services) }, onAddTransaction = { nav.navigate(ENTRY) })
            }
            composable(TopLevel.TRANSACTIONS.route) {
                TransactionsScreen(viewModel { TransactionsViewModel(services) }, onAdd = { nav.navigate(ENTRY) })
            }
            composable(TopLevel.BUDGETS.route) { BudgetsScreen(viewModel { BudgetsViewModel(services) }, services::newKey) }
            composable(TopLevel.ACCOUNTS.route) { AccountsScreen(viewModel { AccountsViewModel(services) }, services::newKey) }
            composable(TopLevel.MORE.route) {
                MoreScreen(onGoals = { nav.navigate(GOALS) }, onCategories = { nav.navigate(CATEGORIES) })
            }
            composable(ENTRY) { EntryScreen(viewModel { EntryViewModel(services) }, onDone = { nav.popBackStack() }) }
            composable(GOALS) { GoalsScreen(viewModel { GoalsViewModel(services) }, services::newKey) }
            composable(CATEGORIES) { CategoriesScreen(viewModel { CategoriesViewModel(services) }) }
        }
    }
}

private fun NavHostController.goTopLevel(route: String) =
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
