package ph.financewallet.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Design tokens (PRD UX-01). Brand palette is fixed (dynamic color off) so financial meaning stays
 * consistent: positive/negative colors are never taken from wallpaper-derived palettes.
 * Contrast targets WCAG AA (UX-04); verified with an accessibility scanner in Phase 8.
 */

private val Navy900 = Color(0xFF0B1F3A)
private val Navy700 = Color(0xFF16365F)
private val Teal600 = Color(0xFF00897B)
private val Teal200 = Color(0xFF6FD6C8)
private val Slate50 = Color(0xFFF6F8FB)
private val Slate100 = Color(0xFFE9EEF5)
private val Slate800 = Color(0xFF1C2532)
private val Slate900 = Color(0xFF111821)

/** Semantic financial colors used for amounts. Never the only signal: amounts also carry a sign. */
@Immutable
data class FinanceColors(
    val income: Color,
    val expense: Color,
    val neutral: Color,
    val warning: Color,
)

private val LightFinance =
    FinanceColors(
        income = Color(0xFF1B7F3A),
        expense = Color(0xFFB3261E),
        neutral = Color(0xFF44505F),
        warning = Color(0xFF8A5A00),
    )
private val DarkFinance =
    FinanceColors(
        income = Color(0xFF7DDB98),
        expense = Color(0xFFFFB4AB),
        neutral = Color(0xFFC3CCD8),
        warning = Color(0xFFF5C26B),
    )

private val LightScheme: ColorScheme =
    lightColorScheme(
        primary = Navy700,
        onPrimary = Color.White,
        secondary = Teal600,
        onSecondary = Color.White,
        background = Slate50,
        onBackground = Slate900,
        surface = Color.White,
        onSurface = Slate900,
        surfaceVariant = Slate100,
        onSurfaceVariant = Color(0xFF44505F),
    )

private val DarkScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFA9C7FF),
        onPrimary = Navy900,
        secondary = Teal200,
        onSecondary = Color(0xFF003731),
        background = Slate900,
        onBackground = Color(0xFFE3E8EF),
        surface = Slate800,
        onSurface = Color(0xFFE3E8EF),
        surfaceVariant = Color(0xFF2A3442),
        onSurfaceVariant = Color(0xFFC3CCD8),
    )

/** Spacing scale (4-dp grid). */
@Immutable
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
)

val LocalFinanceColors = staticCompositionLocalOf { LightFinance }
val LocalSpacing = staticCompositionLocalOf { Spacing() }

@Composable
fun FinanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalFinanceColors provides if (darkTheme) DarkFinance else LightFinance,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, content = content)
    }
}
