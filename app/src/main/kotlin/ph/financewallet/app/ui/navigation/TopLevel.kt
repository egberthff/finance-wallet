package ph.financewallet.app.ui.navigation

import androidx.annotation.StringRes
import ph.financewallet.app.R

/** Top-level destinations (PRD UX-02). Feature screens replace the empty states from Phase 4 on. */
enum class TopLevel(
    val route: String,
    @param:StringRes val label: Int,
) {
    HOME("home", R.string.nav_home),
    TRANSACTIONS("transactions", R.string.nav_transactions),
    BUDGETS("budgets", R.string.nav_budgets),
    ACCOUNTS("accounts", R.string.nav_accounts),
    MORE("more", R.string.nav_more),
}
