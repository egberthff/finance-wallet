package ph.financewallet.app.ui

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.categories.CategoriesViewModel
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.report.RangeKind
import ph.financewallet.domain.report.ReportRanges
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Category editing (ANALYSIS.md blueprint C): rename, icon and colour, and sibling-scoped reordering. */
@RunWith(RobolectricTestRunner::class)
class CategoriesViewModelTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private lateinit var vm: CategoriesViewModel
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
            vm = CategoriesViewModel(s)
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity: ${report.failures}", report.isHealthy)
        }

    private suspend fun category(
        name: String,
        parentId: String? = null,
        cls: AccountClass = AccountClass.EXPENSE,
    ) = s.categories.create(name, cls, parentId).getOrThrow()

    private fun names(cls: AccountClass = AccountClass.EXPENSE) =
        vm.state.value.shown
            .filter { it.accountClass == cls.name }
            .map { it.name }

    @Test
    fun `rename shows on old transactions because names are joined, not copied`() =
        runBlocking<Unit> {
            val bpi =
                s.ledger
                    .createAccount(NewAccount("BPI", FinancialAccountType.BANK, CurrencyCode.PHP, LocalDate.of(2026, 11, 1), p(500_000)))
                    .getOrThrow()
            val food = category("Food")
            s.ledger
                .record(
                    TxnHeader(LocalDate.of(2026, 11, 2), "old"),
                ) { t, _ -> t.expense(bpi, listOf(Split(food, p(12_000)))) }
                .getOrThrow()

            assertNull(vm.edit(food, "Meals", "restaurant", 0xFFE53935L))

            val row =
                vm.state.value.categories
                    .single { it.id == food }
            assertEquals(Triple("Meals", "restaurant", 0xFFE53935L), Triple(row.name, row.icon, row.colorArgb))
            val item =
                s.transactions
                    .page()
                    .items
                    .single { it.type.name == "EXPENSE" }
            assertEquals(listOf("Meals"), item.categoryNames)
            assertEquals(listOf(food), item.categoryIds)
            val report = s.reports.spendingByCategory(ReportRanges.containing(RangeKind.MONTH, LocalDate.of(2026, 11, 2)), CurrencyCode.PHP)
            assertEquals("Meals" to p(12_000), report.single { it.categoryId == food }.let { it.name to it.total })
        }

    @Test
    fun `edit errors map to messages the dialog shows under the name field`() =
        runBlocking<Unit> {
            val food = category("Food")
            category("Transport")
            assertEquals(R.string.err_name_length, vm.edit(food, "  ", "restaurant", 0xFF607D8BL))
            assertEquals(R.string.err_category_name_taken, vm.edit(food, "transport", "restaurant", 0xFF607D8BL))
            // A change of case on its own name is allowed (the row itself is excluded from the uniqueness check).
            assertNull(vm.edit(food, "FOOD", "restaurant", 0xFF607D8BL))
        }

    @Test
    fun `reorder moves within siblings and saves only on finish`() =
        runBlocking<Unit> {
            category("Salary", cls = AccountClass.INCOME)
            val a = category("A")
            category("B")
            val c = category("C")
            val x = category("X", parentId = a)
            val y = category("Y", parentId = a)
            vm.reload()
            assertEquals(listOf("A", "B", "C", "X", "Y"), names())

            vm.startReorder()
            vm.move(c, up = true)
            vm.move(a, up = true) // already first among its siblings: no change
            vm.move(y, up = true) // children move among themselves only
            vm.move(x, up = false) // already last after the previous move: no change
            assertEquals(listOf("A", "C", "B", "Y", "X"), names())
            // Nothing is stored until Done.
            assertEquals(
                listOf("A", "B", "C", "X", "Y"),
                s.categories
                    .categories()
                    .filter { it.accountClass == "EXPENSE" }
                    .map { it.name },
            )

            assertNull(vm.finishReorder())
            assertNull(vm.state.value.reorderDraft)
            assertEquals(listOf("A", "C", "B", "Y", "X"), names())
            assertEquals(listOf("Salary"), names(AccountClass.INCOME))
        }

    @Test
    fun `finishing reorder without moves writes nothing`() =
        runBlocking<Unit> {
            val a = category("A")
            category("B")
            vm.reload()
            val versionBefore = checkNotNull(db.ledgerDao().category(a)).version
            vm.startReorder()
            assertNull(vm.finishReorder())
            assertEquals(versionBefore, checkNotNull(db.ledgerDao().category(a)).version)
        }

    @Test
    fun `a reorder in progress survives a reload and can be cancelled without saving`() =
        runBlocking<Unit> {
            category("A")
            val b = category("B")
            vm.reload()
            vm.startReorder()
            vm.move(b, up = true)
            assertTrue(vm.state.value.reorderChanged)

            vm.reload() // e.g. the screen is re-entered or recreated
            assertEquals(listOf("B", "A"), names())
            assertTrue(vm.state.value.reorderChanged)

            vm.cancelReorder()
            assertNull(vm.state.value.reorderDraft)
            assertEquals(listOf("A", "B"), names())
            assertEquals(listOf("A", "B"), s.categories.categories().map { it.name })
        }

    @Test
    fun `new categories are created with the chosen icon and colour`() =
        runBlocking<Unit> {
            assertNull(vm.create("Pets", AccountClass.EXPENSE, null, "heart", 0xFFD81B60L))
            val row =
                vm.state.value.categories
                    .single { it.name == "Pets" }
            assertEquals("heart" to 0xFFD81B60L, row.icon to row.colorArgb)
        }

    @Test
    fun `the colour offer shows for an old seed and disappears once applied`() =
        runBlocking<Unit> {
            s.categories.seedDefaults().getOrThrow()
            vm.reload()
            assertEquals(false, vm.state.value.suggestedColorsAvailable) // a new seed already has colours
            s.categories.categories().forEach { row ->
                val c = checkNotNull(db.ledgerDao().category(row.id))
                db.accountDao().updateCategory(c.copy(colorArgb = 0xFF607D8BL))
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM app_meta WHERE `key` = 'seed:category-colors:v1'")
            vm.reload()
            assertTrue(vm.state.value.suggestedColorsAvailable)
            assertNull(vm.answerSuggestedColors(apply = true))
            assertEquals(false, vm.state.value.suggestedColorsAvailable)
        }

    @Test
    fun `archive moves a category to the archived list and restore (undo) brings it back`() =
        runBlocking<Unit> {
            val food = category("Food")
            vm.reload()
            assertNull(vm.archive(food))
            assertEquals(emptyList<String>(), names())
            assertEquals(
                listOf("Food"),
                vm.state.value.archived
                    .map { it.name },
            )

            assertNull(vm.unarchive(food)) // the snackbar's Undo calls this
            assertEquals(listOf("Food"), names())
            assertTrue(
                vm.state.value.archived
                    .isEmpty(),
            )
            assertEquals(R.string.err_not_archived, vm.unarchive(food))
        }

    @Test
    fun `adding an archived name offers that category for restore instead`() =
        runBlocking<Unit> {
            val food = category("Food")
            vm.archive(food)
            val (id, error) = vm.createReturningId("FOOD", AccountClass.EXPENSE, null, "category", 0xFF607D8BL)
            assertNull(id)
            assertEquals(R.string.err_category_name_archived, error)
            assertEquals(food, vm.archivedNamed("FOOD", AccountClass.EXPENSE, null)?.id)
        }

    @Test
    fun `a subcategory cannot be restored before its main category`() =
        runBlocking<Unit> {
            val food = category("Food")
            val dining = category("Dining", parentId = food)
            vm.archive(dining)
            vm.archive(food)
            assertEquals(R.string.err_parent_archived, vm.unarchive(dining))
            assertNull(vm.unarchive(food))
            assertNull(vm.unarchive(dining))
        }

    @Test
    fun `suggested categories can be added again and report how many were added`() =
        runBlocking<Unit> {
            category("Pets")
            val first = vm.seed()
            assertNull(first.error)
            assertTrue(first.created > 0)
            assertEquals(0, vm.seed().created)
        }
}
