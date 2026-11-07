package ph.financewallet.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import ph.financewallet.app.R

/** A category icon the user can pick: the key stored in `category.icon`, its glyph, and a spoken name. */
data class CategoryIconChoice(
    val key: String,
    val icon: () -> ImageVector,
    @StringRes val label: Int,
)

/** A category colour the user can pick: the ARGB value stored in `category.color_argb` and a spoken name. */
data class CategoryColorChoice(
    val argb: Long,
    @StringRes val label: Int,
)

/**
 * Maps the icon keys stored on categories (the PH seed's keys plus the default) to the app's own glyphs, and holds the
 * fixed colour palette. Unknown keys (from a newer version or a restored backup) fall back to [AppIcons.Label], so a
 * category always renders.
 */
object CategoryIcons {
    val ICONS: List<CategoryIconChoice> =
        listOf(
            CategoryIconChoice("category", { AppIcons.Category }, R.string.cat_icon_category),
            CategoryIconChoice("restaurant", { AppIcons.Restaurant }, R.string.cat_icon_restaurant),
            CategoryIconChoice("commute", { AppIcons.Car }, R.string.cat_icon_commute),
            CategoryIconChoice("receipt", { AppIcons.Receipt }, R.string.cat_icon_receipt),
            CategoryIconChoice("home", { AppIcons.Home }, R.string.cat_icon_home),
            CategoryIconChoice("health", { AppIcons.Hospital }, R.string.cat_icon_health),
            CategoryIconChoice("account_balance", { AppIcons.Bank }, R.string.cat_icon_account_balance),
            CategoryIconChoice("family", { AppIcons.People }, R.string.cat_icon_family),
            CategoryIconChoice("school", { AppIcons.School }, R.string.cat_icon_school),
            CategoryIconChoice("shopping", { AppIcons.Cart }, R.string.cat_icon_shopping),
            CategoryIconChoice("spa", { AppIcons.Heart }, R.string.cat_icon_spa),
            CategoryIconChoice("movie", { AppIcons.Movie }, R.string.cat_icon_movie),
            CategoryIconChoice("gift", { AppIcons.Gift }, R.string.cat_icon_gift),
            CategoryIconChoice("fee", { AppIcons.CreditCard }, R.string.cat_icon_fee),
            CategoryIconChoice("work", { AppIcons.Work }, R.string.cat_icon_work),
            CategoryIconChoice("laptop", { AppIcons.Laptop }, R.string.cat_icon_laptop),
            CategoryIconChoice("store", { AppIcons.Store }, R.string.cat_icon_store),
            CategoryIconChoice("savings", { AppIcons.Wallet }, R.string.cat_icon_savings),
            CategoryIconChoice("add", { AppIcons.Add }, R.string.cat_icon_add),
        )

    private val byKey = ICONS.associateBy { it.key }

    /** The glyph for a stored key; [AppIcons.Label] when the key is unknown. */
    fun icon(key: String): ImageVector = byKey[key]?.icon?.invoke() ?: AppIcons.Label

    /**
     * Mid-tone colours (Material 400–900 shades) chosen so the glyph keeps at least 3:1 contrast against the card
     * surface in both themes (`surfaceContainerLow`: white in light, #151C25 in dark); `CategoryIconsTest` checks this.
     * The first entry equals `CategoryService.DEFAULT_COLOR`.
     */
    val PALETTE: List<CategoryColorChoice> =
        listOf(
            CategoryColorChoice(0xFF607D8BL, R.string.cat_color_slate),
            CategoryColorChoice(0xFFE53935L, R.string.cat_color_red),
            CategoryColorChoice(0xFFD81B60L, R.string.cat_color_pink),
            CategoryColorChoice(0xFFAB47BCL, R.string.cat_color_purple),
            CategoryColorChoice(0xFF7E57C2L, R.string.cat_color_violet),
            CategoryColorChoice(0xFF5C6BC0L, R.string.cat_color_indigo),
            CategoryColorChoice(0xFF1E88E5L, R.string.cat_color_blue),
            CategoryColorChoice(0xFF0097A7L, R.string.cat_color_cyan),
            CategoryColorChoice(0xFF00897BL, R.string.cat_color_teal),
            CategoryColorChoice(0xFF388E3CL, R.string.cat_color_green),
            CategoryColorChoice(0xFFE65100L, R.string.cat_color_orange),
            CategoryColorChoice(0xFF8D6E63L, R.string.cat_color_brown),
        )
}
