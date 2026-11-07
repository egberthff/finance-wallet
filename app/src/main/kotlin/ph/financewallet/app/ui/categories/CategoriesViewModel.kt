package ph.financewallet.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.ui.common.Tables
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.common.reloadOn
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.data.account.CategoryUsage
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.ledger.AccountClass

/** Result of "Add suggested categories": an error message, or how many were added (0 = all already there). */
data class SeedOutcome(
    val error: Int?,
    val created: Int,
)

data class CategoriesState(
    val loading: Boolean = true,
    /** Live categories, in stored order. */
    val categories: List<CategoryRow> = emptyList(),
    /** Archived categories: kept on history, restorable (UC-14). */
    val archived: List<CategoryRow> = emptyList(),
    /** Non-null while in reorder mode: [categories] in the order the user has arranged so far, not yet saved. */
    val reorderDraft: List<CategoryRow>? = null,
    /** Seeded categories from before colours existed can get their suggested colours once (see CategoryService). */
    val suggestedColorsAvailable: Boolean = false,
) {
    /** What the list shows: the draft while reordering, otherwise the stored order. */
    val shown: List<CategoryRow> get() = reorderDraft ?: categories

    /** The draft differs from the stored order, so leaving reorder mode would lose something. */
    val reorderChanged: Boolean get() = reorderDraft != null && reorderDraft.map { it.id } != categories.map { it.id }
}

/** UC-14 screen state: create, edit, archive, reorder, plus the optional default seed (CAT-02). */
class CategoriesViewModel(
    private val s: AppServices,
) : ViewModel(),
    CategoryCreator {
    private val mutable = MutableStateFlow(CategoriesState())
    val state: StateFlow<CategoriesState> = mutable

    init {
        refresh()
        reloadOn(s, *Tables.CATEGORIES) { reload() }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /**
     * Reloads from the database. A reorder in progress survives (screen re-entry, configuration change): its rows are
     * refreshed, rows that are gone are dropped, and new rows are added at the end.
     */
    suspend fun reload() {
        val all = s.categories.categories(includeArchived = true)
        val rows = all.filter { it.archivedAt == null }
        val draft =
            mutable.value.reorderDraft?.let { old ->
                val byId = rows.associateBy { it.id }
                val kept = old.mapNotNull { byId[it.id] }
                kept + rows.filter { r -> kept.none { it.id == r.id } }
            }
        mutable.value =
            CategoriesState(
                loading = false,
                categories = rows,
                archived = all.filter { it.archivedAt != null },
                reorderDraft = draft,
                suggestedColorsAvailable = s.categories.suggestedColors().isNotEmpty(),
            )
    }

    suspend fun create(
        name: String,
        cls: AccountClass,
        parentId: String?,
        icon: String,
        colorArgb: Long,
    ): Int? =
        s.categories
            .create(name, cls, parentId, icon, colorArgb)
            .errorRes()
            .also { if (it == null) reload() }

    /** Like [create], also returning the new id (so a picker can select what was just made). */
    override suspend fun createReturningId(
        name: String,
        cls: AccountClass,
        parentId: String?,
        icon: String,
        colorArgb: Long,
    ): Pair<String?, Int?> =
        when (val r = s.categories.create(name, cls, parentId, icon, colorArgb)) {
            is DomainResult.Ok -> (r.value to null).also { reload() }
            is DomainResult.Err -> null to r.errorRes()
        }

    /** Rename and restyle (CAT-01). Names are joined at query time, so history, reports and budgets show the new name. */
    suspend fun edit(
        id: String,
        name: String,
        icon: String,
        colorArgb: Long,
    ): Int? =
        s.categories
            .edit(id, name, icon, colorArgb)
            .errorRes()
            .also { if (it == null) reload() }

    suspend fun archive(id: String): Int? =
        s.categories
            .archive(id)
            .errorRes()
            .also { if (it == null) reload() }

    /** Restore (UC-14 unarchive); also the Undo of an archive and the answer to "restore instead of creating?". */
    override suspend fun unarchive(id: String): Int? =
        s.categories
            .unarchive(id)
            .errorRes()
            .also { if (it == null) reload() }

    /** What uses the category and its subcategories, for the archive confirmation. */
    suspend fun usage(id: String): CategoryUsage = s.categories.usage(id)

    /** The archived category that blocks a new one with this name (after `CATEGORY_NAME_ARCHIVED`). */
    override suspend fun archivedNamed(
        name: String,
        cls: AccountClass,
        parentId: String?,
    ): CategoryRow? = s.categories.archivedNamed(name, cls, parentId)

    /** Adds the missing starter categories; returns the number added, or the error. */
    suspend fun seed(): SeedOutcome =
        when (val r = s.categories.seedDefaults()) {
            is DomainResult.Ok -> SeedOutcome(null, r.value).also { reload() }
            is DomainResult.Err -> SeedOutcome(r.errorRes(), 0)
        }

    /** Answers the one-time colour offer: [apply] colours the old starter categories; otherwise the offer is dismissed. */
    suspend fun answerSuggestedColors(apply: Boolean): Int? {
        val result = if (apply) s.categories.applySuggestedColors() else s.categories.dismissSuggestedColors()
        return result.errorRes().also { reload() }
    }

    fun startReorder() {
        mutable.value = mutable.value.copy(reorderDraft = mutable.value.categories)
    }

    /** Leaves reorder mode without saving. */
    fun cancelReorder() {
        mutable.value = mutable.value.copy(reorderDraft = null)
    }

    /** Swaps [id] with its previous (or next) sibling in the draft. Siblings share a class and a parent. */
    fun move(
        id: String,
        up: Boolean,
    ) {
        val draft = mutable.value.reorderDraft ?: return
        val row = draft.firstOrNull { it.id == id } ?: return
        val siblingIndexes = draft.indices.filter { draft[it].isSiblingOf(row) }
        val at = siblingIndexes.indexOf(draft.indexOf(row))
        val other = siblingIndexes.getOrNull(if (up) at - 1 else at + 1) ?: return
        val next = draft.toMutableList()
        next[siblingIndexes[at]] = draft[other]
        next[other] = row
        mutable.value = mutable.value.copy(reorderDraft = next)
    }

    /** Leaves reorder mode, saving each sibling group whose order changed. Returns the first error, or null. */
    suspend fun finishReorder(): Int? {
        val state = mutable.value
        val draft = state.reorderDraft ?: return null

        fun groups(list: List<CategoryRow>) = list.groupBy { it.accountClass to it.parentId }.mapValues { (_, rows) -> rows.map { it.id } }
        val before = groups(state.categories)
        val error =
            groups(draft)
                .filter { (key, ids) -> before[key] != ids }
                .firstNotNullOfOrNull { (key, ids) -> s.categories.reorder(AccountClass.valueOf(key.first), key.second, ids).errorRes() }
        mutable.value = mutable.value.copy(reorderDraft = null)
        reload()
        return error
    }

    private fun CategoryRow.isSiblingOf(other: CategoryRow) = accountClass == other.accountClass && parentId == other.parentId
}
