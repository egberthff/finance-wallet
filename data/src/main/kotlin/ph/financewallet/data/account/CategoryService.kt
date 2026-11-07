package ph.financewallet.data.account

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.CategoryEntity
import ph.financewallet.data.db.entity.LedgerAccountEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.ledger.AccountClass
import java.text.Normalizer
import java.util.Locale

/**
 * Category management UC-14 (CAT-01/02/04): create, edit, archive/unarchive, reorder, and the optional default
 * Philippine seed (Phase 4 step 4.9). Rules: income or expense only; names unique among siblings (Unicode
 * case-insensitive, archived siblings included so a restore can never create a duplicate); one level of
 * subcategories; archived categories stay on history and cannot receive new transactions. Every change writes an
 * `audit_event` (CLAUDE.md §16).
 */
class CategoryService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val dao get() = db.accountDao()
    private val ledgerDao get() = db.ledgerDao()

    suspend fun categories(includeArchived: Boolean = false): List<CategoryRow> = dao.categories(includeArchived)

    /** What uses [categoryId] and its subcategories (shown before archiving). */
    suspend fun usage(categoryId: String): CategoryUsage = dao.categoryUsage(categoryId)

    /** The archived sibling that blocks [name] (after `CATEGORY_NAME_ARCHIVED`), so the UI can offer to restore it. */
    suspend fun archivedNamed(
        name: String,
        accountClass: AccountClass,
        parentId: String?,
    ): CategoryRow? = siblingNamed(name, accountClass.name, parentId, excludingId = "")?.takeIf { it.archivedAt != null }

    /**
     * Creates a category. A name already used by a live sibling fails with `CATEGORY_NAME_TAKEN`; one used by an
     * archived sibling fails with `CATEGORY_NAME_ARCHIVED` (see [archivedNamed]), because restoring is the fix.
     */
    suspend fun create(
        name: String,
        accountClass: AccountClass,
        parentId: String? = null,
        icon: String = DEFAULT_ICON,
        colorArgb: Long = DEFAULT_COLOR,
    ): DomainResult<String> =
        db.validatedWrite {
            insert(name, accountClass, parentId, icon, colorArgb, seed = false).also { id ->
                audit("category.create", id, mapOf("name" to name.trim(), "class" to accountClass.name, "parent" to parentId.orEmpty()))
            }
        }

    suspend fun edit(
        categoryId: String,
        name: String,
        icon: String,
        colorArgb: Long,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val (account, category) = load(categoryId)
            checkName(name, account.accountClass, category.parentId, categoryId)
            ensure(icon.isNotBlank(), "icon", "ICON_REQUIRED")
            val now = ctx.nowMillis()
            dao.updateLedgerAccount(account.copy(name = name.trim(), version = account.version + 1, updatedAt = now, hlc = ctx.nextHlc()))
            dao.updateCategory(
                category.copy(icon = icon, colorArgb = colorArgb, version = category.version + 1, updatedAt = now, hlc = ctx.nextHlc()),
            )
            audit(
                "category.edit",
                categoryId,
                mapOf(
                    "name.before" to account.name,
                    "name.after" to name.trim(),
                    "icon.before" to category.icon,
                    "icon.after" to icon,
                    "color.before" to category.colorArgb.toString(HEX),
                    "color.after" to colorArgb.toString(HEX),
                ),
            )
        }

    /** Archives a category. A parent with live subcategories must have them archived first, so nothing is hidden by surprise. */
    suspend fun archive(categoryId: String): DomainResult<Unit> =
        db.validatedWrite {
            val (account, _) = load(categoryId)
            ensure(account.archivedAt == null, "categoryId", "ALREADY_ARCHIVED")
            ensure(dao.liveChildCount(categoryId) == 0, "categoryId", "CATEGORY_HAS_CHILDREN")
            dao.updateLedgerAccount(
                account.copy(archivedAt = ctx.nowMillis(), version = account.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()),
            )
            audit("category.archive", categoryId, mapOf("name" to account.name))
        }

    suspend fun unarchive(categoryId: String): DomainResult<Unit> =
        db.validatedWrite {
            val (account, category) = load(categoryId)
            ensure(account.archivedAt != null, "categoryId", "NOT_ARCHIVED")
            category.parentId?.let { ensure(ledgerDao.ledgerAccount(it)?.archivedAt == null, "categoryId", "PARENT_ARCHIVED") }
            checkName(account.name, account.accountClass, category.parentId, categoryId)
            dao.updateLedgerAccount(
                account.copy(archivedAt = null, version = account.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()),
            )
            audit("category.unarchive", categoryId, mapOf("name" to account.name))
        }

    /** Sets the order of [orderedIds], which must be exactly the live children of [parentId] (or top level) of one class. */
    suspend fun reorder(
        accountClass: AccountClass,
        parentId: String?,
        orderedIds: List<String>,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val siblings =
                dao
                    .categories(
                        includeArchived = false,
                    ).filter { it.accountClass == accountClass.name && it.parentId == parentId }
            ensure(orderedIds.toSet() == siblings.map { it.id }.toSet() && orderedIds.size == siblings.size, "orderedIds", "ORDER_MISMATCH")
            orderedIds.forEachIndexed { i, id ->
                val c = checkNotNull(ledgerDao.category(id))
                if (c.sortOrder != i) {
                    dao.updateCategory(c.copy(sortOrder = i, version = c.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()))
                }
            }
            audit("category.reorder", parentId ?: accountClass.name, mapOf("order" to orderedIds.joinToString(",")))
        }

    /**
     * Adds the default PH categories (CAT-02). Skippable at onboarding; never forced. It can be run again at any time
     * (decision 2026-11-07, docs/12-use-cases.md UC-14) and then adds only the starter categories that are missing.
     * A category whose name already exists among its siblings, live or archived, is skipped, so a user's own categories
     * are never duplicated or renamed and an archived starter category is not re-created (restore it instead).
     * Returns the number of categories created.
     */
    suspend fun seedDefaults(): DomainResult<Int> =
        db.validatedWrite {
            var created = 0
            DefaultCategories.ALL.forEach { group ->
                val parentId = findOrCreate(group.name, group.accountClass, null, group.icon, group.colorArgb) { created++ }
                group.children.forEach { child ->
                    findOrCreate(child, group.accountClass, parentId, group.icon, group.colorArgb) { created++ }
                }
            }
            dao.setMeta(SEED_KEY, ctx.nowMillis().toString())
            // Starter categories made now already have their colours, so the one-time colour offer never applies to them.
            dao.setMeta(COLORS_KEY, ctx.nowMillis().toString())
            if (created > 0) audit("category.seed", SEED_KEY, mapOf("created" to created.toString()))
            created
        }

    /**
     * Seeded categories that still have the default colour and would get their suggested colour. Seeds made before
     * colours existed stored [DEFAULT_COLOR] for every row. Only rows with `is_default_seed` and the default colour
     * count, so a colour the user chose is never touched. Empty once the offer was applied or dismissed.
     */
    suspend fun suggestedColors(): Map<String, Long> {
        if (dao.meta(COLORS_KEY) != null) return emptyMap()
        val rows = dao.categories(includeArchived = true)
        val byId = rows.associateBy { it.id }
        val groups = DefaultCategories.ALL.associateBy { it.accountClass.name to it.name.lowercase() }
        return rows
            .filter { it.isDefaultSeed && it.colorArgb == DEFAULT_COLOR }
            .mapNotNull { row ->
                val top = row.parentId?.let { byId[it] } ?: row
                groups[row.accountClass to top.name.trim().lowercase()]
                    ?.colorArgb
                    ?.takeIf { it != DEFAULT_COLOR }
                    ?.let { row.id to it }
            }.toMap()
    }

    /** Applies [suggestedColors] once (colour only; names, icons and order stay). Returns the number of categories changed. */
    suspend fun applySuggestedColors(): DomainResult<Int> =
        db.validatedWrite {
            val colors = suggestedColors()
            colors.forEach { (id, argb) ->
                val c = ledgerDao.category(id) ?: notFound("category", id)
                dao.updateCategory(c.copy(colorArgb = argb, version = c.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()))
            }
            dao.setMeta(COLORS_KEY, ctx.nowMillis().toString())
            if (colors.isNotEmpty()) audit("category.suggested_colors", COLORS_KEY, mapOf("changed" to colors.size.toString()))
            colors.size
        }

    /** The user declined the colour offer: it is not shown again. */
    suspend fun dismissSuggestedColors(): DomainResult<Unit> = db.validatedWrite { dao.setMeta(COLORS_KEY, ctx.nowMillis().toString()) }

    // ---- internals ----------------------------------------------------------------------------------------------

    private suspend fun audit(
        kind: String,
        entityId: String,
        detail: Map<String, String>,
    ) = dao.audit(ctx, kind, "category", entityId, detail)

    private suspend fun ValidationScope.findOrCreate(
        name: String,
        cls: AccountClass,
        parentId: String?,
        icon: String,
        colorArgb: Long,
        onCreated: () -> Unit,
    ): String {
        val existing = siblingNamed(name, cls.name, parentId, excludingId = "")
        if (existing != null) return existing.id
        onCreated()
        return insert(name, cls, parentId, icon, colorArgb, seed = true)
    }

    private suspend fun ValidationScope.insert(
        name: String,
        accountClass: AccountClass,
        parentId: String?,
        icon: String,
        colorArgb: Long,
        seed: Boolean,
    ): String {
        ensure(accountClass == AccountClass.INCOME || accountClass == AccountClass.EXPENSE, "class", "CATEGORY_WRONG_CLASS")
        if (parentId != null) {
            val parent = ledgerDao.ledgerAccount(parentId) ?: notFound("category", parentId)
            val parentCategory = ledgerDao.category(parentId) ?: fail("parentId", "NOT_A_CATEGORY")
            ensure(parent.accountClass == accountClass.name, "parentId", "CATEGORY_WRONG_CLASS")
            ensure(parentCategory.parentId == null, "parentId", "CATEGORY_TOO_DEEP")
            ensure(parent.archivedAt == null, "parentId", "CATEGORY_ARCHIVED")
        }
        checkName(name, accountClass.name, parentId, excludingId = "")
        val id = ctx.ids.newId()
        val now = ctx.nowMillis()
        ledgerDao.insertLedgerAccount(
            LedgerAccountEntity(id, accountClass.name, name.trim(), null, null, null, now, 1, now, ctx.deviceId, ctx.nextHlc()),
        )
        val order = dao.maxSortOrder(parentId) + 1
        ledgerDao.insertCategory(CategoryEntity(id, parentId, icon, colorArgb, order, seed, 1, now, ctx.deviceId, ctx.nextHlc()))
        return id
    }

    private suspend fun ValidationScope.checkName(
        name: String,
        accountClass: String,
        parentId: String?,
        excludingId: String,
    ) {
        ensure(name.trim().length in 1..MAX_NAME, "name", "NAME_LENGTH")
        val clash = siblingNamed(name, accountClass, parentId, excludingId) ?: return
        fail("name", if (clash.archivedAt != null) "CATEGORY_NAME_ARCHIVED" else "CATEGORY_NAME_TAKEN")
    }

    /** The sibling (live or archived) whose name equals [name] after [nameKey]; categories are few, so this runs in Kotlin. */
    private suspend fun siblingNamed(
        name: String,
        accountClass: String,
        parentId: String?,
        excludingId: String,
    ): CategoryRow? {
        val key = nameKey(name)
        return dao
            .categories(includeArchived = true)
            .firstOrNull { it.accountClass == accountClass && it.parentId == parentId && it.id != excludingId && nameKey(it.name) == key }
    }

    private suspend fun ValidationScope.load(categoryId: String) =
        (ledgerDao.ledgerAccount(categoryId) ?: notFound("category", categoryId)) to
            (ledgerDao.category(categoryId) ?: notFound("category", categoryId))

    companion object {
        const val DEFAULT_ICON = "category"
        const val DEFAULT_COLOR = 0xFF607D8BL
        private const val MAX_NAME = 80
        private const val HEX = 16
        private const val SEED_KEY = "seed:categories:v1"
        private const val COLORS_KEY = "seed:category-colors:v1"
        private val SPACES = Regex("\\s+")

        /**
         * Name comparison key (C-11): Unicode case folding ("PIÑATA" equals "Piñata") and collapsed spaces. Accents
         * are kept on purpose: "Año" and "Ano" are different words in Filipino and Spanish, so TextNormalizer's
         * accent stripping would wrongly block one of them.
         */
        fun nameKey(name: String): String =
            Normalizer
                .normalize(name, Normalizer.Form.NFC)
                .trim()
                .replace(SPACES, " ")
                .lowercase(Locale.ROOT)
    }
}

