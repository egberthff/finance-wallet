package ph.financewallet.domain.allocation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money

/** BUD-07 rollover table (docs/10-domain-model.md §5.5), every policy × sign, plus the entries it produces. */
class RolloverTest {
    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    private fun plan(
        policy: RolloverPolicy,
        remaining: Long,
        carryNegative: Boolean = false,
        hasNext: Boolean = true,
    ) = RolloverPlanner.plan(policy, carryNegative, p(remaining), sweepGoalId = "goal", hasNextPeriod = hasNext)

    @ParameterizedTest
    @EnumSource(RolloverPolicy::class)
    fun `zero remaining never writes anything`(policy: RolloverPolicy) {
        assertEquals(RolloverAction.None, plan(policy, 0, carryNegative = true))
    }

    @Test
    fun `EXPIRE lapses both leftovers and overspends`() {
        assertEquals(RolloverAction.None, plan(RolloverPolicy.EXPIRE, 500))
        assertEquals(RolloverAction.None, plan(RolloverPolicy.EXPIRE, -500, carryNegative = true))
    }

    @Test
    fun `positive leftover per policy`() {
        assertEquals(RolloverAction.Carry(p(500)), plan(RolloverPolicy.CARRY, 500))
        assertEquals(RolloverAction.Sweep("goal", p(500)), plan(RolloverPolicy.SWEEP_TO_GOAL, 500))
        assertEquals(RolloverAction.ToReassign(p(500)), plan(RolloverPolicy.MANUAL, 500))
    }

    @Test
    fun `CARRY without a next period goes to reassign instead of lapsing`() {
        assertEquals(RolloverAction.ToReassign(p(500)), plan(RolloverPolicy.CARRY, 500, hasNext = false))
    }

    @ParameterizedTest
    @EnumSource(value = RolloverPolicy::class, names = ["CARRY", "SWEEP_TO_GOAL", "MANUAL"])
    fun `overspend is carried only with carryNegative and a next period`(policy: RolloverPolicy) {
        assertEquals(RolloverAction.None, plan(policy, -300))
        assertEquals(RolloverAction.Carry(p(-300)), plan(policy, -300, carryNegative = true))
        assertEquals(RolloverAction.None, plan(policy, -300, carryNegative = true, hasNext = false))
    }

    @Test
    fun `sweep without a goal is a programming error`() {
        assertThrows<IllegalArgumentException> {
            RolloverPlanner.plan(RolloverPolicy.SWEEP_TO_GOAL, false, p(1), sweepGoalId = null, hasNextPeriod = true)
        }
    }

    @Test
    fun `carry entries move R from this period's category to the next and sum to zero`() {
        val entries = RolloverPlanner.entries(RolloverAction.Carry(p(500)), "P1", "P2", "food")
        assertEquals(
            listOf(AllocationEntry(Bucket.Category("P1", "food"), p(-500)), AllocationEntry(Bucket.Category("P2", "food"), p(500))),
            entries,
        )
        val overspend = RolloverPlanner.entries(RolloverAction.Carry(p(-300)), "P1", "P2", "food")
        assertEquals(listOf(p(300), p(-300)), overspend.map { it.amount })
    }

    @Test
    fun `sweep entries move R into the goal`() {
        val entries = RolloverPlanner.entries(RolloverAction.Sweep("g", p(700)), "P1", null, "food")
        assertEquals(
            listOf(AllocationEntry(Bucket.Category("P1", "food"), p(-700)), AllocationEntry(Bucket.Goal("g"), p(700))),
            entries,
        )
    }

    @Test
    fun `no entries for none or reassign`() {
        assertEquals(emptyList<AllocationEntry>(), RolloverPlanner.entries(RolloverAction.None, "P1", "P2", "food"))
        assertEquals(emptyList<AllocationEntry>(), RolloverPlanner.entries(RolloverAction.ToReassign(p(1)), "P1", "P2", "food"))
        assertThrows<IllegalArgumentException> { RolloverPlanner.entries(RolloverAction.Carry(p(1)), "P1", null, "food") }
    }
}
