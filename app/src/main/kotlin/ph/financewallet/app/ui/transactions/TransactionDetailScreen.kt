package ph.financewallet.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.categories.CategoryIcon
import ph.financewallet.app.ui.common.AmountField
import ph.financewallet.app.ui.common.AmountText
import ph.financewallet.app.ui.common.CardList
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FieldLabel
import ph.financewallet.app.ui.common.LabeledRow
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.common.TxnAmount
import ph.financewallet.app.ui.common.display
import ph.financewallet.app.ui.common.displayWithWeekday
import ph.financewallet.app.ui.components.LoadingState
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.ledger.DetailLine
import ph.financewallet.data.ledger.TxnDetails
import ph.financewallet.domain.ledger.TxnStatus
import ph.financewallet.domain.money.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Transaction detail (blueprint G). Tapping a row of the list opens this; Delete is never the default action. */
@Composable
fun TransactionDetailScreen(
    vm: TransactionDetailViewModel,
    onEdit: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var refunding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }
    if (state.loading) {
        LoadingState(stringResource(R.string.loading))
        return
    }
    val d = state.details
    ListScreen {
        if (d == null) {
            item { Text(stringResource(R.string.err_not_found)) }
            return@ListScreen
        }
        item { HeaderCard(d) }
        item { LinesCard(d, state) }
        item { FieldsCard(d) }
        if (hasLinks(d)) {
            item { LinksCard(d, onOpen) }
        }
        if (d.revisions.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.detail_history)) }
            item {
                CardList(d.revisions) { r ->
                    ListRow(
                        stringResource(R.string.detail_revision, r.revisionNo, stringResource(changeKindLabel(r.changeKind))),
                        subtitle = listOfNotNull(timestamp(r.createdAt), r.reason).joinToString(" · "),
                    )
                }
            }
        }
        if (!d.deleted) item { Actions(d, onEdit = { onEdit(d.id) }, onRefund = { refunding = true }, onDelete = { deleting = true }) }
    }
    if (refunding && d != null) RefundDialog(vm, d) { refunding = false }
    if (deleting) {
        DeleteDialog(vm, onDismiss = { deleting = false }, onDeleted = {
            deleting = false
            onDeleted()
        })
    }
}

@Composable
private fun HeaderCard(d: TxnDetails) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(stringResource(typeLabel(d.type)), style = MaterialTheme.typography.labelLarge)
            focusAmount(d)?.let { TxnAmount(d.type, it) }
            Text(d.txnDate.displayWithWeekday(), style = MaterialTheme.typography.bodyMedium)
            if (d.status == TxnStatus.PENDING) Text(stringResource(R.string.txn_pending), color = MaterialTheme.colorScheme.tertiary)
            if (d.deleted) Text(stringResource(R.string.detail_deleted), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** True when the transaction is linked to others: a refund of, a reversal of or by, or refunds against it. */
private fun hasLinks(d: TxnDetails): Boolean =
    listOfNotNull(d.refundOfTxnId, d.reversesTxnId, d.reversedByTxnId).isNotEmpty() || d.refunds.isNotEmpty()

/** The amount from the account side: what left (negative) or arrived (positive); for a move, the amount moved. */
private fun focusAmount(d: TxnDetails): Money? {
    val real = d.lines.filter { it.accountClass == "ASSET" || it.accountClass == "LIABILITY" }
    return when {
        real.size == 1 -> real.single().amount
        real.size > 1 -> real.firstOrNull { it.amount.isPositive }?.amount
        else -> null
    }
}

@Composable
private fun LinesCard(
    d: TxnDetails,
    state: TransactionDetailState,
) {
    SectionTitle(stringResource(R.string.detail_lines))
    CardList(d.lines.filter { it.systemKey == null || it.accountClass == "EXPENSE" || it.accountClass == "INCOME" }) { line ->
        val category = state.categories[line.ledgerAccountId]
        ListRow(
            lineName(line, state),
            subtitle = stringResource(lineKind(line)),
            leading = category?.let { { CategoryIcon(it, small = true) } },
        ) { LineAmount(line) }
    }
}

@Composable
private fun lineName(
    line: DetailLine,
    state: TransactionDetailState,
): String {
    val category = state.categories[line.ledgerAccountId]
    val parent = category?.parentId?.let { state.categories[it]?.name }
    val name = parent?.let { "$it › ${line.name}" } ?: line.name
    return if (line.archived) stringResource(R.string.picker_archived_suffix, name) else name
}

private fun lineKind(line: DetailLine): Int =
    when (line.accountClass) {
        "EXPENSE" -> R.string.detail_line_expense
        "INCOME" -> R.string.detail_line_income
        "LIABILITY" -> R.string.detail_line_liability
        "ASSET" -> R.string.detail_line_account
        else -> R.string.detail_line_other
    }

@Composable
private fun FieldsCard(d: TxnDetails) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Field(R.string.field_description, d.description.ifBlank { null })
            Field(R.string.field_merchant, d.merchant)
            Field(R.string.field_reference, d.referenceNo)
            Field(R.string.field_notes, d.notes)
            Field(R.string.detail_posting_date, d.postingDate?.display())
            Field(R.string.detail_source, stringResource(sourceLabel(d.source)))
            Field(R.string.detail_created, timestamp(d.createdAt))
            Field(R.string.detail_modified, timestamp(d.updatedAt))
        }
    }
}

