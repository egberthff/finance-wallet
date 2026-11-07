package ph.financewallet.data.budget

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
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.allocation.Bucket
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.allocation.RolloverAction
import ph.financewallet.domain.allocation.RolloverPolicy
import ph.financewallet.domain.budget.PeriodRule
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
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test can move forward (period close, rule changes). */
private class MovableClock(
    var instant: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)

    override fun instant(): Instant = instant
}

/**
 * Budget, allocation and goal use cases UC-16..UC-21 against Room (docs/10-domain-model.md §5–6). Integrity checks
 * IC-1..IC-8 (incl. IC-5 allocation balance) must hold after every test.
 */
@RunWith(RobolectricTestRunner::class)
class BudgetServicesTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: LedgerService
    private lateinit var budgets: BudgetService
    private lateinit var allocations: AllocationService
    private lateinit var goals: GoalService
    private lateinit var lifecycle: BudgetLifecycleService

    // 2026-11-05 11:00 in Asia/Manila.
    private val clock = MovableClock(Instant.parse("2026-11-05T03:00:00Z"))
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "test-device")
    private val php = CurrencyCode.PHP
    private val nov5 = LocalDate.of(2026, 11, 5)
    private var keySeq = 0

    private lateinit var gcash: String
    private lateinit var bpiSavings: String
    private lateinit var salary: String
    private lateinit var food: String
    private lateinit var groceries: String
    private lateinit var transport: String
    private lateinit var bills: String

    private fun p(minor: Long) = Money(minor, php)

    private fun key() = "k${++keySeq}"

    private fun today(date: LocalDate) {
        clock.instant = date.atTime(11, 0).atZone(ZoneId.of("Asia/Manila")).toInstant()
    }

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            ledger = LedgerService(db, ctx)
            budgets = BudgetService(db, ctx)
            lifecycle = BudgetLifecycleService(db, ctx)
            allocations = AllocationService(db, ctx)
            goals = GoalService(db, ctx, ledger)
            ledger.ensureSystemAccounts()
            gcash = ledger.createAccount(NewAccount("GCash", FinancialAccountType.EWALLET, php, nov5, p(0))).getOrThrow()
            bpiSavings = ledger.createAccount(NewAccount("BPI Savings", FinancialAccountType.SAVINGS, php, nov5, p(0))).getOrThrow()
            salary = ledger.createCategory("Salary", AccountClass.INCOME).getOrThrow()
            food = ledger.createCategory("Food", AccountClass.EXPENSE).getOrThrow()
            groceries = ledger.createCategory("Groceries", AccountClass.EXPENSE, parentId = food).getOrThrow()
            transport = ledger.createCategory("Transport", AccountClass.EXPENSE).getOrThrow()
            bills = ledger.createCategory("Bills", AccountClass.EXPENSE).getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity after test: ${report.failures}", report.isHealthy)
        }

    private suspend fun income(
        amount: Long,
        date: LocalDate = nov5,
        account: String = gcash,
    ) = ledger.record(TxnHeader(date, key())) { t, _ -> t.income(account, listOf(Split(salary, p(amount)))) }.getOrThrow()

    private suspend fun spend(
        category: String,
        amount: Long,
        date: LocalDate = nov5,
        account: String = gcash,
    ) = ledger.record(TxnHeader(date, key())) { t, _ -> t.expense(account, listOf(Split(category, p(amount)))) }.getOrThrow()

    private suspend fun monthlyBudget(
        mode: IncomeMode = IncomeMode.TRACKED,
        specs: List<BudgetCategorySpec> = listOf(BudgetCategorySpec(food), BudgetCategorySpec(transport), BudgetCategorySpec(bills)),
        fixed: Money? = null,
        accounts: Set<String> = emptySet(),
    ): String =
        budgets
            .createBudget(
                NewBudget(php, PeriodRule.Monthly(nov5, 5), BudgetSettings("Personal", mode, fixed, specs, accounts)),
            ).getOrThrow()

    private suspend fun period(
        budgetId: String,
        date: LocalDate = nov5,
    ) = budgets.ensurePeriod(budgetId, date).getOrThrow()

    private suspend fun goal(
        name: String = "Laptop",
        target: Long = 5_000_000,
        linked: Set<String> = emptySet(),
    ) = goals.createGoal(php, GoalDetails(name, p(target)), linked).getOrThrow()

    // ---- periods (UC-17) ----------------------------------------------------------------------------------------

    @Test
    fun `EX-11 periods are created lazily, once, with the right boundaries`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            val p1 = period(b, LocalDate.of(2026, 12, 4))
            assertEquals(LocalDate.of(2026, 11, 5) to LocalDate.of(2026, 12, 4), p1.start to p1.endInclusive)
            val p2 = period(b, LocalDate.of(2026, 12, 5))
            assertEquals(LocalDate.of(2027, 1, 4), p2.endInclusive)
            assertEquals(p1.id, period(b, nov5).id)
            assertEquals(2, budgets.periods(b).size)
            assertEquals("NO_PERIOD_FOR_DATE", budgets.ensurePeriod(b, LocalDate.of(2026, 11, 4)).errorOrNull()?.code)
        }

    // ---- EX-15 / AC-CORE-3: savings allocation is not spending ------------------------------------------------

    @Test
    fun `EX-15 contributing to a goal lowers available, raises the goal, leaves spending and balances alone`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            income(3_000_000)
            val p = period(b)
            assertEquals(p(3_000_000), budgets.overview(p.id).availableToBudget)
            val laptop = goal()
            val before = ledger.summary(nov5, nov5).getValue(php)
            goals.contribute(Bucket.Unallocated(p.id), laptop, p(500_000), key()).getOrThrow()

            val o = budgets.overview(p.id)
            assertEquals(p(3_000_000), o.funding)
            assertEquals(p(2_500_000), o.availableToBudget)
            assertEquals(p(500_000), goals.summary(laptop).allocated)
            assertEquals(before, ledger.summary(nov5, nov5).getValue(php))
            assertEquals(p(3_000_000), ledger.balance(gcash, nov5))
        }

    // ---- allocation (UC-18) -------------------------------------------------------------------------------------

    @Test
    fun `EX-16 percentage allocation through the service sums exactly`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            income(2_500_000)
            val p = period(b)
            allocations.allocatePercent(p.id, p(2_500_000), listOf(food to 3000, transport to 1500, bills to 5500), key()).getOrThrow()
            val lines = budgets.overview(p.id).categories.associate { it.categoryId to it.allocated }
            assertEquals(mapOf(food to p(750_000), transport to p(375_000), bills to p(1_375_000)), lines)
            assertEquals(p(0), budgets.overview(p.id).availableToBudget)
        }

    @Test
    fun `allocation is idempotent by key and over-allocation shows as negative available`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            income(100_000)
            val p = period(b)
            val k = key()
            val first = allocations.allocate(p.id, food, p(150_000), k).getOrThrow()
            assertEquals(first, allocations.allocate(p.id, food, p(150_000), k).getOrThrow())
            assertEquals(p(-50_000), budgets.overview(p.id).availableToBudget)
        }

    @Test
    fun `category spending includes subcategories and refunds, remaining = allocated minus spent`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            income(1_000_000)
            val p = period(b)
            allocations.allocate(p.id, food, p(500_000), key()).getOrThrow()
            spend(food, 100_000)
            val grocery = spend(groceries, 250_000)
            ledger
                .record(TxnHeader(nov5, key())) { t, r -> t.refund(gcash, p(50_000), groceries, r.refundOriginal(grocery)) }
                .getOrThrow()
            spend(food, 999_999, date = LocalDate.of(2026, 12, 5)) // next period: not counted
            val line = budgets.overview(p.id).categories.single { it.categoryId == food }
            assertEquals(p(300_000), line.spent)
            assertEquals(p(200_000), line.remaining)
        }

    @Test
    fun `account filter counts only transactions touching the budget's accounts`() =
        runBlocking<Unit> {
            val b = monthlyBudget(accounts = setOf(gcash))
            income(1_000_000)
            income(1_000_000, account = bpiSavings)
            val p = period(b)
            spend(food, 100_000)
            spend(food, 70_000, account = bpiSavings)
            val o = budgets.overview(p.id)
            assertEquals(p(1_000_000), o.funding)
            assertEquals(p(100_000), o.categories.single { it.categoryId == food }.spent)
        }

    @Test
    fun `income modes - FIXED uses the amount, MANUAL only counts FUND, FUND is rejected elsewhere`() =
        runBlocking<Unit> {
            income(9_999_900)
            val fixed = monthlyBudget(IncomeMode.FIXED, fixed = p(2_000_000))
            assertEquals(p(2_000_000), budgets.overview(period(fixed).id).funding)
            assertEquals("FUND_REQUIRES_MANUAL_MODE", allocations.fund(period(fixed).id, p(1), key()).errorOrNull()?.code)

            val manual = monthlyBudget(IncomeMode.MANUAL)
            val mp = period(manual)
            assertEquals(p(0), budgets.overview(mp.id).funding)
            allocations.fund(mp.id, p(1_500_000), key()).getOrThrow()
            allocations.allocate(mp.id, food, p(400_000), key()).getOrThrow()
            val o = budgets.overview(mp.id)
            assertEquals(p(1_500_000), o.funding)
            assertEquals(p(1_100_000), o.availableToBudget)
        }

    @Test
    fun `applyDefaults allocates fixed amounts and percentages of funding once`() =
        runBlocking<Unit> {
            val specs =
                listOf(
                    BudgetCategorySpec(bills, AllocationMode.FIXED, defaultAmount = p(800_000)),
                    BudgetCategorySpec(food, AllocationMode.PERCENT, percentBp = 3000),
                    BudgetCategorySpec(transport, AllocationMode.PERCENT, percentBp = 1000),
                )
            val b = monthlyBudget(specs = specs)
            income(2_000_000)
            val p = period(b)
            val k = key()
            val ids = allocations.applyDefaults(p.id, k).getOrThrow()
            assertEquals(ids, allocations.applyDefaults(p.id, k).getOrThrow())
            val lines = budgets.overview(p.id).categories.associate { it.categoryId to it.allocated }
            assertEquals(mapOf(bills to p(800_000), food to p(600_000), transport to p(200_000)), lines)
            assertEquals(p(400_000), budgets.overview(p.id).availableToBudget)
        }

    @Test
    fun `reallocate stays within one period and buckets never go negative`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            income(1_000_000)
            val p = period(b)
            allocations.allocate(p.id, food, p(300_000), key()).getOrThrow()
            allocations
                .reallocate(
                    Bucket.Category(p.id, food),
                    Bucket.Category(p.id, transport),
                    p(100_000),
                    key(),
                    "bus pass",
                ).getOrThrow()
            assertEquals(
                "ALLOCATION_INSUFFICIENT",
                allocations.reallocate(Bucket.Category(p.id, food), Bucket.Category(p.id, bills), p(200_001), key()).errorOrNull()?.code,
            )
            val next = period(b, LocalDate.of(2026, 12, 5))
            assertEquals(
                "DIFFERENT_PERIODS",
                allocations.reallocate(Bucket.Category(p.id, food), Bucket.Category(next.id, food), p(1), key()).errorOrNull()?.code,
            )
            assertEquals("CATEGORY_NOT_IN_BUDGET", allocations.allocate(p.id, salary, p(1), key()).errorOrNull()?.code)
        }

    // ---- budget configuration (UC-16) ---------------------------------------------------------------------------

    @Test
    fun `budget validation - overlap, income allocation, percent total, fixed income`() =
        runBlocking<Unit> {
            suspend fun create(s: BudgetSettings) = budgets.createBudget(NewBudget(php, PeriodRule.Monthly(nov5, 5), s)).errorOrNull()?.code
            assertEquals(
                "BUDGET_CATEGORY_OVERLAP",
                create(BudgetSettings("B", IncomeMode.TRACKED, null, listOf(BudgetCategorySpec(food), BudgetCategorySpec(groceries)))),
            )
            assertEquals(
                "INCOME_CATEGORY_NOT_ALLOCATABLE",
                create(BudgetSettings("B", IncomeMode.TRACKED, null, listOf(BudgetCategorySpec(salary, AllocationMode.FIXED, p(1))))),
            )
            assertEquals(
                "WEIGHTS_EXCEED_100_PERCENT",
                create(
                    BudgetSettings(
                        "B",
                        IncomeMode.TRACKED,
                        null,
                        listOf(
                            BudgetCategorySpec(food, AllocationMode.PERCENT, percentBp = 6000),
                            BudgetCategorySpec(bills, AllocationMode.PERCENT, percentBp = 4001),
                        ),
                    ),
                ),
            )
            assertEquals("FIXED_INCOME_REQUIRED", create(BudgetSettings("B", IncomeMode.FIXED, null, listOf(BudgetCategorySpec(food)))))
            assertEquals("BUDGET_CATEGORIES_EMPTY", create(BudgetSettings("B", IncomeMode.TRACKED, null, emptyList())))
            assertTrue(db.budgetDao().activeBudgets().isEmpty())
        }

    @Test
    fun `a category holding allocation cannot be removed, edits are version-checked`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            income(1_000_000)
            val p = period(b)
            allocations.allocate(p.id, food, p(100_000), key()).getOrThrow()
            val withoutFood =
                BudgetSettings("Personal", IncomeMode.TRACKED, null, listOf(BudgetCategorySpec(transport), BudgetCategorySpec(bills)))
            assertEquals("CATEGORY_HAS_ALLOCATION", budgets.editBudget(b, 1, withoutFood).errorOrNull()?.code)
            assertEquals(3, db.budgetDao().budgetCategories(b).size) // rolled back, nothing lost
            allocations.reallocate(Bucket.Category(p.id, food), Bucket.Unallocated(p.id), p(100_000), key()).getOrThrow()
            budgets.editBudget(b, 1, withoutFood).getOrThrow()
            assertEquals("STALE_VERSION", budgets.editBudget(b, 1, withoutFood).errorOrNull()?.code)
        }

    @Test
    fun `rule change keeps stored periods and bridges the gap to the new rule`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            today(LocalDate.of(2026, 11, 20))
            val effective = budgets.ruleChangeEffectiveDate(b)
            assertEquals(LocalDate.of(2026, 12, 5), effective)
            assertEquals(
                "RULE_ANCHOR_BEFORE_EFFECTIVE_DATE",
                budgets.changeRule(b, 1, PeriodRule.Monthly(LocalDate.of(2026, 12, 1), 15)).errorOrNull()?.code,
            )
            budgets.changeRule(b, 1, PeriodRule.Monthly(effective, 15)).getOrThrow()
            val ranges =
                listOf(nov5, LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 14)).map {
                    period(b, it).let { p -> p.start to p.endInclusive }
                }
            assertEquals(
                listOf(
                    LocalDate.of(2026, 11, 5) to LocalDate.of(2026, 12, 4), // old rule, unchanged
                    LocalDate.of(2026, 12, 5) to LocalDate.of(2026, 12, 14), // bridging period
                    LocalDate.of(2026, 12, 15) to LocalDate.of(2027, 1, 14), // new rule
                    LocalDate.of(2026, 12, 15) to LocalDate.of(2027, 1, 14),
                ),
                ranges,
            )
        }

    // ---- close + rollover (UC-19) -------------------------------------------------------------------------------

    @Test
    fun `closing applies each policy once - carry, carry overspend, sweep, expire`() =
        runBlocking<Unit> {
            val laptop = goal()
            val specs =
                listOf(
                    BudgetCategorySpec(food, rollover = RolloverPolicy.CARRY),
                    BudgetCategorySpec(transport, rollover = RolloverPolicy.CARRY, carryNegative = true),
                    BudgetCategorySpec(bills, rollover = RolloverPolicy.SWEEP_TO_GOAL, sweepGoalId = laptop),
                )
            val b = monthlyBudget(specs = specs)
            income(3_000_000)
            val p = period(b)
            allocations.allocate(p.id, food, p(500_000), key()).getOrThrow()
            allocations.allocate(p.id, transport, p(100_000), key()).getOrThrow()
            allocations.allocate(p.id, bills, p(900_000), key()).getOrThrow()
            spend(food, 300_000)
            spend(transport, 130_000)
            spend(bills, 850_000)

            today(LocalDate.of(2026, 12, 6))
            val current = budgets.refresh(b).getOrThrow()!!
            assertEquals(LocalDate.of(2026, 12, 5), current.start)
            assertTrue(budgets.periods(b).first().closed)

            val next = budgets.overview(current.id).categories.associate { it.categoryId to it.allocated }
            assertEquals(p(200_000), next[food]) // carried leftover
            assertEquals(p(-30_000), next[transport]) // carried overspend
            assertEquals(p(50_000), goals.summary(laptop).allocated) // swept
            val closedLines = budgets.overview(p.id).categories.associate { it.categoryId to it.remaining }
            assertEquals(mapOf(food to p(0), transport to p(0), bills to p(0)), closedLines)

            val again = budgets.closePeriod(p.id).getOrThrow()
            assertTrue(again.alreadyClosed)
            assertEquals("PERIOD_CLOSED", allocations.allocate(p.id, food, p(1), key()).errorOrNull()?.code)
        }

    @Test
    fun `MANUAL leftovers wait on the reassign list until resolved once`() =
        runBlocking<Unit> {
            val b = monthlyBudget(specs = listOf(BudgetCategorySpec(food, rollover = RolloverPolicy.MANUAL), BudgetCategorySpec(bills)))
            income(1_000_000)
            val p = period(b)
            allocations.allocate(p.id, food, p(400_000), key()).getOrThrow()
            spend(food, 100_000)
            val result = budgets.closePeriod(p.id).getOrThrow()
            assertEquals(RolloverAction.ToReassign(p(300_000)), result.rollovers.single { it.categoryId == food }.action)
            assertEquals(listOf(Leftover(p.id, food, p(300_000))), budgets.leftovers(b))

            budgets.resolveLeftover(p.id, food, LeftoverResolution.CarryToNextPeriod).getOrThrow()
            assertEquals(emptyList<Leftover>(), budgets.leftovers(b))
            val next = period(b, LocalDate.of(2026, 12, 5))
            assertEquals(
                p(300_000),
                budgets
                    .overview(next.id)
                    .categories
                    .single { it.categoryId == food }
                    .allocated,
            )
            assertEquals("ALREADY_RESOLVED", budgets.resolveLeftover(p.id, food, LeftoverResolution.Expire).errorOrNull()?.code)
        }

    // ---- goals (UC-20/21) ---------------------------------------------------------------------------------------

    @Test
    fun `withdrawals and goal transfers need confirmation and record before and after`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            income(1_000_000)
            val p = period(b)
            val laptop = goal()
            val trip = goal("Trip")
            goals.contribute(Bucket.Unallocated(p.id), laptop, p(400_000), key()).getOrThrow()
            assertEquals(
                "CONFIRMATION_REQUIRED",
                goals.withdraw(laptop, p.id, p(100_000), confirmed = false, idempotencyKey = key()).errorOrNull()?.code,
            )
            assertEquals("ALLOCATION_INSUFFICIENT", goals.withdraw(laptop, p.id, p(400_001), true, key()).errorOrNull()?.code)
            val w = goals.withdraw(laptop, p.id, p(100_000), confirmed = true, idempotencyKey = key(), reason = "repair").getOrThrow()
            val group = db.budgetDao().group(w)!!
            assertEquals("repair", group.reason)
            assertTrue(group.confirmedAt != null)
            assertTrue(group.snapshotJson.contains("\"previousMinor\":\"400000\",\"updatedMinor\":\"300000\""))
            goals.transfer(laptop, trip, p(50_000), confirmed = true, idempotencyKey = key()).getOrThrow()
            assertEquals(p(250_000), goals.summary(laptop).allocated)
            assertEquals(p(50_000), goals.summary(trip).allocated)
            // 1,000,000 − 400,000 + 100,000; goal-to-goal transfers do not touch the pool
            assertEquals(p(700_000), budgets.overview(p.id).availableToBudget)
        }

    @Test
    fun `EX-17 linked goals cannot exceed the linked balance, and net worth counts the money once`() =
        runBlocking<Unit> {
            income(2_000_000, account = bpiSavings)
            val laptop = goal(linked = setOf(bpiSavings))
            goals.earmark(laptop, p(1_500_000), key()).getOrThrow()
            assertEquals("GOAL_EXCEEDS_LINKED_BALANCE", goals.earmark(laptop, p(600_000), key()).errorOrNull()?.code)
            assertEquals(p(500_000), goals.summary(laptop).linkedHeadroom)

            // a second goal sharing the account shares the cap
            val phone = goal("Phone")
            val v = goals.summary(phone).version
            goals.earmark(phone, p(600_000), key()).getOrThrow()
            assertEquals("GOAL_EXCEEDS_LINKED_BALANCE", goals.linkAccounts(phone, v, setOf(bpiSavings)).errorOrNull()?.code)
            assertEquals(emptySet<String>(), goals.summary(phone).linkedAccountIds) // rolled back
            // free money = spendable balances − unlinked goals; the linked ₱15,000 is inside BPI's balance already
            assertEquals(p(2_000_000 - 600_000), goals.freeMoney(php))
        }

    @Test
    fun `saving to a linked account records the transfer and the allocation together, or neither`() =
        runBlocking<Unit> {
            income(1_000_000)
            val laptop = goal(linked = setOf(bpiSavings))
            val saved = goals.saveToAccount(TxnHeader(nov5, "save-1"), gcash, bpiSavings, p(300_000), laptop).getOrThrow()
            assertEquals(saved.txnId, db.budgetDao().group(saved.allocationGroupId)!!.relatedTxnId)
            assertEquals(p(300_000), ledger.balance(bpiSavings, nov5))
            assertEquals(p(300_000), goals.summary(laptop).allocated)
            assertEquals(p(0), ledger.summary(nov5, nov5).getValue(php).spending)
            assertEquals(saved, goals.saveToAccount(TxnHeader(nov5, "save-1"), gcash, bpiSavings, p(300_000), laptop).getOrThrow())

            val paused = goal("Paused")
            goals.editGoal(paused, 1, GoalDetails("Paused", p(1_000)), GoalStatus.PAUSED).getOrThrow()
            val failed = goals.saveToAccount(TxnHeader(nov5, "save-2"), gcash, bpiSavings, p(100_000), paused)
            assertEquals("GOAL_NOT_ACTIVE", failed.errorOrNull()?.code)
            assertNull("transfer rolled back with the allocation", db.ledgerDao().txnByIdempotencyKey("save-2"))
            assertEquals(p(300_000), ledger.balance(bpiSavings, nov5))
        }

    @Test
    fun `database rejects an unconfirmed withdrawal group even if code forgot the check`() =
        runBlocking<Unit> {
            val error =
                runCatching {
                    db.openHelper.writableDatabase.execSQL(
                        "INSERT INTO allocation_group(id, kind, effective_date, reason, related_txn_id, snapshot_json, confirmed_at, " +
                            "idempotency_key, created_at, deleted_at, version, updated_at, updated_by_device, hlc) " +
                            "VALUES ('g', 'GOAL_WITHDRAW', '2026-11-05', NULL, NULL, '{}', NULL, 'x', 0, NULL, 1, 0, 'd', 'h')",
                    )
                }.exceptionOrNull()
            assertTrue("${error?.message}", error?.message.orEmpty().contains("CK_ALLOC_CONFIRMATION"))
        }

    // ---- undo: restore an archived budget, reopen a closed period ---------------------------------------------------

    @Test
    fun `an archived budget is listed as archived and can be restored`() =
        runBlocking<Unit> {
            val b = monthlyBudget()
            val v = budgets.activeBudgets().single().version
            budgets.archiveBudget(b, v).getOrThrow()
            assertEquals(emptyList<String>(), budgets.activeBudgets().map { it.id })
            val archived = lifecycle.archivedBudgets().single()
            assertEquals(b, archived.id)

            lifecycle.unarchiveBudget(b, archived.version).getOrThrow()
            assertEquals(listOf(b), budgets.activeBudgets().map { it.id })
            assertEquals(emptyList<String>(), lifecycle.archivedBudgets().map { it.id })
            assertEquals("NOT_ARCHIVED", lifecycle.unarchiveBudget(b, budgets.activeBudgets().single().version).errorOrNull()?.code)
        }

    @Test
    fun `a period closed by mistake reopens, its carry and sweep are reversed, and it can be closed again`() =
        runBlocking<Unit> {
            val laptop = goal()
            val specs =
                listOf(
                    BudgetCategorySpec(food, rollover = RolloverPolicy.CARRY),
                    BudgetCategorySpec(bills, rollover = RolloverPolicy.SWEEP_TO_GOAL, sweepGoalId = laptop),
                )
            val b = monthlyBudget(specs = specs)
            income(3_000_000)
            val p = period(b)
            allocations.allocate(p.id, food, p(500_000), key()).getOrThrow()
            allocations.allocate(p.id, bills, p(900_000), key()).getOrThrow()
            spend(food, 300_000)
            spend(bills, 850_000)

            budgets.closePeriod(p.id).getOrThrow() // mis-tap: the period runs to 4 Dec
            val next = period(b, LocalDate.of(2026, 12, 5))

            suspend fun nextFood() =
                budgets
                    .overview(next.id)
                    .categories
                    .single { it.categoryId == food }
                    .allocated
            assertEquals(p(200_000), nextFood())
            assertEquals(p(50_000), goals.summary(laptop).allocated)

            assertEquals(2, lifecycle.reopenPeriod(p.id).getOrThrow())
            assertTrue(!budgets.periods(b).first().closed)
            assertEquals(p(0), nextFood())
            assertEquals(p(0), goals.summary(laptop).allocated)
            assertEquals(
                p(200_000),
                budgets
                    .overview(p.id)
                    .categories
                    .single { it.categoryId == food }
                    .remaining,
            )
            allocations.allocate(p.id, food, p(1_000), key()).getOrThrow() // open again: allocations work

            // Closing again works (the reversed moves' keys were freed) and moves the money once more.
            val again = budgets.closePeriod(p.id).getOrThrow()
            assertTrue(!again.alreadyClosed)
            assertEquals(p(201_000), nextFood())
            assertEquals(p(50_000), goals.summary(laptop).allocated)
            assertEquals(
                listOf("budget.period_reopen"),
                db.accountDao().auditFor(p.id).map { it.kind },
            )
        }

    @Test
    fun `reopening is refused once the moved money was used, or after the period ended`() =
        runBlocking<Unit> {
            val laptop = goal()
            val b = monthlyBudget(specs = listOf(BudgetCategorySpec(bills, rollover = RolloverPolicy.SWEEP_TO_GOAL, sweepGoalId = laptop)))
            income(3_000_000)
            val p = period(b)
            allocations.allocate(p.id, bills, p(900_000), key()).getOrThrow()
            spend(bills, 850_000)
            budgets.closePeriod(p.id).getOrThrow() // ₱500 swept to the goal
            val next = period(b, LocalDate.of(2026, 12, 5))
            goals.withdraw(laptop, next.id, p(30_000), confirmed = true, key()).getOrThrow() // ₱300 of it moved on

            assertEquals("MOVED_MONEY_USED", lifecycle.reopenPeriod(p.id).errorOrNull()?.code)
            assertTrue(budgets.periods(b).first().closed)
            assertEquals(p(20_000), goals.summary(laptop).allocated)

            today(LocalDate.of(2026, 12, 6)) // the period (5 Nov – 4 Dec) has ended
            assertEquals("PERIOD_ALREADY_ENDED", lifecycle.reopenPeriod(p.id).errorOrNull()?.code)
            assertEquals("PERIOD_NOT_CLOSED", lifecycle.reopenPeriod(next.id).errorOrNull()?.code)
        }
}
