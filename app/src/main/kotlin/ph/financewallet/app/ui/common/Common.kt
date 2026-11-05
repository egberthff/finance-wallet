package ph.financewallet.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import ph.financewallet.app.R
import ph.financewallet.app.ui.theme.LocalFinanceColors
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.domain.common.DomainError
import ph.financewallet.domain.common.DomainResult
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
        style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/** Labelled figure in a card. [caption] explains what the number is, so different concepts are never confused (UX dashboard rule). */
@Composable
fun FigureCard(
    label: String,
    money: Money,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            AmountText(money, emphasize = true)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = LocalSpacing.current.md, bottom = LocalSpacing.current.xs),
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

/** Horizontal single-choice chips. */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        items(options) { option ->
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

@Composable
fun ErrorLine(
    @StringRes message: Int?,
) {
    if (message != null) Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
