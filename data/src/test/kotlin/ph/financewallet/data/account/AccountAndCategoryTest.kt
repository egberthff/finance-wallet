package ph.financewallet.data.account

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.data.budget.GoalDetails
import ph.financewallet.data.budget.GoalService
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.errorOrNull
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.id.UuidV7Generator
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** UC-02/03/04 (accounts), UC-13 (reconcile), UC-14 (categories) and the default seed (4.9). */
@RunWith(RobolectricTestRunner::class)
class AccountAndCategoryTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: LedgerService
    private lateinit var accounts: AccountService
    private lateinit var categories: CategoryService
    private val clock = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneOffset.UTC)
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "test-device")
    private val php = CurrencyCode.PHP
    private val usd = CurrencyCode.of("USD")
    private val nov5 = LocalDate.of(2026, 11, 5)
    private val far = LocalDate.of(2100, 1, 1)
    private var keySeq = 0

    private fun p(minor: Long) = Money(minor, php)

    private fun key() = "k${++keySeq}"

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            ledger = LedgerService(db, ctx)
            accounts = AccountService(db, ctx, ledger)
            categories = CategoryService(db, ctx)
            ledger.ensureSystemAccounts()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity after test: ${report.failures}", report.isHealthy)
        }

    private suspend fun account(
        opening: Long,
        type: FinancialAccountType = FinancialAccountType.EWALLET,
    ) = ledger.createAccount(NewAccount("GCash", type, php, nov5, p(opening))).getOrThrow()

    private suspend fun version(id: String) = checkNotNull(db.ledgerDao().financialAccount(id)).version

    private fun edit(
        opening: Long,
        currency: CurrencyCode = php,
        name: String = "GCash",
    ) = AccountEdit(name = name, currency = currency, openingDate = nov5, openingAmount = Money(opening, currency))

    // ---- UC-02 --------------------------------------------------------------------------------------------------

    @Test
    fun `editing the opening balance goes through revisions, including to zero and back`() =
        runBlocking<Unit> {
            val a = account(500_000)
            accounts.edit(a, version(a), edit(700_000, name = "GCash main")).getOrThrow()
            assertEquals(p(700_000), ledger.balance(a, far))
            val opening = checkNotNull(db.ledgerDao().txnByIdempotencyKey("opening:$a"))
            assertEquals(2, ledger.revisionCount(opening.id))

            accounts.edit(a, version(a), edit(0)).getOrThrow()
            assertEquals(p(0), ledger.balance(a, far))
            accounts.edit(a, version(a), edit(100_000)).getOrThrow()
            assertEquals(p(100_000), ledger.balance(a, far))
            assertEquals("STALE_VERSION", accounts.edit(a, 1, edit(100_000)).errorOrNull()?.code)
            assertEquals(
                "LAST4_FORMAT",
                accounts.edit(a, version(a), edit(100_000).copy(accountNumberLast4 = "12a4")).errorOrNull()?.code,
            )
            assertEquals("GCash", db.ledgerDao().ledgerAccount(a)?.name)
            assertEquals(3, db.accountDao().auditFor(a).size)
        }

    @Test
    fun `currency changes only while the opening balance is the only history`() =
        runBlocking<Unit> {
            val a = account(500_000)
            accounts.edit(a, version(a), edit(10_000, usd)).getOrThrow()
            assertEquals(Money(10_000, usd), ledger.balance(a, far))
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            ledger.record(TxnHeader(nov5, key())) { t, _ -> t.expense(a, listOf(Split(food, Money(100, usd)))) }.getOrThrow()
            assertEquals("CURRENCY_LOCKED", accounts.edit(a, version(a), edit(10_000, php)).errorOrNull()?.code)
            assertEquals("USD", db.ledgerDao().ledgerAccount(a)?.currency) // rolled back
        }

    // ---- UC-03 / UC-04 ------------------------------------------------------------------------------------------

    @Test
    fun `archiving needs acknowledgement for a non-zero balance and blocks new transactions`() =
        runBlocking<Unit> {
            val a = account(500_000)
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            assertEquals("BALANCE_NOT_ZERO", accounts.archive(a).errorOrNull()?.code)
            accounts.archive(a, acknowledgeNonZeroBalance = true).getOrThrow()
            assertEquals(emptyList<AccountRow>(), accounts.accounts())
            assertEquals(1, accounts.accounts(includeArchived = true).size)
            val rejected = ledger.record(TxnHeader(nov5, key())) { t, _ -> t.expense(a, listOf(Split(food, p(1)))) }
            assertEquals("ACCOUNT_ARCHIVED", rejected.errorOrNull()?.code)
            assertEquals(p(500_000), ledger.balance(a, far)) // history intact
            accounts.unarchive(a).getOrThrow()
            ledger.record(TxnHeader(nov5, key())) { t, _ -> t.expense(a, listOf(Split(food, p(1)))) }.getOrThrow()
        }

    @Test
    fun `only an account without any history or links can be deleted`() =
        runBlocking<Unit> {
            val empty = account(0)
            accounts.delete(empty).getOrThrow()
            assertNull(db.ledgerDao().ledgerAccount(empty))
            assertEquals(
                "ACCOUNT_DELETE",
                db
                    .accountDao()
                    .auditFor(empty)
                    .single()
                    .kind,
            )

            val withOpening = account(1)
            assertEquals("ACCOUNT_HAS_HISTORY", accounts.delete(withOpening).errorOrNull()?.code)

            val linked = account(0, FinancialAccountType.SAVINGS)
            GoalService(db, ctx, ledger).createGoal(php, GoalDetails("Laptop", p(1_000)), setOf(linked)).getOrThrow()
            assertEquals("ACCOUNT_HAS_LINKS", accounts.delete(linked).errorOrNull()?.code)
            assertNotNull(db.ledgerDao().ledgerAccount(linked))
        }

    // ---- UC-13 --------------------------------------------------------------------------------------------------

    @Test
    fun `reconciling books the difference as an adjustment, never as spending`() =
        runBlocking<Unit> {
            val a = account(1_000_000)
            val k = key()
            val r = accounts.reconcile(a, nov5, p(985_000), createAdjustment = true, idempotencyKey = k).getOrThrow()
            assertEquals(p(1_000_000), r.calculated)
            assertEquals(p(-15_000), r.difference)
            assertEquals(p(985_000), ledger.balance(a, far))
            val s = ledger.summary(nov5, nov5).getValue(php)
            assertEquals(p(0), s.spending)
            assertEquals(p(-15_000), s.adjustments)
            assertEquals(r, accounts.reconcile(a, nov5, p(985_000), createAdjustment = true, idempotencyKey = k).getOrThrow())
            assertEquals(1, db.accountDao().reconciliations(a).size)
            val reconciled = checkNotNull(accounts.reconciledBalance(a))
            assertEquals(p(985_000) to true, reconciled.statementBalance to reconciled.adjusted)
            assertEquals("DATE_IN_FUTURE", accounts.reconcile(a, nov5.plusDays(1), p(1), true, key()).errorOrNull()?.code)
        }

    @Test
    fun `credit card statements are entered as the amount owed`() =
        runBlocking<Unit> {
            val card = account(250_000, FinancialAccountType.CREDIT_CARD) // owes 2,500
            val r = accounts.reconcile(card, nov5, p(300_000), createAdjustment = false, idempotencyKey = key()).getOrThrow()
            assertEquals(p(-250_000), r.calculated)
            assertEquals(p(-50_000), r.difference) // owes 500 more than recorded
            assertNull(r.adjustmentTxnId)
            assertEquals(p(-250_000), ledger.balance(card, far))
            assertEquals(p(300_000), accounts.reconciledBalance(card)?.statementBalance)
        }

    // ---- UC-14 --------------------------------------------------------------------------------------------------

    @Test
    fun `category names are unique among siblings and only one level deep`() =
        runBlocking<Unit> {
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            assertEquals("CATEGORY_NAME_TAKEN", categories.create(" food ", AccountClass.EXPENSE).errorOrNull()?.code)
            categories.create("Food", AccountClass.INCOME).getOrThrow() // different class: allowed
            val groceries = categories.create("Groceries", AccountClass.EXPENSE, food).getOrThrow()
            val other = categories.create("Other", AccountClass.EXPENSE).getOrThrow()
            categories.create("Groceries", AccountClass.EXPENSE, other).getOrThrow() // different parent: allowed
            assertEquals("CATEGORY_TOO_DEEP", categories.create("Veg", AccountClass.EXPENSE, groceries).errorOrNull()?.code)
            assertEquals("CATEGORY_WRONG_CLASS", categories.create("Tip", AccountClass.INCOME, food).errorOrNull()?.code)
            assertEquals("CATEGORY_NAME_TAKEN", categories.edit(other, "FOOD", "x", 0).errorOrNull()?.code)
            categories.edit(other, "Misc", "more", 1).getOrThrow()
            assertEquals("Misc", db.ledgerDao().ledgerAccount(other)?.name)
        }

    @Test
    fun `archived categories keep history, reject new spending, and parents archive after children`() =
        runBlocking<Unit> {
            val a = account(100_000)
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            val groceries = categories.create("Groceries", AccountClass.EXPENSE, food).getOrThrow()
            ledger.record(TxnHeader(nov5, key())) { t, _ -> t.expense(a, listOf(Split(groceries, p(5_000)))) }.getOrThrow()
            assertEquals("CATEGORY_HAS_CHILDREN", categories.archive(food).errorOrNull()?.code)
            categories.archive(groceries).getOrThrow()
            categories.archive(food).getOrThrow()
            assertEquals("PARENT_ARCHIVED", categories.unarchive(groceries).errorOrNull()?.code)
            val rejected = ledger.record(TxnHeader(nov5, key())) { t, _ -> t.expense(a, listOf(Split(groceries, p(1)))) }
            assertEquals("CATEGORY_ARCHIVED", rejected.errorOrNull()?.code)
            assertEquals(p(5_000), ledger.summary(nov5, nov5).getValue(php).spending)
            assertEquals(2, categories.categories(includeArchived = true).size)
            assertEquals(0, categories.categories().size)
        }

    @Test
    fun `reorder sets the order of exactly the siblings`() =
        runBlocking<Unit> {
            val ids = listOf("A", "B", "C").map { categories.create(it, AccountClass.EXPENSE).getOrThrow() }
            categories.reorder(AccountClass.EXPENSE, null, ids.reversed()).getOrThrow()
            assertEquals(listOf("C", "B", "A"), categories.categories().map { it.name })
            assertEquals("ORDER_MISMATCH", categories.reorder(AccountClass.EXPENSE, null, ids.take(2)).errorOrNull()?.code)
        }

    @Test
    fun `default PH seed runs once and never duplicates the user's own categories`() =
        runBlocking<Unit> {
            categories.create("salary", AccountClass.INCOME).getOrThrow()
            val created = categories.seedDefaults().getOrThrow()
            val expected = DefaultCategories.ALL.size + DefaultCategories.ALL.sumOf { it.children.size } - 1
            assertEquals(expected, created)
            assertEquals(0, categories.seedDefaults().getOrThrow())
            val all = categories.categories()
            assertEquals(1, all.count { it.name.equals("salary", ignoreCase = true) })
            assertTrue(all.filter { it.parentId != null }.all { child -> all.single { it.id == child.parentId }.parentId == null })
            assertEquals(expected, all.count { it.isDefaultSeed })
        }

    @Test
    fun `seeded categories get their group colour, and children share it`() =
        runBlocking<Unit> {
            categories.seedDefaults().getOrThrow()
            val all = categories.categories()
            val food = all.single { it.name == "Food" && it.parentId == null }
            assertEquals(0xFFE65100L, food.colorArgb)
            assertTrue(all.filter { it.parentId == food.id }.all { it.colorArgb == food.colorArgb })
            // A seed made with colours needs no colour offer.
            assertTrue(categories.suggestedColors().isEmpty())
        }

    @Test
    fun `suggested colours apply once, only to seeded categories still in the default colour`() =
        runBlocking<Unit> {
            categories.seedDefaults().getOrThrow()
            // Simulate a seed made before colours existed: every row slate, colour offer not yet made.
            categories.categories().forEach { row ->
                val c = checkNotNull(db.ledgerDao().category(row.id))
                db.accountDao().updateCategory(c.copy(colorArgb = CategoryService.DEFAULT_COLOR))
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM app_meta WHERE `key` = 'seed:category-colors:v1'")
            val food = categories.categories().single { it.name == "Food" && it.parentId == null }
            categories.edit(food.id, "Food", food.icon, 0xFF1E88E5L).getOrThrow() // the user's own choice
            val own = categories.create("Pets", AccountClass.EXPENSE).getOrThrow() // not seeded: never touched
            val offered = categories.suggestedColors()
            val seededCount = categories.categories().count { it.isDefaultSeed }
            assertEquals(seededCount - 1, offered.size)

            assertEquals(seededCount - 1, categories.applySuggestedColors().getOrThrow())

            val after = categories.categories().associateBy { it.id }
            assertEquals(0xFF1E88E5L, after.getValue(food.id).colorArgb)
            assertEquals(CategoryService.DEFAULT_COLOR, after.getValue(own).colorArgb)
            assertEquals(0xFF388E3CL, after.values.single { it.name == "Salary" }.colorArgb)
            assertTrue(categories.suggestedColors().isEmpty())
            assertEquals(0, categories.applySuggestedColors().getOrThrow())
        }

    @Test
    fun `dismissing the colour offer changes nothing and hides it`() =
        runBlocking<Unit> {
            categories.seedDefaults().getOrThrow()
            categories.categories().forEach { row ->
                val c = checkNotNull(db.ledgerDao().category(row.id))
                db.accountDao().updateCategory(c.copy(colorArgb = CategoryService.DEFAULT_COLOR))
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM app_meta WHERE `key` = 'seed:category-colors:v1'")
            assertTrue(categories.suggestedColors().isNotEmpty())
            categories.dismissSuggestedColors().getOrThrow()
            assertTrue(categories.suggestedColors().isEmpty())
            assertTrue(categories.categories().all { it.colorArgb == CategoryService.DEFAULT_COLOR })
        }

    // ---- blueprint A: archive, restore, name reuse, usage, audit -------------------------------------------------

    @Test
    fun `an archived name offers a restore, and a restored name is taken again`() =
        runBlocking<Unit> {
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            categories.archive(food).getOrThrow()
            assertEquals("CATEGORY_NAME_ARCHIVED", categories.create("food", AccountClass.EXPENSE).errorOrNull()?.code)
            assertEquals(food, categories.archivedNamed(" FOOD ", AccountClass.EXPENSE, null)?.id)
            // The same name under another parent, or in the other class, is a different sibling group.
            assertNotNull(categories.create("Food", AccountClass.INCOME).getOrThrow())

            categories.unarchive(food).getOrThrow()
            assertEquals("CATEGORY_NAME_TAKEN", categories.create("Food", AccountClass.EXPENSE).errorOrNull()?.code)
            assertNull(categories.archivedNamed("Food", AccountClass.EXPENSE, null))
        }

    @Test
    fun `name comparison folds Unicode case but keeps accents`() =
        runBlocking<Unit> {
            categories.create("Piñata", AccountClass.EXPENSE).getOrThrow()
            assertEquals("CATEGORY_NAME_TAKEN", categories.create("PIÑATA", AccountClass.EXPENSE).errorOrNull()?.code)
            assertEquals("CATEGORY_NAME_TAKEN", categories.create("  piñata ", AccountClass.EXPENSE).errorOrNull()?.code)
            categories.create("Año", AccountClass.EXPENSE).getOrThrow()
            assertNotNull(categories.create("Ano", AccountClass.EXPENSE).getOrThrow())
        }

    @Test
    fun `usage counts budgets, bills, rules and transactions of a category and its children`() =
        runBlocking<Unit> {
            val gcash = account(500_000)
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            val groceries = categories.create("Groceries", AccountClass.EXPENSE, food).getOrThrow()
            assertEquals(CategoryUsage(0, 0, 0, 0), categories.usage(food))

            ledger.record(TxnHeader(nov5, key())) { t, _ -> t.expense(gcash, listOf(Split(groceries, p(1_000)))) }.getOrThrow()
            ledger.record(TxnHeader(nov5, key())) { t, _ -> t.expense(gcash, listOf(Split(food, p(2_000)))) }.getOrThrow()
            ph.financewallet.data.budget
                .BudgetService(db, ctx)
                .createBudget(
                    ph.financewallet.data.budget.NewBudget(
                        php,
                        ph.financewallet.domain.budget.PeriodRule
                            .Monthly(nov5, 5),
                        ph.financewallet.data.budget.BudgetSettings(
                            "Personal",
                            ph.financewallet.domain.allocation.IncomeMode.MANUAL,
                            null,
                            listOf(
                                ph.financewallet.data.budget
                                    .BudgetCategorySpec(food),
                            ),
                        ),
                    ),
                ).getOrThrow()
            ph.financewallet.data.advanced
                .RecurringService(db, ctx, ledger)
                .createRule(
                    ph.financewallet.data.advanced.RuleSpec(
                        ph.financewallet.data.advanced.RuleKind.BILL,
                        "Groceries delivery",
                        p(150_000),
                        ph.financewallet.domain.recurring.Recurrence
                            .Monthly(nov5, 20),
                        accountId = gcash,
                        categoryId = groceries,
                    ),
                ).getOrThrow()
            ph.financewallet.data.imports
                .CategoryRuleService(db, ctx)
                .create(
                    ph.financewallet.data.imports
                        .NewRule("market", groceries),
                ).getOrThrow()

            assertEquals(CategoryUsage(budgets = 1, recurringRules = 1, categoryRules = 1, transactions = 2), categories.usage(food))
            assertEquals(CategoryUsage(budgets = 0, recurringRules = 1, categoryRules = 1, transactions = 1), categories.usage(groceries))
        }

    @Test
    fun `every category change writes an audit event`() =
        runBlocking<Unit> {
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            val transport = categories.create("Transport", AccountClass.EXPENSE).getOrThrow()
            categories.edit(food, "Meals", "restaurant", 0xFFE65100L).getOrThrow()
            categories.reorder(AccountClass.EXPENSE, null, listOf(transport, food)).getOrThrow()
            categories.archive(food).getOrThrow()
            categories.unarchive(food).getOrThrow()

            val kinds = db.accountDao().auditFor(food).map { it.kind }
            assertEquals(listOf("category.create", "category.edit", "category.archive", "category.unarchive"), kinds)
            val edit = db.accountDao().auditFor(food).single { it.kind == "category.edit" }
            assertTrue(checkNotNull(edit.detailJson).contains("\"name.before\":\"Food\""))
            assertTrue(checkNotNull(edit.detailJson).contains("\"name.after\":\"Meals\""))
            assertEquals(listOf("category.reorder"), db.accountDao().auditFor(AccountClass.EXPENSE.name).map { it.kind })
        }

    @Test
    fun `the seed can run again and adds only missing starter categories`() =
        runBlocking<Unit> {
            categories.seedDefaults().getOrThrow()
            val food = categories.categories().single { it.name == "Food" && it.parentId == null }
            val dining = categories.categories().single { it.name == "Dining Out" }
            categories.archive(dining.id).getOrThrow()
            categories.edit(food.id, "Meals", food.icon, food.colorArgb).getOrThrow() // renamed: "Food" is missing now

            // "Food" is missing (renamed), so a new "Food" group with its 3 subcategories is added; nothing else.
            assertEquals(4, categories.seedDefaults().getOrThrow())
            val all = categories.categories(includeArchived = true)
            assertEquals(1, all.count { it.name == "Dining Out" && it.parentId == food.id }) // archived one not re-created
            assertTrue(all.any { it.name == "Food" && it.parentId == null })
        }

    /** Regression: after an opening balance went to zero and back, the next edit used to add a second opening. */
    @Test
    fun `an opening balance re-created after zero is edited in place, never duplicated`() =
        runBlocking<Unit> {
            val a = account(500_000)
            accounts.edit(a, version(a), edit(0)).getOrThrow()
            accounts.edit(a, version(a), edit(100_000)).getOrThrow()
            accounts.edit(a, version(a), edit(250_000)).getOrThrow()
            accounts.edit(a, version(a), edit(300_000)).getOrThrow()
            assertEquals(p(300_000), ledger.balance(a, far))
            val live =
                db.openHelper.readableDatabase
                    .query("SELECT COUNT(*) FROM txn WHERE type = 'OPENING_BALANCE' AND deleted_at IS NULL")
                    .use { c -> c.moveToFirst().let { c.getInt(0) } }
            assertEquals(1, live)

            val state = checkNotNull(accounts.editState(a))
            assertEquals(p(300_000), state.edit.openingAmount)
            assertEquals(version(a), state.version)
            assertEquals(false, state.currencyLocked)
            val food = categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            ledger.record(TxnHeader(nov5, key())) { t, _ -> t.expense(a, listOf(Split(food, p(1)))) }.getOrThrow()
            assertEquals(true, checkNotNull(accounts.editState(a)).currencyLocked)
        }
}
