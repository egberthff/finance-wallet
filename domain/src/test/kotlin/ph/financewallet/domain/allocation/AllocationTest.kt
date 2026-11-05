package ph.financewallet.domain.allocation

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.errorOrNull
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money

class AllocationTest {
    private val php = CurrencyCode.PHP

    private fun p(minor: Long) = Money(minor, php)

    /** In-memory allocation ledger: applies drafts and answers bucket balances. */
    private class Ledger(
        private val php: CurrencyCode,
    ) : BucketBalances {
        val entries = mutableListOf<AllocationEntry>()

        override fun of(bucket: Bucket): Money = Money.sum(entries.filter { it.bucket == bucket }.map { it.amount }, php)

        fun apply(draft: AllocationDraft) = entries.addAll(draft.entries)
    }

    private val period = "p-2026-11"
    private val pool = Bucket.Unallocated(period)

    @Test
    fun `EX-15 contributing 5,000 to a goal reduces available-to-budget and spending is untouched (AC-CORE-3)`() {
        val ledger = Ledger(php)
        val t = AllocationTemplates(php, ledger)
        val funding = BudgetMath.funding(IncomeMode.TRACKED, trackedIncome = p(3_000_000), fixedAmount = null, manualFunds = p(0))
        val draft = t.contributeToGoal(pool, "emergency", p(500_000)).getOrThrow()
        ledger.apply(draft)
        assertEquals(p(2_500_000), BudgetMath.availableToBudget(funding, ledger.of(pool)))
        assertEquals(p(500_000), ledger.of(Bucket.Goal("emergency")))
        assertEquals(AllocationKind.GOAL_CONTRIBUTE, draft.kind)
        // The allocation ledger has no money-ledger entries at all: spending/expenses cannot change (SAV-02).
        assertTrue(draft.entries.all { it.bucket is Bucket.Unallocated || it.bucket is Bucket.Goal })
    }

    @Test
    fun `EX-16 percentage allocation of 25,000 pesos sums exactly`() {
        val t = AllocationTemplates(php, Ledger(php))
        val d = t.allocatePercent(period, p(2_500_000), listOf("food" to 3000, "transport" to 1500, "bills" to 5500)).getOrThrow()
        val byCategory = d.entries.filter { it.amount.isPositive }.associate { (it.bucket as Bucket.Category).categoryId to it.amount }
        assertEquals(mapOf("food" to p(750_000), "transport" to p(375_000), "bills" to p(1_375_000)), byCategory)
        assertEquals(0L, d.entries.sumOf { it.amount.minor })
    }

    @Test
    fun `partial percentages leave the rest unallocated and round down deterministically`() {
        val t = AllocationTemplates(php, Ledger(php))
        val d = t.allocatePercent(period, p(10_001), listOf("a" to 3333, "b" to 0, "c" to 3333)).getOrThrow()
        val allocated = d.entries.filter { it.amount.isPositive }.sumOf { it.amount.minor }
        assertEquals(6_666L, allocated, "floor(10,001 × 66.66 %) = 6,666")
        assertEquals("WEIGHTS_EXCEED_100_PERCENT", t.allocatePercent(period, p(100), listOf("a" to 6000, "b" to 5000)).errorOrNull()?.code)
        assertEquals("WEIGHT_OUT_OF_RANGE", t.allocatePercent(period, p(100), listOf("a" to -1)).errorOrNull()?.code)
        assertEquals("WEIGHTS_EMPTY", t.allocatePercent(period, p(100), emptyList()).errorOrNull()?.code)
        assertEquals("WEIGHTS_EXCEED_100_PERCENT", t.allocatePercent(period, p(100), listOf("a" to 0)).errorOrNull()?.code)
    }

    @Test
    fun `EX-17 linked goal cannot exceed its account balance`() {
        val ledger = Ledger(php)
        val balances = mapOf("bpi-savings" to p(2_000_000))
        val cap = LinkedGoalCap(listOf(GoalLink("laptop", setOf("bpi-savings"))), { ledger.of(Bucket.Goal(it)) }, { balances.getValue(it) })
        val t = AllocationTemplates(php, ledger, cap)
        ledger.apply(t.contributeToGoal(pool, "laptop", p(1_500_000)).getOrThrow())
        assertEquals("GOAL_EXCEEDS_LINKED_BALANCE", t.contributeToGoal(pool, "laptop", p(600_000)).errorOrNull()?.code)
        assertEquals(p(500_000), cap.headroom("laptop", php))
        ledger.apply(t.contributeToGoal(pool, "laptop", p(500_000)).getOrThrow())
        assertNull(cap.headroom("virtual-goal", php), "unlinked goals are not capped")
    }

