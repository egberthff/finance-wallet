package ph.financewallet.data.account

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.CategoryEntity
import ph.financewallet.data.db.entity.LedgerAccountEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.ledger.AccountClass

/**
 * Category management UC-14 (CAT-01/02/04): create, edit, archive/unarchive, reorder, and the optional default
 * Philippine seed (Phase 4 step 4.9). Rules: income or expense only; names unique among siblings (case-insensitive);
 * one level of subcategories; archived categories stay on history and cannot receive new transactions.
 */
class CategoryService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val dao get() = db.accountDao()
    private val ledgerDao get() = db.ledgerDao()

    suspend fun categories(includeArchived: Boolean = false): List<CategoryRow> = dao.categories(includeArchived)

    suspend fun create(
        name: String,
        accountClass: AccountClass,
        parentId: String? = null,
        icon: String = DEFAULT_ICON,
        colorArgb: Long = DEFAULT_COLOR,
    ): DomainResult<String> = db.validatedWrite { insert(name, accountClass, parentId, icon, colorArgb, seed = false) }

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
                if (c.sortOrder !=
                    i
                ) {
                    dao.updateCategory(c.copy(sortOrder = i, version = c.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()))
                }
            }
        }

    /**
     * Adds the default PH categories once (CAT-02). Skippable at onboarding; never forced. Categories whose name already
     * exists among their siblings are skipped, so a user's own categories are never duplicated or renamed.
     * Returns the number of categories created.
     */
    suspend fun seedDefaults(): DomainResult<Int> =
        db.validatedWrite {
            if (dao.meta(SEED_KEY) != null) return@validatedWrite 0
            var created = 0
            DefaultCategories.ALL.forEach { group ->
                val parentId = findOrCreate(group.name, group.accountClass, null, group.icon) { created++ }
                group.children.forEach { child -> findOrCreate(child, group.accountClass, parentId, group.icon) { created++ } }
            }
            dao.setMeta(SEED_KEY, ctx.nowMillis().toString())
            created
        }

    // ---- internals ----------------------------------------------------------------------------------------------

    private suspend fun ValidationScope.findOrCreate(
        name: String,
        cls: AccountClass,
        parentId: String?,
        icon: String,
        onCreated: () -> Unit,
    ): String {
        val existing =
            dao.categories(includeArchived = true).firstOrNull {
                it.accountClass == cls.name && it.parentId == parentId && it.name.trim().equals(name, ignoreCase = true)
            }
        if (existing != null) return existing.id
        onCreated()
        return insert(name, cls, parentId, icon, DEFAULT_COLOR, seed = true)
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
        ensure(dao.siblingsNamed(accountClass, parentId, name, excludingId) == 0, "name", "CATEGORY_NAME_TAKEN")
    }

    private suspend fun ValidationScope.load(categoryId: String) =
        (ledgerDao.ledgerAccount(categoryId) ?: notFound("category", categoryId)) to
            (ledgerDao.category(categoryId) ?: notFound("category", categoryId))

    companion object {
        const val DEFAULT_ICON = "category"
        const val DEFAULT_COLOR = 0xFF607D8BL
        private const val MAX_NAME = 80
        private const val SEED_KEY = "seed:categories:v1"
    }
}

/** A default top-level category and its subcategories. */
data class DefaultCategory(
    val name: String,
    val accountClass: AccountClass,
    val icon: String,
    val children: List<String> = emptyList(),
)

/** Default categories for Philippine users (CAT-02). Generic names only; no brand or institution names. */
object DefaultCategories {
    val ALL: List<DefaultCategory> =
        listOf(
            DefaultCategory("Salary", AccountClass.INCOME, "work"),
            DefaultCategory("13th Month Pay", AccountClass.INCOME, "work"),
            DefaultCategory("Bonus", AccountClass.INCOME, "work"),
            DefaultCategory("Freelance", AccountClass.INCOME, "laptop"),
            DefaultCategory("Business", AccountClass.INCOME, "store"),
            DefaultCategory("Remittance Received", AccountClass.INCOME, "family"),
            DefaultCategory("Interest", AccountClass.INCOME, "savings"),
            DefaultCategory("Other Income", AccountClass.INCOME, "add"),
            DefaultCategory("Food", AccountClass.EXPENSE, "restaurant", listOf("Groceries", "Dining Out", "Coffee & Snacks")),
            DefaultCategory(
                "Transportation",
                AccountClass.EXPENSE,
                "commute",
                listOf("Public Transport", "Ride-hailing & Taxi", "Fuel", "Parking & Tolls"),
            ),
            DefaultCategory(
                "Bills & Utilities",
                AccountClass.EXPENSE,
                "receipt",
                listOf("Electricity", "Water", "Internet", "Mobile Load & Postpaid"),
            ),
            DefaultCategory("Housing", AccountClass.EXPENSE, "home", listOf("Rent", "Association Dues", "Repairs")),
            DefaultCategory("Health", AccountClass.EXPENSE, "health", listOf("Medicine", "Consultation", "HMO & Insurance")),
            DefaultCategory(
                "Government Contributions",
                AccountClass.EXPENSE,
                "account_balance",
                listOf("SSS", "PhilHealth", "Pag-IBIG", "Taxes"),
            ),
            DefaultCategory("Family Support", AccountClass.EXPENSE, "family"),
            DefaultCategory("Education", AccountClass.EXPENSE, "school"),
            DefaultCategory("Shopping", AccountClass.EXPENSE, "shopping"),
            DefaultCategory("Personal Care", AccountClass.EXPENSE, "spa"),
            DefaultCategory("Entertainment & Subscriptions", AccountClass.EXPENSE, "movie"),
            DefaultCategory("Gifts & Donations", AccountClass.EXPENSE, "gift"),
            DefaultCategory("Fees & Charges", AccountClass.EXPENSE, "fee"),
        )
}
