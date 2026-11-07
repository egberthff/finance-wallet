package ph.financewallet.app.ui

import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.transactions.TransactionDetailViewModel
import ph.financewallet.app.ui.transactions.TransactionsViewModel
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnFilter
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Transaction list and detail: live reload (F), filters (H), detail, refunds and delete (G). */
@RunWith(RobolectricTestRunner::class)
class TransactionsFlowTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private lateinit var gcash: String
    private lateinit var maya: String
    private lateinit var food: String
    private lateinit var groceries: String
    private lateinit var fare: String
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)
    private val nov = LocalDate.of(2026, 11, 1)
    private var keys = 0

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
            gcash =
                s.ledger.createAccount(NewAccount("GCash", FinancialAccountType.EWALLET, CurrencyCode.PHP, nov, p(1_000_000))).getOrThrow()
            maya =
                s.ledger.createAccount(NewAccount("Maya", FinancialAccountType.EWALLET, CurrencyCode.PHP, nov, p(1_000_000))).getOrThrow()
            food = s.categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            groceries = s.categories.create("Groceries", AccountClass.EXPENSE, food).getOrThrow()
            fare = s.categories.create("Fare", AccountClass.EXPENSE).getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity: ${report.failures}", report.isHealthy)
        }

    private suspend fun spend(
        account: String,
        category: String,
        minor: Long,
        date: LocalDate = LocalDate.of(2026, 11, 10),
        description: String = "",
    ) = s.ledger
        .record(TxnHeader(date, "k${++keys}", description = description)) { t, _ -> t.expense(account, listOf(Split(category, p(minor)))) }
        .getOrThrow()

    /** Lets the main looper run (debounce, collectors) until [done] or a few seconds pass. */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(STEP_MS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS * 3))
        }
    }

    @Test
    fun `a write anywhere shows in an open transaction list without a refresh (F)`() =
        runBlocking<Unit> {
            val vm = TransactionsViewModel(s)
            waitFor { !vm.state.value.loading }
            val before = vm.state.value.items.size
            spend(gcash, fare, 5_000, description = "Jeep")
            waitFor { vm.state.value.items.size > before }
            assertTrue(
                vm.state.value.items
                    .any { it.description == "Jeep" },
            )
        }

    @Test
    fun `after loading more, an edit on page 2 keeps every loaded row (F, R-1)`() =
        runBlocking<Unit> {
            repeat(60) { spend(gcash, fare, 100L + it, date = LocalDate.of(2026, 11, 1 + it % 19)) }
            val vm = TransactionsViewModel(s)
            vm.reload()
            vm.loadMore()
            waitFor { vm.state.value.items.size >= 62 }
            val loaded = vm.state.value.items.size
            val onPage2 = vm.state.value.items[55]
            val editable = checkNotNull(s.ledger.editable(onPage2.id))
            s.ledger
                .edit(onPage2.id, editable.version, editable.header.copy(description = "fixed")) { t, _ ->
                    t.expense(gcash, listOf(Split(fare, p(999))))
                }.getOrThrow()
            vm.reload()
            assertEquals(loaded, vm.state.value.items.size)
            assertEquals(
                "fixed",
                vm.state.value.items
                    .single { it.id == onPage2.id }
                    .description,
            )
        }

    @Test
    fun `filters by account, by category with its subcategories, and by dates match the report drill-down (H)`() =
        runBlocking<Unit> {
            spend(gcash, groceries, 1_000, LocalDate.of(2026, 11, 3))
            spend(maya, food, 2_000, LocalDate.of(2026, 11, 5))
            spend(gcash, fare, 3_000, LocalDate.of(2026, 11, 7))
            spend(maya, groceries, 4_000, LocalDate.of(2026, 11, 12))
            val vm = TransactionsViewModel(s)

            vm.setFilter(categoryId = food) // Food includes Groceries
            val foodIds =
                vm.state.value.items
                    .map { it.id }
                    .toSet()
            assertEquals(3, foodIds.size)
            assertEquals(
                s.transactions
                    .page(filter = TxnFilter(food))
                    .items
                    .map { it.id }
                    .toSet(),
                foodIds,
            )

            vm.setFilter(accountId = maya)
            assertEquals(2, vm.state.value.items.size) // Maya and Food: 5 Nov and 12 Nov

            vm.setFilter(accountId = null, from = LocalDate.of(2026, 11, 4), to = LocalDate.of(2026, 11, 10))
            assertEquals(
                listOf(LocalDate.of(2026, 11, 5)),
                vm.state.value.items
                    .map { it.date },
            )

            vm.clearFilters()
            assertTrue(!vm.state.value.filtered)
            assertEquals(
                4,
                vm.state.value.items
                    .count { it.type.name == "EXPENSE" },
            )

            val deepLink = TransactionsViewModel(s, initialAccountId = gcash)
            deepLink.reload()
            assertTrue(
                deepLink.state.value.items
                    .isNotEmpty(),
            )
            assertTrue(
                deepLink.state.value.items
                    .all { it.accountName == "GCash" },
            )
        }

    @Test
    fun `detail shows fields and history, records a linked refund, and refuses too large a refund (G)`() =
        runBlocking<Unit> {
            val id =
                s.ledger
                    .record(TxnHeader(nov.plusDays(9), "k${++keys}", description = "Shoes", merchant = "Mall", notes = "gift")) { t, _ ->
                        t.expense(gcash, listOf(Split(fare, p(50_000))))
                    }.getOrThrow()
            val editable = checkNotNull(s.ledger.editable(id))
            s.ledger
                .edit(id, editable.version, editable.header.copy(description = "Shoes (sale)")) { t, _ ->
                    t.expense(gcash, listOf(Split(fare, p(50_000))))
                }.getOrThrow()

            val vm = TransactionDetailViewModel(s, id)
            vm.load()
            val d = checkNotNull(vm.state.value.details)
            assertEquals(Triple("Shoes (sale)", "Mall", "gift"), Triple(d.description, d.merchant, d.notes))
            assertEquals(listOf("CREATE", "UPDATE"), d.revisions.map { it.changeKind })
            assertEquals(p(50_000), d.refundable)
            assertEquals("500.00", vm.refundDefault())
            assertEquals(gcash, vm.refundAccountDefault())

            assertEquals(R.string.err_refund_exceeds, vm.recordRefund("600", gcash, nov.plusDays(10)))
            assertNull(vm.recordRefund("200", gcash, nov.plusDays(10)))
            val after = checkNotNull(vm.state.value.details)
            assertEquals(listOf(p(20_000)), after.refunds.map { it.amount })
            assertEquals(p(30_000), after.refundable)
            // The refund lowers spending; it is not income.
            val summary = s.ledger.summary(nov, nov.plusDays(29)).getValue(CurrencyCode.PHP)
            assertEquals(p(30_000), summary.spending)
            assertEquals(p(0), summary.income)
            // While a refund depends on it, the expense cannot be deleted.
            assertEquals(R.string.err_txn_has_dependents, vm.delete())
        }

    @Test
    fun `delete from the detail screen keeps a revision (G)`() =
        runBlocking<Unit> {
            val id = spend(gcash, fare, 1_000)
            val vm = TransactionDetailViewModel(s, id)
            vm.load()
            assertNull(vm.delete())
            val d = checkNotNull(vm.state.value.details)
            assertTrue(d.deleted)
            assertEquals(listOf("CREATE", "DELETE"), d.revisions.map { it.changeKind })
        }

    private companion object {
        const val WAIT_MS = 5_000L
        const val STEP_MS = 50L
    }
}