    @Test
    fun `goals sharing an account share one cap`() {
        val ledger = Ledger(php)
        val balances = mapOf("a1" to p(1_000), "a2" to p(500), "a3" to p(9_999))
        val links =
            listOf(GoalLink("g1", setOf("a1")), GoalLink("g2", setOf("a1", "a2")), GoalLink("g3", setOf("a3")), GoalLink("v", emptySet()))
        val cap = LinkedGoalCap(links, { ledger.of(Bucket.Goal(it)) }, { balances.getValue(it) })
        val t = AllocationTemplates(php, ledger, cap)
        ledger.apply(t.contributeToGoal(pool, "g1", p(1_200)).getOrThrow())
        assertEquals(p(300), cap.headroom("g2", php), "g1+g2 share a1+a2 = 1,500")
        assertEquals("GOAL_EXCEEDS_LINKED_BALANCE", t.contributeToGoal(pool, "g2", p(301)).errorOrNull()?.code)
        assertEquals(p(9_999), cap.headroom("g3", php))
        assertEquals("GOAL_EXCEEDS_LINKED_BALANCE", t.transferBetweenGoals("g3", "g2", p(301), confirmed = true).errorOrNull()?.code)
    }

    @Test
    fun `SAV-04 withdrawals and goal transfers need confirmation and record before-after`() {
        val ledger = Ledger(php)
        val t = AllocationTemplates(php, ledger)
        ledger.apply(t.contributeToGoal(pool, "travel", p(10_000)).getOrThrow())
        assertEquals("CONFIRMATION_REQUIRED", t.withdrawFromGoal("travel", period, p(1_000), confirmed = false).errorOrNull()?.code)
        val w = t.withdrawFromGoal("travel", period, p(4_000), confirmed = true, reason = "Flight moved").getOrThrow()
        assertEquals(BucketChange(Bucket.Goal("travel"), p(10_000), p(6_000)), w.changes.single { it.bucket is Bucket.Goal })
        assertEquals(BucketChange(pool, p(-10_000), p(-6_000)), w.changes.single { it.bucket == pool })
        assertEquals("Flight moved", w.reason)
        assertTrue(w.confirmed)
        assertEquals("CONFIRMATION_REQUIRED", t.transferBetweenGoals("travel", "laptop", p(1), confirmed = false).errorOrNull()?.code)
        assertEquals(AllocationKind.GOAL_TRANSFER, t.transferBetweenGoals("travel", "laptop", p(1), confirmed = true).getOrThrow().kind)
        assertEquals("SAME_BUCKET", t.transferBetweenGoals("travel", "travel", p(1), confirmed = true).errorOrNull()?.code)
        assertEquals("REASON_TOO_LONG", t.withdrawFromGoal("travel", period, p(1), true, "x".repeat(501)).errorOrNull()?.code)
    }

    @Test
    fun `goal and category buckets can never go negative, the pool can`() {
        val ledger = Ledger(php)
        val t = AllocationTemplates(php, ledger)
        assertEquals("ALLOCATION_INSUFFICIENT", t.withdrawFromGoal("g", period, p(1), confirmed = true).errorOrNull()?.code)
        val over = t.allocate(period, "food", p(5_000)).getOrThrow()
        assertEquals(p(-5_000), over.changes.single { it.bucket == pool }.updated, "over-allocation allowed, shown as warning")
        ledger.apply(over)
        assertEquals("ALLOCATION_INSUFFICIENT", t.reallocate(Bucket.Category(period, "food"), pool, p(5_001)).errorOrNull()?.code)
        assertEquals(
            AllocationKind.REALLOCATE,
            t.reallocate(Bucket.Category(period, "food"), Bucket.Category(period, "fun"), p(5_000)).getOrThrow().kind,
        )
    }