/** A default top-level category and its subcategories, which share its icon and colour. */
data class DefaultCategory(
    val name: String,
    val accountClass: AccountClass,
    val icon: String,
    val children: List<String> = emptyList(),
    /** One of the app's palette colours (the app's `CategoryIconsTest` checks this). */
    val colorArgb: Long = CategoryService.DEFAULT_COLOR,
)

/** Default categories for Philippine users (CAT-02). Generic names only; no brand or institution names. */
object DefaultCategories {
    // Palette values (app/.../ui/theme/CategoryIcons.kt). Income uses greens and teals; expenses are spread out.
    private const val RED = 0xFFE53935L
    private const val PINK = 0xFFD81B60L
    private const val PURPLE = 0xFFAB47BCL
    private const val VIOLET = 0xFF7E57C2L
    private const val INDIGO = 0xFF5C6BC0L
    private const val BLUE = 0xFF1E88E5L
    private const val CYAN = 0xFF0097A7L
    private const val TEAL = 0xFF00897BL
    private const val GREEN = 0xFF388E3CL
    private const val ORANGE = 0xFFE65100L
    private const val BROWN = 0xFF8D6E63L

    val ALL: List<DefaultCategory> =
        listOf(
            DefaultCategory("Salary", AccountClass.INCOME, "work", colorArgb = GREEN),
            DefaultCategory("13th Month Pay", AccountClass.INCOME, "work", colorArgb = GREEN),
            DefaultCategory("Bonus", AccountClass.INCOME, "work", colorArgb = GREEN),
            DefaultCategory("Freelance", AccountClass.INCOME, "laptop", colorArgb = TEAL),
            DefaultCategory("Business", AccountClass.INCOME, "store", colorArgb = TEAL),
            DefaultCategory("Remittance Received", AccountClass.INCOME, "family", colorArgb = CYAN),
            DefaultCategory("Interest", AccountClass.INCOME, "savings", colorArgb = TEAL),
            DefaultCategory("Other Income", AccountClass.INCOME, "add", colorArgb = GREEN),
            DefaultCategory("Food", AccountClass.EXPENSE, "restaurant", listOf("Groceries", "Dining Out", "Coffee & Snacks"), ORANGE),
            DefaultCategory(
                "Transportation",
                AccountClass.EXPENSE,
                "commute",
                listOf("Public Transport", "Ride-hailing & Taxi", "Fuel", "Parking & Tolls"),
                BLUE,
            ),
            DefaultCategory(
                "Bills & Utilities",
                AccountClass.EXPENSE,
                "receipt",
                listOf("Electricity", "Water", "Internet", "Mobile Load & Postpaid"),
                INDIGO,
            ),
            DefaultCategory("Housing", AccountClass.EXPENSE, "home", listOf("Rent", "Association Dues", "Repairs"), BROWN),
            DefaultCategory("Health", AccountClass.EXPENSE, "health", listOf("Medicine", "Consultation", "HMO & Insurance"), RED),
            DefaultCategory(
                "Government Contributions",
                AccountClass.EXPENSE,
                "account_balance",
                listOf("SSS", "PhilHealth", "Pag-IBIG", "Taxes"),
                CYAN,
            ),
            DefaultCategory("Family Support", AccountClass.EXPENSE, "family", colorArgb = PINK),
            DefaultCategory("Education", AccountClass.EXPENSE, "school", colorArgb = VIOLET),
            DefaultCategory("Shopping", AccountClass.EXPENSE, "shopping", colorArgb = PURPLE),
            DefaultCategory("Personal Care", AccountClass.EXPENSE, "spa", colorArgb = PINK),
            DefaultCategory("Entertainment & Subscriptions", AccountClass.EXPENSE, "movie", colorArgb = VIOLET),
            DefaultCategory("Gifts & Donations", AccountClass.EXPENSE, "gift", colorArgb = RED),
            DefaultCategory("Fees & Charges", AccountClass.EXPENSE, "fee", colorArgb = BROWN),
        )
}
