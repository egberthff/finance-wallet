package ph.financewallet.app.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.IconDisc
import ph.financewallet.app.ui.common.SurfaceCard
import ph.financewallet.app.ui.theme.AppIcons
import ph.financewallet.app.ui.theme.LocalSpacing
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.text.TextNormalizer

/**
 * One field that shows the chosen category ("Food › Groceries" with its icon). A tap opens a bottom sheet with a search
 * box (case- and accent-insensitive), recently used categories, every category grouped under its main category, and
 * "+ New category", which creates one without leaving the form and selects it (blueprint D; fixes T-2, T-4, C-12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPicker(
    options: List<CategoryOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    recentIds: List<String> = emptyList(),
    creator: CategoryCreator? = null,
    newCategoryClass: AccountClass = AccountClass.EXPENSE,
    /** When set, the sheet offers "No category" (for optional categories such as a bill's). */
    onClear: (() -> Unit)? = null,
    /** Text when nothing is chosen (default "Choose a category"). */
    placeholder: String? = null,
    /** Label of the clearing row (default "No category"). */
    noneLabel: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    var creatingWith by remember { mutableStateOf<String?>(null) }
    val selected = options.firstOrNull { it.id == selectedId }
    PickerField(selected, modifier, placeholder) { open = true }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            PickerSheet(
                options,
                selectedId,
                recentIds,
                canCreate = creator != null,
                noneLabel = noneLabel,
                onClear =
                    onClear?.let { clear ->
                        {
                            clear()
                            open = false
                        }
                    },
                onPick = {
                    onSelect(it)
                    open = false
                },
                onCreate = { query -> creatingWith = query },
            )
        }
    }
    val newName = creatingWith
    if (creator != null && newName != null) {
        AddCategoryDialog(
            creator,
            existing = options.map { it.row }.filter { it.archivedAt == null },
            onRestored = {},
            onDismiss = { creatingWith = null },
            initialName = newName,
            initialClass = newCategoryClass,
            onCreated = { id ->
                onSelect(id)
                open = false
            },
        )
    }
}

/** The read-only field: icon and path of the choice, or a prompt. Announced as "Category, Food › Groceries". */
@Composable
private fun PickerField(
    selected: CategoryOption?,
    modifier: Modifier,
    placeholder: String?,
    onOpen: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val label = stringResource(R.string.field_category)
    val value = selected?.let { pathLabel(it) } ?: placeholder ?: stringResource(R.string.picker_choose_category)
    SurfaceCard(modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $value" }, onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TOUCH).padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (selected != null) {
                CategoryIcon(selected.row, small = true)
            } else {
                IconDisc(AppIcons.Label, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant, small = true)
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickerSheet(
    options: List<CategoryOption>,
    selectedId: String?,
    recentIds: List<String>,
    canCreate: Boolean,
    onClear: (() -> Unit)?,
    noneLabel: String?,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var query by remember { mutableStateOf("") }
    val matches = filterOptions(options, query)
    val recent = if (query.isBlank()) recentIds.mapNotNull { id -> options.firstOrNull { it.id == id && !it.archived } } else emptyList()
    Column(Modifier.padding(horizontal = spacing.md).padding(bottom = spacing.lg)) {
        OutlinedTextField(
            query,
            { query = it },
            label = { Text(stringResource(R.string.picker_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            if (canCreate) item { NewCategoryRow(query) { onCreate(query.trim()) } }
            if (onClear != null && query.isBlank()) item { NoneRow(noneLabel, selectedId == null, onClear) }
            if (recent.isNotEmpty()) {
                item { SheetHeading(stringResource(R.string.picker_recent)) }
                items(recent, key = { "r" + it.id }) { OptionRow(it, it.id == selectedId, showPath = true) { onPick(it.id) } }
                item { HorizontalDivider() }
            }
            if (matches.isEmpty()) item { SheetHeading(stringResource(R.string.picker_no_match)) }
            items(matches, key = { it.id }) { OptionRow(it, it.id == selectedId, showPath = query.isNotBlank()) { onPick(it.id) } }
        }
    }
}

/** Options whose "Parent › Child" path contains [query], ignoring case and accents; all options for a blank query. */
fun filterOptions(
    options: List<CategoryOption>,
    query: String,
): List<CategoryOption> {
    val q = TextNormalizer.normalize(query)
    return if (q.isEmpty()) options else options.filter { TextNormalizer.normalize(it.path).contains(q) }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LocalSpacing.current.md, bottom = LocalSpacing.current.xs).semantics { heading() },
    )
}

@Composable
private fun NewCategoryRow(
    query: String,
    onClick: () -> Unit,
) {
    val text = if (query.isBlank()) stringResource(R.string.picker_new) else stringResource(R.string.picker_new_named, query.trim())
    Row(
        Modifier.fillMaxWidth().heightIn(min = TOUCH).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        IconDisc(AppIcons.Add, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, small = true)
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun OptionRow(
    option: CategoryOption,
    selected: Boolean,
    showPath: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val selectedText = stringResource(R.string.picker_selected)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { if (selected) stateDescription = selectedText }
            .padding(start = if (option.isSubcategory && !showPath) spacing.lg else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        CategoryIcon(option.row, small = true)
        Text(
            if (showPath) pathLabel(option) else nameLabel(option),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Multi-select for budgets: checkbox rows grouped under their main category (replaces the chip cloud). Each row is
 * one 48 dp target with the checkbox state announced.
 */
@Composable
fun CategoryChecklist(
    options: List<CategoryOption>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column {
        options.forEach { option ->
            val checked = option.id in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TOUCH)
                    .clickable(role = Role.Checkbox) { onToggle(option.id) }
                    .padding(start = if (option.isSubcategory) spacing.lg else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                CategoryIcon(option.row, small = true)
                Text(nameLabel(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun pathLabel(option: CategoryOption): String =
    if (option.archived) stringResource(R.string.picker_archived_suffix, option.path) else option.path

@Composable
private fun nameLabel(option: CategoryOption): String =
    if (option.archived) stringResource(R.string.picker_archived_suffix, option.name) else option.name

private val TOUCH = 48.dp

@Composable
private fun NoneRow(
    label: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedText = stringResource(R.string.picker_selected)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { if (selected) stateDescription = selectedText },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        IconDisc(AppIcons.Label, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant, small = true)
        Text(label ?: stringResource(R.string.picker_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
    }
}
