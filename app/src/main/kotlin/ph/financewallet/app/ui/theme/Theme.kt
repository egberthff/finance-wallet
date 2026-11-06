package ph.financewallet.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Design tokens (PRD UX-01). Brand palette is fixed (dynamic color off) so financial meaning stays
 * consistent: positive/negative colors are never taken from wallpaper-derived palettes.
 * Every Material role is defined (no library defaults leak in). Text/background pairs meet WCAG AA (UX-04).
 */

private val Navy900 = Color(0xFF0B1F3A)
private val Navy700 = Color(0xFF16365F)
private val Navy100 = Color(0xFFD9E4F7)
private val Teal700 = Color(0xFF00695F)
private val Teal100 = Color(0xFFCDEFEA)
private val Teal200 = Color(0xFF6FD6C8)
private val Slate50 = Color(0xFFF3F5F9)
private val Slate100 = Color(0xFFE6EBF2)
private val Slate200 = Color(0xFFDDE3EC)
private val Slate600 = Color(0xFF4A5565)
private val Slate900 = Color(0xFF111821)
private val Ink950 = Color(0xFF0D1319)

/** Semantic financial colors used for amounts. Never the only signal: amounts also carry a sign. */
@Immutable
data class FinanceColors(
    val income: Color,
    val expense: Color,
    val neutral: Color,
    val warning: Color,
    /** Tinted backgrounds behind income/expense/transfer icons in lists. */
    val incomeSoft: Color,
    val expenseSoft: Color,
    val neutralSoft: Color,
    /** The summary ("hero") card at the top of Home: a two-stop gradient with high-contrast text. */
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroMuted: Color,
)

private val LightFinance =
    FinanceColors(
        income = Color(0xFF1B7F3A),
        expense = Color(0xFFB3261E),
        neutral = Slate600,
        warning = Color(0xFF8A5A00),
        incomeSoft = Color(0xFFDDF3E3),
        expenseSoft = Color(0xFFFBE3E1),
        neutralSoft = Slate100,
        heroStart = Navy700,
        heroEnd = Color(0xFF0B5563),
        onHero = Color.White,
        onHeroMuted = Color(0xFFD3E0EE),
    )
private val DarkFinance =
    FinanceColors(
        income = Color(0xFF7DDB98),
        expense = Color(0xFFFFB4AB),
        neutral = Color(0xFFC3CCD8),
        warning = Color(0xFFF5C26B),
        incomeSoft = Color(0xFF173A24),
        expenseSoft = Color(0xFF452220),
        neutralSoft = Color(0xFF2A3442),
        heroStart = Color(0xFF1E3A63),
        heroEnd = Color(0xFF0F4F48),
        onHero = Color.White,
        onHeroMuted = Color(0xFFC9D6E6),
    )

private val LightScheme: ColorScheme =
    lightColorScheme(
        primary = Navy700,
        onPrimary = Color.White,
        primaryContainer = Navy100,
        onPrimaryContainer = Navy900,
        secondary = Teal700,
        onSecondary = Color.White,
        secondaryContainer = Teal100,
        onSecondaryContainer = Color(0xFF00201C),
        tertiary = Color(0xFF8A5A00),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFE2B8),
        onTertiaryContainer = Color(0xFF2B1700),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = Slate50,
        onBackground = Slate900,
        surface = Slate50,
        onSurface = Slate900,
        surfaceVariant = Slate100,
        onSurfaceVariant = Slate600,
        surfaceTint = Navy700,
        outline = Color(0xFF7C8796),
        outlineVariant = Slate200,
        surfaceBright = Color.White,
        surfaceDim = Slate100,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color.White,
        inverseSurface = Color(0xFF263040),
        inverseOnSurface = Color(0xFFEDF1F7),
        inversePrimary = Color(0xFFA9C7FF),
    )

private val DarkScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFA9C7FF),
        onPrimary = Navy900,
        primaryContainer = Color(0xFF1E3A63),
        onPrimaryContainer = Navy100,
        secondary = Teal200,
        onSecondary = Color(0xFF003731),
        secondaryContainer = Color(0xFF0F4F48),
        onSecondaryContainer = Color(0xFFB8F0E7),
        tertiary = Color(0xFFF5C26B),
        onTertiary = Color(0xFF452B00),
        tertiaryContainer = Color(0xFF5A3D00),
        onTertiaryContainer = Color(0xFFFFE2B8),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        background = Ink950,
        onBackground = Color(0xFFE3E8EF),
        surface = Ink950,
        onSurface = Color(0xFFE3E8EF),
        surfaceVariant = Color(0xFF2A3442),
        onSurfaceVariant = Color(0xFFC3CCD8),
        surfaceTint = Color(0xFFA9C7FF),
        outline = Color(0xFF8D97A5),
        outlineVariant = Color(0xFF333E4C),
        surfaceBright = Color(0xFF2A3442),
        surfaceDim = Ink950,
        surfaceContainerLowest = Color(0xFF090D12),
        surfaceContainerLow = Color(0xFF151C25),
        surfaceContainer = Color(0xFF18202A),
        surfaceContainerHigh = Color(0xFF1D2631),
        surfaceContainerHighest = Color(0xFF1D2631),
        inverseSurface = Color(0xFFE3E8EF),
        inverseOnSurface = Color(0xFF263040),
        inversePrimary = Navy700,
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

/** Tabular figures so amounts line up in columns and do not jitter while typing. */
const val TABULAR_FIGURES = "tnum"

private val BaseTypography = Typography()

private val AppTypography =
    BaseTypography.copy(
        displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_FIGURES),
        headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_FIGURES),
        headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )

private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
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
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
