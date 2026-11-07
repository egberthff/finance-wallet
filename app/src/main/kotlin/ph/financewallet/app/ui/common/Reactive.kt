package ph.financewallet.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices

/**
 * Re-runs [reload] whenever one of [tables] changes, for as long as the view model lives (blueprint F). A change made
 * anywhere (entry, import, an auto-posted bill, another screen) then shows on this screen without a manual refresh.
 */
fun ViewModel.reloadOn(
    s: AppServices,
    vararg tables: String,
    reload: suspend () -> Unit,
) {
    viewModelScope.launch { s.changes(*tables).collect { reload() } }
}

/** Table sets that screens watch. Names are the Room table names (data/schemas). */
object Tables {
    val LEDGER = arrayOf("txn", "entry", "ledger_account", "category")
    val ACCOUNTS = LEDGER + arrayOf("financial_account", "reconciliation")
    val BUDGETS = LEDGER + arrayOf("budget", "budget_category", "budget_period", "allocation_group", "allocation_entry", "savings_goal")
    val HOME = BUDGETS + arrayOf("occurrence", "recurring_rule", "asset_valuation", "goal_account")
    val RECURRING = LEDGER + arrayOf("recurring_rule", "occurrence")
    val CATEGORIES = arrayOf("category", "ledger_account")
}
