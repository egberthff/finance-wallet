package ph.financewallet.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ph.financewallet.app.R
import ph.financewallet.app.ui.components.EmptyState

/** Top-level destinations (PRD UX-02). Feature screens replace the empty states from Phase 4 on. */
enum class TopLevel(val route: String, @param:StringRes val label: Int) {
    HOME("home", R.string.nav_home),
    TRANSACTIONS("transactions", R.string.nav_transactions),
    BUDGETS("budgets", R.string.nav_budgets),
    ACCOUNTS("accounts", R.string.nav_accounts),
    MORE("more", R.string.nav_more),
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest.route,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // Icons are added with the visual design pass; labels alone keep the bar accessible meanwhile.
                        icon = {},
                        label = { Text(stringResource(dest.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = TopLevel.HOME.route, modifier = Modifier.padding(padding)) {
            TopLevel.entries.forEach { dest ->
                composable(dest.route) { SectionNotBuilt(stringResource(dest.label)) }
            }
        }
    }
}

@Composable
private fun SectionNotBuilt(name: String) {
    Box {
        EmptyState(
            title = stringResource(R.string.section_not_built_title, name),
            message = stringResource(R.string.section_not_built_message),
        )
    }
}
