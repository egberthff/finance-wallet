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
import ph.financewallet.app.ui.transactions.EntryKind
import ph.financewallet.app.ui.transactions.EntryViewModel
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Fast entry: remembered defaults (E), edits with archived choices (B), and the "More details" fields (G). */
@RunWith(RobolectricTestRunner::class)
class EntryViewModelTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private lateinit var gcash: String
    private lateinit var maya: String
    private lateinit var food: String
    private lateinit var fare: String
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
            val opened = LocalDate.of(2026, 11, 1)
            gcash =
                s.ledger.createAccount(NewAccount("GCash", FinancialAccountType.EWALLET, CurrencyCode.PHP, opened, p(500_000))).getOrThrow()
            maya =
                s.ledger.createAccount(NewAccount("Maya", FinancialAccountType.EWALLET, CurrencyCode.PHP, opened, p(500_000))).getOrThrow()
            food = s.categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            fare = s.categories.create("Fare", AccountClass.EXPENSE).getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity: ${report.failures}", report.isHealthy)
        }

    private suspend fun newVm() = EntryViewModel(s).also { it.loadOptions() }

    private suspend fun saveExpense(
        account: String,
        category: String,
        amount: String = "100",
    ): String? {
        val vm = newVm()
        vm.edit { it.copy(kind = EntryKind.EXPENSE, amount = amount, accountId = account, categoryId = category) }
        assertNull(vm.save())
        return s.transactions
            .page()
            .items
            .first { it.type.name == "EXPENSE" }
            .id
    }

    @Test
    fun `a new entry starts with the last used account and category`() =
        runBlocking<Unit> {
            saveExpense(maya, fare)
            val vm = newVm()
            assertEquals(maya, vm.form.value.accountId)
            assertEquals(fare, vm.form.value.categoryId)
            // Income has no remembered category yet; switching kind clears the expense category.
            vm.setKind(EntryKind.INCOME)
            assertNull(vm.form.value.categoryId)
            vm.setKind(EntryKind.EXPENSE)
            assertEquals(fare, vm.form.value.categoryId)
            // Recently used categories are offered first in the picker.
            assertEquals(listOf(fare), vm.options.value.recent(EntryKind.EXPENSE))
        }

    @Test
    fun `an archived remembered category is not preselected`() =
        runBlocking<Unit> {
            saveExpense(gcash, fare)
            s.categories.archive(fare).getOrThrow()
            val vm = newVm()
            assertNull(vm.form.value.categoryId)
            assertEquals(gcash, vm.form.value.accountId)
        }

    @Test
    fun `edits do not change what is remembered`() =
        runBlocking<Unit> {
            val id = checkNotNull(saveExpense(gcash, food))
            val vm = newVm()
            assertTrue(vm.loadExisting(id))
            vm.edit { it.copy(accountId = maya, categoryId = fare) }
            assertNull(vm.save())
            val next = newVm()
            assertEquals(gcash, next.form.value.accountId)
            assertEquals(food, next.form.value.categoryId)
        }

    @Test
    fun `an expense whose category and account were archived can still be edited (B)`() =
        runBlocking<Unit> {
            val id = checkNotNull(saveExpense(gcash, food, "250"))
            s.categories.archive(food).getOrThrow()
            s.accounts.archive(gcash, acknowledgeNonZeroBalance = true).getOrThrow()

            val vm = newVm()
            assertTrue(vm.loadExisting(id))
            val options = vm.options.value
            assertEquals(food, vm.form.value.categoryId)
            assertTrue(options.categories(EntryKind.EXPENSE).single { it.id == food }.archived)
            assertTrue(options.accounts.single { it.id == gcash }.archived)
            vm.edit { it.copy(amount = "300") }
            assertNull(vm.save())
            val item =
                s.transactions
                    .page()
                    .items
                    .single { it.id == id }
            assertEquals(p(-30_000), item.amount)
            assertEquals(listOf("CREATE", "UPDATE"), db.ledgerDao().revisions(id).map { it.changeKind })
            // A new entry never offers the archived ones.
            val fresh = newVm()
            assertTrue(
                fresh.options.value
                    .categories(EntryKind.EXPENSE)
                    .none { it.id == food },
            )
            assertTrue(
                fresh.options.value.accounts
                    .none { it.id == gcash },
            )
        }

    @Test
    fun `merchant, reference and notes are stored and survive an edit (G)`() =
        runBlocking<Unit> {
            val vm = newVm()
            vm.edit {
                it.copy(
                    amount = "80",
                    accountId = gcash,
                    categoryId = food,
                    merchant = " Jollibee ",
                    reference = "OR-123",
                    notes = "team lunch",
                )
            }
            assertNull(vm.save())
            val id =
                s.transactions
                    .page()
                    .items
                    .first { it.type.name == "EXPENSE" }
                    .id
            val editor = newVm()
            assertTrue(editor.loadExisting(id))
            assertEquals(Triple("Jollibee", "OR-123", "team lunch"), editor.form.value.let { Triple(it.merchant, it.reference, it.notes) })
            editor.edit { it.copy(amount = "90") }
            assertNull(editor.save())
            val header = checkNotNull(s.ledger.editable(id)).header
            assertEquals(Triple("Jollibee", "OR-123", "team lunch"), Triple(header.merchant, header.referenceNo, header.notes))
        }
}