    @Test
    fun `reallocation and funding validation`() {
        val t = AllocationTemplates(php, Ledger(php))
        assertEquals("SAME_BUCKET", t.reallocate(pool, pool, p(1)).errorOrNull()?.code)
        assertEquals("USE_GOAL_ACTIONS", t.reallocate(Bucket.Goal("g"), pool, p(1)).errorOrNull()?.code)
        assertEquals("DIFFERENT_PERIODS", t.reallocate(pool, Bucket.Category("other-period", "food"), p(1)).errorOrNull()?.code)
        assertEquals("USE_GOAL_TRANSFER", t.contributeToGoal(Bucket.Goal("a"), "b", p(1)).errorOrNull()?.code)
        assertEquals("AMOUNT_NOT_POSITIVE", t.fund(period, p(0)).errorOrNull()?.code)
        assertEquals("CURRENCY_MISMATCH", t.fund(period, Money(1, CurrencyCode.of("USD"))).errorOrNull()?.code)
        val fund = t.fund(period, p(30_000)).getOrThrow()
        assertEquals(listOf(AllocationEntry(pool, p(30_000))), fund.entries)
    }

    @Test
    fun `direct earmarks need confirmation only when releasing`() {
        val ledger = Ledger(php)
        val t = AllocationTemplates(php, ledger)
        ledger.apply(t.earmarkDirect("g", p(700)).getOrThrow())
        assertEquals("CONFIRMATION_REQUIRED", t.earmarkDirect("g", p(-100)).errorOrNull()?.code)
        assertEquals(
            p(600),
            t
                .earmarkDirect("g", p(-100), confirmed = true)
                .getOrThrow()
                .changes
                .single()
                .updated,
        )
        assertEquals("AMOUNT_ZERO", t.earmarkDirect("g", p(0)).errorOrNull()?.code)
        assertEquals("ALLOCATION_INSUFFICIENT", t.earmarkDirect("g", p(-701), confirmed = true).errorOrNull()?.code)
    }

    @Test
    fun `budget math`() {
        assertEquals(p(7), BudgetMath.funding(IncomeMode.FIXED, p(1), p(7), p(3)))
        assertEquals(p(3), BudgetMath.funding(IncomeMode.MANUAL, p(1), null, p(3)))
        assertThrows<IllegalArgumentException> { BudgetMath.funding(IncomeMode.FIXED, p(1), null, p(3)) }
        assertEquals(p(-200), BudgetMath.categoryRemaining(allocated = p(1_000), spent = p(1_200)))
        assertEquals(3333, BudgetMath.goalProgressBp(p(1), p(3)))
        assertEquals(10_000, BudgetMath.goalProgressBp(p(9), p(3)))
        assertEquals(0, BudgetMath.goalProgressBp(p(-5), p(3)))
        assertThrows<IllegalArgumentException> { BudgetMath.goalProgressBp(p(1), p(0)) }
        assertEquals(p(1_500), BudgetMath.freeMoney(php, listOf(p(1_000), p(2_000)), listOf(p(1_500))))
    }

    @Test
    fun `property - random allocation actions keep buckets balanced and never negative`() =
        runBlocking<Unit> {
            val op = Arb.triple(Arb.int(0..5), Arb.int(0..3), Arb.long(1L..1_000_000L))
            var applied = 0
            checkAll(3_000, Arb.list(op, 1..20)) { ops ->
                val ledger = Ledger(php)
                val t = AllocationTemplates(php, ledger)
                var externalIn = 0L
                val cats = listOf("food", "fun", "bills", "misc")
                val goals = listOf("g1", "g2", "g3", "g4")
                ops.forEach { (kind, i, raw) ->
                    val amount = p(raw)
                    val r: DomainResult<AllocationDraft> =
                        when (kind) {
                            0 -> t.fund(period, amount)
                            1 -> t.allocate(period, cats[i], amount)
                            2 -> t.reallocate(Bucket.Category(period, cats[i]), Bucket.Category(period, cats[(i + 1) % 4]), amount)
                            3 -> t.contributeToGoal(Bucket.Category(period, cats[i]), goals[i], amount)
                            4 -> t.withdrawFromGoal(goals[i], period, amount, confirmed = true)
                            else -> t.earmarkDirect(goals[i], if (i % 2 == 0) amount else -amount, confirmed = true)
                        }
                    val draft = (r as? DomainResult.Ok)?.value ?: return@forEach
                    if (draft.kind.singleSided) externalIn += draft.entries.sumOf { it.amount.minor }
                    ledger.apply(draft)
                    applied++
                    assertEquals(externalIn, ledger.entries.sumOf { it.amount.minor }, "only FUND/GOAL_DIRECT change the total")
                    (cats.map { Bucket.Category(period, it) } + goals.map { Bucket.Goal(it) }).forEach { b ->
                        assertTrue(!ledger.of(b).isNegative, "$b went negative")
                    }
                }
            }
            assertTrue(applied > 3_000, "applied $applied allocation actions")
        }
}
