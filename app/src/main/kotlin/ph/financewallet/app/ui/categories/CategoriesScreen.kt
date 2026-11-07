package ph.financewallet.app.ui.categories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.CardList
import ph.financewallet.app.ui.common.ListRow
import ph.financewallet.app.ui.common.ListScreen
import ph.financewallet.app.ui.common.SectionTitle
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.domain.ledger.AccountClass

/**
 * UC-14. Live categories by class, then a collapsible "Archived (n)" section with Restore. Messages (errors, "Archived
 * Food. Undo", seed results) are snackbars, so they are visible wherever the user has scrolled (C-8).
 */
@Composable
fun CategoriesScreen(
    vm: CategoriesViewModel,
    onShowTransactions: (String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val messages = Messages(scope, snackbar, LocalContext.current.resources)
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CategoryRow?>(null) }
    var askSaveOrder by remember { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val reordering = state.reorderDraft != null
    val finishReorder: () -> Unit = { scope.launch { messages.error(vm.finishReorder()) } }
    // Back while reordering never drops a changed order silently.
    BackHandler(enabled = reordering) { if (state.reorderChanged) askSaveOrder = true else vm.cancelReorder() }
    ListScreen(if (reordering) null else stringResource(R.string.categories_add), { adding = true }, snackbarHostState = snackbar) {
        headerItems(
            state,
            onToggleReorder = { if (reordering) finishReorder() else vm.startReorder() },
            onSeed = { scope.launch { messages.seeded(vm.seed()) } },
            onColors = { apply -> scope.launch { messages.error(vm.answerSuggestedColors(apply)) } },
        )
        listOf(AccountClass.INCOME, AccountClass.EXPENSE).forEach { cls ->
            val group = ordered(state.shown.filter { it.accountClass == cls.name })
            if (group.isNotEmpty()) {
                item { SectionTitle(stringResource(if (cls == AccountClass.INCOME) R.string.kind_income else R.string.kind_expense)) }
                item { CategoryGroup(group, reordering = reordering, onOpen = { editing = it }, onMove = vm::move) }
            }
        }
        if (!reordering) {
            archivedItems(state, showArchived, { showArchived = !showArchived }) { row ->
                scope.launch { messages.restored(row, vm.unarchive(row.id)) }
            }
        }
    }
    CategoryDialogs(
        vm,
        state,
        adding = adding,
        editing = editing,
        askSaveOrder = askSaveOrder,
        onCloseAdd = { adding = false },
        onCloseEdit = { editing = null },
        onArchived = { row -> messages.archived(row) { vm.unarchive(row.id) } },
        onShowTransactions = { id ->
            editing = null
            onShowTransactions(id)
        },
        onRestored = { row -> scope.launch { messages.restored(row, null) } },
        onSaveOrder = { save ->
            askSaveOrder = false
            when (save) {
                true -> finishReorder()
                false -> vm.cancelReorder()
                null -> Unit
            }
        },
    )
}

/** The screen's dialogs: add, edit (with archive), and "save the new order?". */
@Composable
private fun CategoryDialogs(
    vm: CategoriesViewModel,
    state: CategoriesState,
    adding: Boolean,
    editing: CategoryRow?,
    askSaveOrder: Boolean,
    onCloseAdd: () -> Unit,
    onCloseEdit: () -> Unit,
    onArchived: (CategoryRow) -> Unit,
    onShowTransactions: (String) -> Unit,
    onRestored: (CategoryRow) -> Unit,
    onSaveOrder: (Boolean?) -> Unit,
) {
    if (adding) AddCategoryDialog(vm, state.categories, onRestored = onRestored, onDismiss = onCloseAdd)
    editing?.let { row ->
        EditCategoryDialog(
            row,
            liveChildren = state.categories.count { it.parentId == row.id },
            loadUsage = { vm.usage(row.id) },
            onSave = { name, icon, color -> vm.edit(row.id, name, icon, color) },
            onArchive = { vm.archive(row.id).also { if (it == null) onArchived(row) } },
            onDismiss = onCloseEdit,
            onShowTransactions = { onShowTransactions(row.id) },
        )
    }
    if (askSaveOrder) SaveOrderDialog(onSave = { onSaveOrder(true) }, onDiscard = { onSaveOrder(false) }, onCancel = { onSaveOrder(null) })
}

/** Snackbar wording for the screen's outcomes. */
private class Messages(
    private val scope: CoroutineScope,
    private val host: SnackbarHostState,
    private val res: android.content.res.Resources,
) {
    suspend fun error(message: Int?) {
        if (message != null) host.showSnackbar(res.getString(message))
    }

    suspend fun seeded(outcome: SeedOutcome) {
        when {
            outcome.error != null -> error(outcome.error)
            outcome.created == 0 -> host.showSnackbar(res.getString(R.string.categories_seed_none))
            else -> host.showSnackbar(res.getQuantityString(R.plurals.categories_seed_added, outcome.created, outcome.created))
        }
    }

    suspend fun restored(
        row: CategoryRow,
        failure: Int?,
    ) {
        if (failure != null) error(failure) else host.showSnackbar(res.getString(R.string.categories_restored, row.name))
    }

    /** "Archived Food" with Undo, which restores it (UC-14). */
    fun archived(
        row: CategoryRow,
        undo: suspend () -> Int?,
    ) {
        scope.launch {
            val result = host.showSnackbar(res.getString(R.string.categories_archived, row.name), res.getString(R.string.action_undo))
            if (result == SnackbarResult.ActionPerformed) error(undo())
        }
    }
}

/** Reorder toggle and "add suggested" action, the seed and colour offers, above the category groups. */
private fun LazyListScope.headerItems(
    state: CategoriesState,
    onToggleReorder: () -> Unit,
    onSeed: () -> Unit,
    onColors: (apply: Boolean) -> Unit,
) {
    val reordering = state.reorderDraft != null
    if (state.categories.isNotEmpty()) item { ReorderBar(reordering, onToggleReorder, onSeed) }
    if (state.categories.isEmpty() && !state.loading) item { SeedCard(onSeed = onSeed) }
    if (state.suggestedColorsAvailable && !reordering) {
        item { SuggestedColorsCard(onApply = { onColors(true) }, onDismiss = { onColors(false) }) }
    }
}

/** "Archived (n)", collapsed by default; each row can be restored unless its main category is archived too. */
private fun LazyListScope.archivedItems(
    state: CategoriesState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRestore: (CategoryRow) -> Unit,
) {
    if (state.archived.isEmpty()) return
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.categories_archived_title, state.archived.size))
            Row(Modifier.weight(1f)) {}
            TextButton(onClick = onToggle) {
                Text(stringResource(if (expanded) R.string.categories_archived_hide else R.string.categories_archived_show))
            }
        }
    }
    if (!expanded) return
    item {
        val archivedIds = state.archived.map { it.id }.toSet()
        val names = (state.categories + state.archived).associate { it.id to it.name }
        CardList(state.archived) { c ->
            val parentArchived = c.parentId != null && c.parentId in archivedIds
            ListRow(
                c.name,
                subtitle =
                    if (parentArchived) {
                        stringResource(R.string.categories_restore_parent_first, names[c.parentId].orEmpty())
                    } else {
                        c.parentId?.let { stringResource(R.string.categories_subcategory_of, names[it].orEmpty()) }
                    },
                leading = { CategoryIcon(c) },
            ) {
                TextButton(onClick = { onRestore(c) }, enabled = !parentArchived) { Text(stringResource(R.string.categories_restore)) }
            }
        }
    }
}

