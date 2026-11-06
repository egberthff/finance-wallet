package ph.financewallet.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import ph.financewallet.app.R
import ph.financewallet.app.ui.theme.AppIcons

/** Top-level destinations (PRD UX-02). The icon is decorative; the label is always shown. */
enum class TopLevel(
    val route: String,
    @param:StringRes val label: Int,
    val icon: () -> ImageVector,
) {
    HOME("home", R.string.nav_home, { AppIcons.Home }),
    TRANSACTIONS("transactions", R.string.nav_transactions, { AppIcons.List }),
    BUDGETS("budgets", R.string.nav_budgets, { AppIcons.PieChart }),
    ACCOUNTS("accounts", R.string.nav_accounts, { AppIcons.Wallet }),
    MORE("more", R.string.nav_more, { AppIcons.More }),
}
