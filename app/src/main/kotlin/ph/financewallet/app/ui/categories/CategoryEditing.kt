package ph.financewallet.app.ui.categories

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FieldLabel
import ph.financewallet.app.ui.common.IconDisc
import ph.financewallet.app.ui.theme.CategoryIcons
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.account.CategoryUsage
import ph.financewallet.data.ledger.TransactionListItem

/** A category's stored icon on a disc of its stored colour (CAT-01: icon and colour are the user's choice). */
@Composable
fun CategoryIcon(
    row: CategoryRow,
    small: Boolean = false,
) {
    val color = Color(row.colorArgb)
    IconDisc(CategoryIcons.icon(row.icon), color, color.copy(alpha = SOFT_ALPHA), small)
}

/** Errors shown under the name field rather than at the bottom of the dialog. */
private val NAME_ERRORS = setOf(R.string.err_name_length, R.string.err_category_name_taken)

/**
 * Rename a category and pick its icon and colour (UC-14 edit). Archive lives here too, behind a tap on the row and a
 * confirmation that shows what uses the category, so it is never a one-tap action in the list. While the category
 * has live subcategories, Archive is disabled with the reason shown. Callbacks return an error resource or null.
 */
@Composable
fun EditCategoryDialog(
    row: CategoryRow,
    liveChildren: Int,
    loadUsage: suspend () -> CategoryUsage,
    onSave: suspend (name: String, icon: String, colorArgb: Long) -> Int?,
    onArchive: suspend () -> Int?,
    onDismiss: () -> Unit,
    onShowTransactions: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val spacing = LocalSpacing.current
    var name by remember { mutableStateOf(row.name) }
    var icon by remember { mutableStateOf(row.icon) }
    var color by remember { mutableStateOf(row.colorArgb) }
    var error by remember { mutableStateOf<Int?>(null) }
    var usage by remember { mutableStateOf<CategoryUsage?>(null) }
    val nameError = error?.takeIf { it in NAME_ERRORS }
    usage?.let { u ->
        val unsaved = name != row.name || icon != row.icon || color != row.colorArgb
        ArchiveConfirmDialog(row.name, u, unsaved, onConfirm = {
            scope.launch {
                error = onArchive()
                usage = null
                if (error == null) onDismiss()
            }
        }, onCancel = { usage = null })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.categories_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                NameField(name, nameError) {
                    name = it
                    if (error in NAME_ERRORS) error = null
                }
                StylePicker(icon, color, onIcon = { icon = it }, onColor = { color = it })
                if (error != null && nameError == null) ErrorLine(error)
                onShowTransactions?.let { TextButton(onClick = it) { Text(stringResource(R.string.accounts_view_transactions)) } }
                ArchiveAction(liveChildren) { scope.launch { usage = loadUsage() } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    error = onSave(name, icon, color)
                    if (error == null) onDismiss()
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The name field with its own error (empty, too long, or already used here) shown right under it. */
@Composable
private fun NameField(
    name: String,
    error: Int?,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        name,
        onChange,
        label = { Text(stringResource(R.string.field_name)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Archive, disabled with the reason while live subcategories exist (they must be archived first). */
@Composable
private fun ArchiveAction(
    liveChildren: Int,
    onArchive: () -> Unit,
) {
    val allowed = liveChildren == 0
    TextButton(onClick = onArchive, enabled = allowed) {
        Text(stringResource(R.string.action_archive), color = if (allowed) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
    if (!allowed) {
        Text(
            stringResource(R.string.err_category_has_children),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Archive 'Food'?" with what uses it: past transactions keep it; budgets, bills and import rules that use it need a
 * new category (C-6). Also says when edits made in the dialog will be lost.
 */
@Composable
private fun ArchiveConfirmDialog(
    name: String,
    usage: CategoryUsage,
    unsavedChanges: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.categories_archive_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                Text(stringResource(R.string.categories_archive_hint))
                if (usage.transactions > 0) {
                    Text(pluralStringResource(R.plurals.categories_usage_transactions, usage.transactions, usage.transactions))
                }
                if (usage.budgets + usage.recurringRules + usage.categoryRules > 0) {
                    Text(
                        stringResource(R.string.categories_usage_links, usage.budgets, usage.recurringRules, usage.categoryRules),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (unsavedChanges) Text(stringResource(R.string.categories_archive_unsaved), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_archive), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The category style for a transaction row: only when the row is titled by its single category (no description or
 * merchant), so the icon always matches the title. Otherwise null, and the row keeps its money-direction icon.
 */
fun categoryStyleFor(
    item: TransactionListItem,
    styles: Map<String, CategoryRow>,
): CategoryRow? {
    val titledByCategory = item.description.isBlank() && item.merchant.isNullOrBlank()
    return if (titledByCategory) item.categoryIds.singleOrNull()?.let { styles[it] } else null
}

/** Icon grid (drawn in the chosen colour, so the result is previewed) and colour swatches. */
@Composable
internal fun StylePicker(
    icon: String,
    color: Long,
    onIcon: (String) -> Unit,
    onColor: (Long) -> Unit,
) {
    val gap = Arrangement.spacedBy(LocalSpacing.current.xs)
    FieldLabel(stringResource(R.string.categories_icon))
    FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
        CategoryIcons.ICONS.forEach { choice ->
            Choice(choice.label, selected = icon == choice.key, onClick = { onIcon(choice.key) }) {
                Icon(choice.icon(), contentDescription = null, tint = Color(color))
            }
        }
    }
    FieldLabel(stringResource(R.string.categories_color))
    FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
        CategoryIcons.PALETTE.forEach { choice ->
            Choice(choice.label, selected = color == choice.argb, onClick = { onColor(choice.argb) }) {
                Box(Modifier.size(SWATCH).clip(CircleShape).background(Color(choice.argb)))
            }
        }
    }
}

/** One 48 dp option in a picker grid; the selection is announced by TalkBack and shown with a ring. */
@Composable
private fun Choice(
    @StringRes label: Int,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val description = stringResource(label)
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        Modifier
            .size(TOUCH_TARGET)
            .clip(CircleShape)
            .border(RING_WIDTH, ring, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

private const val SOFT_ALPHA = 0.16f
private val TOUCH_TARGET = 48.dp
private val SWATCH = 32.dp
private val RING_WIDTH = 2.dp