@Composable
private fun Field(
    label: Int,
    value: String?,
) {
    if (value.isNullOrBlank()) return
    LabeledRow(stringResource(label)) { Text(value, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun LinksCard(
    d: TxnDetails,
    onOpen: (String) -> Unit,
) {
    SectionTitle(stringResource(R.string.detail_links))
    SurfaceCard {
        d.refundOfTxnId?.let { ListRow(stringResource(R.string.detail_refund_of), onClick = { onOpen(it) }) }
        d.reversesTxnId?.let { ListRow(stringResource(R.string.detail_reverses), onClick = { onOpen(it) }) }
        d.reversedByTxnId?.let { ListRow(stringResource(R.string.detail_reversed_by), onClick = { onOpen(it) }) }
        d.refunds.forEach { r ->
            ListRow(stringResource(R.string.detail_refund_row, r.date.display()), onClick = { onOpen(r.id) }) { AmountText(r.amount) }
        }
    }
}

@Composable
private fun Actions(
    d: TxnDetails,
    onEdit: () -> Unit,
    onRefund: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(Modifier.fillMaxWidth().padding(top = spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_edit)) }
        val refundable = d.refundable
        if (refundable != null && refundable.isPositive) {
            OutlinedButton(onClick = onRefund, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.detail_record_refund)) }
            Text(
                stringResource(R.string.detail_refundable, refundable.display()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RefundDialog(
    vm: TransactionDetailViewModel,
    d: TxnDetails,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    var amount by remember { mutableStateOf(vm.refundDefault()) }
    var account by remember { mutableStateOf(vm.refundAccountDefault()) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_record_refund)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                Text(stringResource(R.string.detail_refund_explain), style = MaterialTheme.typography.bodySmall)
                d.refundable?.let {
                    Text(
                        stringResource(R.string.detail_refundable, it.display()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AmountField(amount, { amount = it }, stringResource(R.string.field_amount), null)
                FieldLabel(stringResource(R.string.detail_refund_account))
                ChoiceChips(state.refundAccounts, state.refundAccounts.firstOrNull { it.id == account }, { it.label }, { account = it.id })
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.recordRefund(amount, account, vm.today())
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** States that the history is kept (TXN-07), so delete is never mistaken for erasing. */
@Composable
private fun DeleteDialog(
    vm: TransactionDetailViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_delete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.txn_delete_message))
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = vm.delete()
                    if (error == null) onDeleted()
                }
            }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun sourceLabel(source: String): Int =
    when (source) {
        "MANUAL" -> R.string.detail_source_manual
        "IMPORT" -> R.string.detail_source_import
        "SYSTEM" -> R.string.detail_source_system
        else -> R.string.detail_source_other
    }

private fun changeKindLabel(kind: String): Int =
    when (kind) {
        "CREATE" -> R.string.detail_change_create
        "UPDATE" -> R.string.detail_change_update
        "DELETE" -> R.string.detail_change_delete
        "RESTORE" -> R.string.detail_change_restore
        "IMPORT_ROLLBACK" -> R.string.detail_change_rollback
        else -> R.string.detail_change_other
    }

private val MANILA: ZoneId = ZoneId.of("Asia/Manila")
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

/** A stored instant as Manila local time, e.g. "7 Nov 2026, 10:32 AM". */
fun timestamp(millis: Long): String = STAMP.format(Instant.ofEpochMilli(millis).atZone(MANILA))

/**
 * A line's amount. Account lines are signed and coloured (money left or arrived). Category lines show the plain amount
 * in a neutral colour: in the ledger a spending category is debited (positive), and colouring that green would read as
 * money coming in.
 */
@Composable
private fun LineAmount(line: DetailLine) {
    if (line.accountClass == "EXPENSE" || line.accountClass == "INCOME") {
        val plain = if (line.amount.isNegative) -line.amount else line.amount
        Text(
            plain.display(),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = ph.financewallet.app.ui.theme.TABULAR_FIGURES),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        AmountText(line.amount)
    }
}
