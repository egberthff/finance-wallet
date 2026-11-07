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
import ph.financewallet.app.ui.budgets.BudgetsViewModel
import ph.financewallet.app.ui.budgets.PeriodChoice
import ph.financewallet.app.ui.budgets.PeriodForm
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Budgets tab: open and closed budgets are listed apart, and a closed period or archived budget can be undone. */
@RunWith(RobolectricTestRunner::class)
class BudgetSectionsViewModelTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private lateinit var vm: BudgetsViewModel
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
            vm = BudgetsViewModel(s)
            val food = s.categories.create("Food", AccountClass.EXPENSE).getOrThrow()
            val fare = s.categories.create("Fare", AccountClass.EXPENSE).getOrThrow()
            assertNull(vm.create("Monthly", PeriodForm(PeriodChoice.MONTHLY, day1 = "1"), listOf(food), IncomeMode.MANUAL))
            assertNull(vm.create("Commute", PeriodForm(PeriodChoice.MONTHLY, day1 = "1"), listOf(fare), IncomeMode.MANUAL))
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity: ${report.failures}", report.isHealthy)
        }

    @Test
    fun `a period closed by mistake moves to Closed and back to Open when reopened`() =
        runBlocking<Unit> {
            var state = vm.load()
            assertEquals(listOf("Commute", "Monthly"), state.open.map { it.name })
            val monthly = state.open.single { it.name == "Monthly" }

            assertNull(vm.close(checkNotNull(monthly.current).period.id))
            state = vm.load()
            assertEquals(listOf("Commute"), state.open.map { it.name })
            assertEquals(listOf("Monthly"), state.closedPeriod.map { it.name })

            assertNull(vm.reopen(state.closedPeriod.single()))
            state = vm.load()
            assertEquals(listOf("Commute", "Monthly"), state.open.map { it.name })
            assertTrue(state.closedPeriod.isEmpty())
        }

    @Test
    fun `a category allocation is edited up, down and to zero, moving only the difference to or from Available`() =
        runBlocking<Unit> {
            suspend fun monthly() =
                checkNotNull(
                    vm
                        .load()
                        .budgets
                        .single { it.name == "Monthly" }
                        .current,
                )
            val period = monthly().period.id
            val food = monthly().categories.single().categoryId
            assertNull(vm.fund(period, "10,000", "fund"))
            assertNull(vm.allocate(period, food, "3,000", "alloc"))
            assertEquals(
                1_000_000L to 300_000L,
                monthly().funding.minor to
                    monthly()
                        .categories
                        .single()
                        .allocated.minor,
            )

            assertNull(vm.setAllocation(period, food, "5,000", "edit1"))
            assertEquals(
                500_000L to 500_000L,
                monthly().availableToBudget.minor to
                    monthly()
                        .categories
                        .single()
                        .allocated.minor,
            )

            assertNull(vm.setAllocation(period, food, "1,000.50", "edit2"))
            assertEquals(
                899_950L to 100_050L,
                monthly().availableToBudget.minor to
                    monthly()
                        .categories
                        .single()
                        .allocated.minor,
            )

            assertNull(vm.setAllocation(period, food, "0", "edit3"))
            assertEquals(
                1_000_000L to 0L,
                monthly().availableToBudget.minor to
                    monthly()
                        .categories
                        .single()
                        .allocated.minor,
            )

            assertNull(vm.setAllocation(period, food, "0", "edit4"))
            assertEquals(R.string.err_amount_negative, vm.setAllocation(period, food, "-100", "edit5"))
            assertEquals(1_000_000L, monthly().funding.minor)
        }

    @Test
    fun `an archived budget is listed under Closed and can be restored`() =
        runBlocking<Unit> {
            val commute = vm.load().open.single { it.name == "Commute" }
            val version =
                s.budgets
                    .activeBudgets()
                    .single { it.id == commute.id }
                    .version
            s.budgets.archiveBudget(commute.id, version).getOrThrow()
            var state = vm.load()
            assertEquals(listOf("Monthly"), state.open.map { it.name })
            assertEquals(listOf("Commute"), state.archived.map { it.name })

            assertNull(vm.restore(state.archived.single()))
            state = vm.load()
            assertEquals(listOf("Commute", "Monthly"), state.open.map { it.name })
            assertTrue(state.archived.isEmpty())
        }
}
