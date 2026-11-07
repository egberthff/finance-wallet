package ph.financewallet.app.ui.categories

import ph.financewallet.app.AppServices
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.data.account.CategoryRow
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.ledger.AccountClass

/**
 * One choosable category. [path] is "Parent › Child" for a subcategory, so two subcategories with the same name under
 * different parents are never confused. [archived] is set only for an archived category that is shown because the item
 * being edited already uses it (UC-09, blueprint B).
 */
data class CategoryOption(
    val row: CategoryRow,
    val parentName: String?,
) {
    val id: String get() = row.id
    val name: String get() = row.name
    val archived: Boolean get() = row.archivedAt != null
    val isSubcategory: Boolean get() = row.parentId != null
    val path: String get() = parentName?.let { "$it › ${row.name}" } ?: row.name
}

/**
 * The categories to offer, in display order: each main category followed by its subcategories, in stored order.
 * [rows] should include archived ones; they are offered only when their id is in [includeIds] (the current
 * selection of an item being edited). [accountClass] limits the list to income or expense; null offers both.
 */
fun categoryOptions(
    rows: List<CategoryRow>,
    accountClass: String? = null,
    includeIds: Set<String> = emptySet(),
): List<CategoryOption> {
    val names = rows.associate { it.id to it.name }
    val offered =
        rows.filter {
            (accountClass == null || it.accountClass == accountClass) && (it.archivedAt == null || it.id in includeIds)
        }
    val ids = offered.map { it.id }.toSet()
    val children = offered.filter { it.parentId != null }.groupBy { it.parentId }
    // A subcategory whose main category is not offered (archived) still appears, under its full path.
    val roots = offered.filter { it.parentId == null || it.parentId !in ids }
    return roots.flatMap { root ->
        listOf(CategoryOption(root, root.parentId?.let { names[it] })) +
            children[root.id].orEmpty().map { CategoryOption(it, root.name) }
    }
}

/** Creating (or restoring) a category from wherever a category is picked, so the user never has to leave (T-4). */
interface CategoryCreator {
    /** The new id, or an error message resource. */
    suspend fun createReturningId(
        name: String,
        cls: AccountClass,
        parentId: String?,
        icon: String,
        colorArgb: Long,
    ): Pair<String?, Int?>

    suspend fun archivedNamed(
        name: String,
        cls: AccountClass,
        parentId: String?,
    ): CategoryRow?

    suspend fun unarchive(id: String): Int?
}

/** [CategoryCreator] for screens other than Categories; [onChanged] reloads the screen's options. */
class ServicesCategoryCreator(
    private val s: AppServices,
    private val onChanged: suspend () -> Unit,
) : CategoryCreator {
    override suspend fun createReturningId(
        name: String,
        cls: AccountClass,
        parentId: String?,
        icon: String,
        colorArgb: Long,
    ): Pair<String?, Int?> =
        when (val r = s.categories.create(name, cls, parentId, icon, colorArgb)) {
            is DomainResult.Ok -> (r.value to null).also { onChanged() }
            is DomainResult.Err -> null to r.errorRes()
        }

    override suspend fun archivedNamed(
        name: String,
        cls: AccountClass,
        parentId: String?,
    ): CategoryRow? = s.categories.archivedNamed(name, cls, parentId)

    override suspend fun unarchive(id: String): Int? =
        s.categories
            .unarchive(id)
            .errorRes()
            .also { if (it == null) onChanged() }
}
