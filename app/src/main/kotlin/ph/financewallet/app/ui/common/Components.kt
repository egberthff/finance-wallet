package ph.financewallet.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ph.financewallet.app.R
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalFinanceColors
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.app.ui.theme.TABULAR_FIGURES
import ph.financewallet.domain.common.DomainError
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.AmountError
import ph.financewallet.domain.money.AmountParseResult
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.money.MoneyFormatter
import ph.financewallet.domain.money.MoneyParser

/** White (light) / raised (dark) card with a hairline border: the one card style used across the app. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = content)
    }
}

/** Labelled figure in a card. [caption] explains what the number is, so different concepts are never confused (UX dashboard rule). */
@Composable
fun FigureCard(
    label: String,
    money: Money,
    caption: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    SurfaceCard(modifier, onClick = onClick) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (icon !=
                    null
                ) {
                    IconDisc(icon, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer, small = true)
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AmountText(money, emphasize = true)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Page heading at the top of a screen. */
@Composable
fun ScreenTitle(
    text: String,
    subtitle: String? = null,
) {
    val spacing = LocalSpacing.current
    Column(Modifier.padding(top = spacing.md, bottom = spacing.sm).semantics(mergeDescendants = true) { heading() }) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier =
            Modifier
                .padding(top = LocalSpacing.current.md, bottom = LocalSpacing.current.xs)
                .semantics { heading() },
    )
}

private val DISC_SIZE = 40.dp
private val DISC_SIZE_SMALL = 28.dp
private val DISC_ICON = 22.dp
private val DISC_ICON_SMALL = 16.dp

/** Decorative icon on a tinted circle (list leading element). */
@Composable
fun IconDisc(
    icon: ImageVector,
    tint: Color,
    background: Color,
    small: Boolean = false,
) {
    Box(
        Modifier.size(if (small) DISC_SIZE_SMALL else DISC_SIZE).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (small) DISC_ICON_SMALL else DISC_ICON))
    }
}

/** A list line: icon disc, title, optional subtitle, trailing content (usually an amount). */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = spacing.md, vertical = spacing.sm + spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/**
 * Leading icon for a transaction. Moves of the user's own money (transfers, savings, adjustments) are neutral, so they
 * never look like income or spending (TXN transfer rule); otherwise the sign of the amount decides.
 */
@Composable
fun TxnIcon(
    type: TxnType,
    amount: Money,
) {
    val c = LocalFinanceColors.current
    when {
        type in NEUTRAL_TYPES -> IconDisc(AppIcons.Transfer, c.neutral, c.neutralSoft)
        amount.isNegative -> IconDisc(AppIcons.TrendingDown, c.expense, c.expenseSoft)
        else -> IconDisc(AppIcons.TrendingUp, c.income, c.incomeSoft)
    }
}

/** A transaction's amount: neutral colour for moves of the user's own money, sign colour otherwise. */
@Composable
fun TxnAmount(
    type: TxnType,
    amount: Money,
) {
    if (type in NEUTRAL_TYPES) {
        Text(
            amount.display(),
            color = LocalFinanceColors.current.neutral,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
            fontWeight = FontWeight.Medium,
        )
    } else {
        AmountText(amount)
    }
}

private val NEUTRAL_TYPES =
    setOf(
        TxnType.TRANSFER,
        TxnType.SAVINGS_CONTRIBUTION,
        TxnType.SAVINGS_WITHDRAWAL,
        TxnType.BALANCE_ADJUSTMENT,
        TxnType.OPENING_BALANCE,
        TxnType.REVERSAL,
    )

/** The primary "add" action of a list screen. */
@Composable
fun AddFab(
    label: String,
    onClick: () -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(AppIcons.Add, contentDescription = null) },
        text = { Text(label) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
}

/** Menu entry that opens another screen. */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = { IconDisc(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer) },
    ) {
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
