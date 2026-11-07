package ph.financewallet.app.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.StatusPill
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.dateRange
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.budget.BudgetHeader

/** What state a budget is in, shown as a label on its card so open and closed budgets are never confused. */
enum class BudgetStatus { OPEN, PERIOD_CLOSED, ARCHIVED }

/** The coloured status label: Open (with days left), Period closed, or Archived. The word carries the meaning. */
@Composable
fun BudgetStatusLabel(
    status: BudgetStatus,
    daysLeft: Int? = null,
) {
    val c = MaterialTheme.colorScheme
    when (status) {
        BudgetStatus.OPEN -> {
            val text =
                if (daysLeft != null) {
                    androidx.compose.ui.res
                        .pluralStringResource(R.plurals.budget_open_days_left, daysLeft, daysLeft)
                } else {
                    stringResource(R.string.budget_status_open)
                }
            StatusPill(text, c.secondaryContainer, c.onSecondaryContainer)
        }

        BudgetStatus.PERIOD_CLOSED -> {
            StatusPill(stringResource(R.string.budget_status_period_closed), c.tertiaryContainer, c.onTertiaryContainer)
        }

        BudgetStatus.ARCHIVED -> {
            StatusPill(stringResource(R.string.budget_status_archived), c.surfaceVariant, c.onSurfaceVariant)
        }
    }
}

/** A budget whose current period was closed early: its dates, the label, Reopen and Edit. */
@Composable
fun ClosedPeriodCard(
    card: BudgetCard,
    onReopen: () -> Unit,
    onEdit: () -> Unit,
) {
    val period = card.current?.period
    ClosedCard(
        name = card.name,
        detail = period?.let { dateRange(it.start, it.endInclusive) },
        explain = stringResource(R.string.budget_period_closed_explain),
        status = BudgetStatus.PERIOD_CLOSED,
    ) {
        OutlinedButton(onClick = onReopen) { Text(stringResource(R.string.budget_reopen)) }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.budget_edit)) }
    }
}

/** An archived budget: the label and Restore. */
@Composable
fun ArchivedBudgetCard(
    budget: BudgetHeader,
    onRestore: () -> Unit,
) {
    ClosedCard(
        name = budget.name,
        detail = null,
        explain = stringResource(R.string.budget_archived_explain),
        status = BudgetStatus.ARCHIVED,
    ) {
        OutlinedButton(onClick = onRestore) { Text(stringResource(R.string.budget_restore)) }
    }
}

@Composable
private fun ClosedCard(
    name: String,
    detail: String?,
    explain: String,
    status: BudgetStatus,
    actions: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    if (detail != null) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BudgetStatusLabel(status)
            }
            Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

/** "Close period" asks first, and says it can be undone, so a mis-tap is never silent. */
@Composable
fun CloseBudgetPeriodDialog(
    name: String,
    onClose: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.budget_close_title, name)) },
        text = { Text(stringResource(R.string.budget_close_message)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.budget_close_period)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Reopen explains that the money closing moved goes back where it was. */
@Composable
fun ReopenBudgetPeriodDialog(
    name: String,
    onReopen: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.budget_reopen_title, name)) },
        text = { Text(stringResource(R.string.budget_reopen_message)) },
        confirmButton = { TextButton(onClick = onReopen) { Text(stringResource(R.string.budget_reopen)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
