package ph.financewallet.app.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.ChoiceChips
import ph.financewallet.app.ui.common.ErrorLine
import ph.financewallet.app.ui.common.FieldLabel
import ph.financewallet.app.ui.common.ParentPicker
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.account.CategoryService
import ph.financewallet.domain.ledger.AccountClass

/**
 * Name, kind, optional parent, then icon and colour; a subcategory starts with its parent's icon and colour. When the
 * name belongs to an archived sibling, the dialog offers to restore that one instead (C-2): archived names stay
 * reserved so a restore can never create a duplicate.
 */
@Composable
internal fun AddCategoryDialog(
    creator: CategoryCreator,
    existing: List<CategoryRow>,
    onRestored: (CategoryRow) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialClass: AccountClass = AccountClass.EXPENSE,
    onCreated: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(NewCategoryDraft(initialName, initialClass)) }
    var error by remember { mutableStateOf<Int?>(null) }
    var restoreOffer by remember { mutableStateOf<CategoryRow?>(null) }
    restoreOffer?.let { archived ->
        RestoreOfferDialog(archived.name, onRestore = {
            scope.launch {
                error = creator.unarchive(archived.id)
                restoreOffer = null
                if (error == null) {
                    onRestored(archived)
                    onCreated(archived.id)
                    onDismiss()
                }
            }
        }, onCancel = { restoreOffer = null })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.categories_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                NewCategoryFields(draft, existing) { draft = it }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val d = draft
                    val (id, failure) = creator.createReturningId(d.name, d.cls, d.parent?.id, d.icon, d.color)
                    error = failure
                    if (failure == R.string.err_category_name_archived) restoreOffer = creator.archivedNamed(d.name, d.cls, d.parent?.id)
                    if (failure == null) {
                        id?.let(onCreated)
                        onDismiss()
                    }
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A new category as typed: name, kind, optional parent, icon and colour. */
private data class NewCategoryDraft(
    val name: String,
    val cls: AccountClass,
    val parent: CategoryRow? = null,
    val icon: String = CategoryService.DEFAULT_ICON,
    val color: Long = CategoryService.DEFAULT_COLOR,
)

@Composable
private fun NewCategoryFields(
    d: NewCategoryDraft,
    existing: List<CategoryRow>,
    onChange: (NewCategoryDraft) -> Unit,
) {
    OutlinedTextField(d.name, { onChange(d.copy(name = it)) }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
    ChoiceChips(listOf(AccountClass.EXPENSE, AccountClass.INCOME), d.cls, {
        stringResource(if (it == AccountClass.INCOME) R.string.kind_income else R.string.kind_expense)
    }, { onChange(d.copy(cls = it, parent = null)) })
    FieldLabel(stringResource(R.string.categories_parent_optional))
    ParentPicker(existing.filter { it.accountClass == d.cls.name && it.parentId == null }, d.parent, { it.name }) { p ->
        // A subcategory starts with its parent's icon and colour, so the group looks consistent.
        onChange(if (p == null) d.copy(parent = null) else d.copy(parent = p, icon = p.icon, color = p.colorArgb))
    }
    StylePicker(d.icon, d.color, onIcon = { onChange(d.copy(icon = it)) }, onColor = { onChange(d.copy(color = it)) })
}

/** "An archived category with this name exists. Restore it?" */
@Composable
private fun RestoreOfferDialog(
    name: String,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.categories_restore_offer_title, name)) },
        text = { Text(stringResource(R.string.categories_restore_offer)) },
        confirmButton = { TextButton(onClick = onRestore) { Text(stringResource(R.string.categories_restore)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Back pressed with an unsaved order: save it, discard it, or keep reordering. */
@Composable
internal fun SaveOrderDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.categories_reorder_unsaved)) },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.action_discard)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
