package ph.financewallet.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/** Display text for an amount (UX-06): ₱1,234.56 and −₱1,234.56. */
fun Money.display(): String = MoneyFormatter.format(this)

/** Amount coloured by sign. The sign is always in the text too, so colour is never the only signal (UX-04). */
@Composable
fun AmountText(
    money: Money,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    val colors = LocalFinanceColors.current
    val color =
        when {
            money.isNegative -> colors.expense
            money.isPositive -> colors.income
            else -> colors.neutral
        }
    Text(
        money.display(),
        modifier = modifier,
        color = color,
        style =
            (if (emphasize) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge)
                .copy(fontFeatureSettings = TABULAR_FIGURES),
        fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
    )
}

/** Amount input: decimal keyboard, parse on the fly, error text under the field. */
@Composable
fun AmountField(
    text: String,
    onText: (String) -> Unit,
    label: String,
    error: String?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Single-choice chips that wrap onto new lines, so every option and the selection stay visible (C-12, J). */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun LabeledRow(
    label: String,
    value: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        value()
    }
}

/** Small label above a group of choices in a form (account, category). */
@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LocalSpacing.current.xs),
    )
}

@Composable
fun ErrorLine(
    @StringRes message: Int?,
) {
    if (message != null) Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/**
 * Optional single choice among a possibly long list (a category's parent): chips wrap onto new lines, so every option
 * and the selection stay visible (C-12). Tapping the selected chip clears it.
 */
@Composable
fun <T> ParentPicker(
    options: List<T>,
    selected: T?,
    label: (T) -> String = { it.toString() },
    onSelect: (T?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = {
                onSelect(
                    if (option ==
                        selected
                    ) {
                        null
                    } else {
                        option
                    },
                )
            }, label = { Text(label(option)) })
        }
    }
}