/** Offers the optional PH starter categories (CAT-02) while the list is empty. */
@Composable
private fun SeedCard(onSeed: () -> Unit) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(stringResource(R.string.categories_seed_explain), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSeed) { Text(stringResource(R.string.categories_seed)) }
        }
    }
}

/** One-time offer to colour the starter categories that still have the default colour. */
@Composable
private fun SuggestedColorsCard(
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(stringResource(R.string.categories_colors_explain), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Button(onClick = onApply) { Text(stringResource(R.string.categories_colors_apply)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) }
            }
        }
    }
}

/** Reorder on/off and, outside reorder mode, "Add suggested" (adds only the missing starter categories, C-9). */
@Composable
private fun ReorderBar(
    reordering: Boolean,
    onToggle: () -> Unit,
    onSeed: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (reordering) {
            Text(
                stringResource(R.string.categories_reorder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            TextButton(onClick = onSeed) { Text(stringResource(R.string.categories_seed_short)) }
            Row(Modifier.weight(1f)) {}
        }
        TextButton(onClick = onToggle) {
            Text(stringResource(if (reordering) R.string.categories_reorder_done else R.string.categories_reorder))
        }
    }
}

/** Top-level categories in their stored order, each followed by its subcategories. */
private fun ordered(list: List<CategoryRow>): List<CategoryRow> {
    val children = list.filter { it.parentId != null }.groupBy { it.parentId }
    val ids = list.map { it.id }.toSet()
    // A subcategory whose parent is archived (not in the list) still shows, on its own.
    val roots = list.filter { it.parentId == null || it.parentId !in ids }
    return roots.flatMap { listOf(it) + children[it.id].orEmpty() }
}

/**
 * One class of categories. Tapping a row opens the edit dialog; in reorder mode each row instead gets up/down buttons
 * that move it among its siblings (no drag gesture, so it works with TalkBack and switch access).
 */
@Composable
private fun CategoryGroup(
    group: List<CategoryRow>,
    reordering: Boolean,
    onOpen: (CategoryRow) -> Unit,
    onMove: (id: String, up: Boolean) -> Unit,
) {
    val byId = group.associateBy { it.id }
    CardList(group) { c ->
        val parent = c.parentId?.let { byId[it] }
        val siblings = group.filter { it.parentId == c.parentId }
        ListRow(
            c.name,
            modifier = if (parent != null) Modifier.padding(start = LocalSpacing.current.lg) else Modifier,
            subtitle = parent?.let { stringResource(R.string.categories_subcategory_of, it.name) },
            leading = { CategoryIcon(c) },
            onClick = if (reordering) null else ({ onOpen(c) }),
        ) {
            if (reordering) {
                IconButton(onClick = { onMove(c.id, true) }, enabled = siblings.firstOrNull() != c) {
                    Icon(AppIcons.ArrowUp, stringResource(R.string.categories_move_up, c.name))
                }
                IconButton(onClick = { onMove(c.id, false) }, enabled = siblings.lastOrNull() != c) {
                    Icon(AppIcons.ArrowDown, stringResource(R.string.categories_move_down, c.name))
                }
            } else {
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
