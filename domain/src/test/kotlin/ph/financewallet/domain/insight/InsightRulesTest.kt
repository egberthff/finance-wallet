package ph.financewallet.domain.insight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** INS-01/02: every insight rule with its thresholds, edges included. */
class InsightRulesTest {
    private val php = CurrencyCode.PHP

    private fun p(minor: Long) = Money(minor, php)

    @Test
    fun `category increases need both 25 percent and 500 pesos`() {
        val changes =
            InsightRules.categoryChanges(
                current =
                    mapOf(
                        "food" to p(1_000_000),
                        "transport" to p(260_000),
                        "bills" to p(150_000),
                        "new" to p(50_000),
                        "small" to p(49_999),
                    ),
                previous = mapOf("food" to p(700_000), "transport" to p(240_000), "bills" to p(100_000)),
                php,
            )
        // food +3,000 (+42.85 %), bills +500 (+50 %), new +500 from nothing; transport +200 too small; small < 500
        assertEquals(listOf("food", "bills", "new"), changes.map { it.categoryId })
        assertEquals(4_285L, changes.first().increaseBp)
        assertNull(changes.last().increaseBp)
        val edge = InsightRules.categoryChanges(mapOf("x" to p(250_000)), mapOf("x" to p(200_000)), php)
        assertEquals(2_500L, edge.single().increaseBp) // exactly 25 % and exactly ₱500 counts
    }

    @Test
    fun `unusual expenses are far above the category median with enough history`() {
        val history = mapOf("food" to listOf(20_000L, 25_000, 30_000, 25_000, 22_000, 90_000), "rare" to listOf(10_000L))
        val recent =
            listOf(
                RecentExpense("t1", "food", LocalDate.of(2026, 11, 5), p(150_000)),
                RecentExpense("t2", "food", LocalDate.of(2026, 11, 6), p(70_000)), // ≤ 3× median (25,000) is not unusual
                RecentExpense("t3", "rare", LocalDate.of(2026, 11, 6), p(500_000)), // too little history
                RecentExpense("t4", "food", LocalDate.of(2026, 11, 6), p(90_000)), // below ₱1,000
            )
        val result = InsightRules.unusual(recent, history)
        assertEquals(listOf("t1"), result.map { it.expense.txnId })
        assertEquals(p(25_000), result.single().typical)
        assertEquals(25_000L, InsightRules.median(listOf(30_000, 20_000, 25_000, 40_000)))
        assertThrows<IllegalArgumentException> { InsightRules.median(emptyList()) }
    }

    @Test
    fun `saving capacity is the floored average and may be negative`() {
        val s = InsightRules.savingCapacity(listOf(p(3_000_000) to p(2_000_000), p(3_000_000) to p(3_100_001)), php)
        assertEquals(p(449_999), s?.perMonth) // (1,000,000 − 100,001) / 2, floored
        assertEquals(2, s?.monthsUsed)
        assertEquals(p(-1), InsightRules.savingCapacity(listOf(p(0) to p(1)), php)?.perMonth)
        assertNull(InsightRules.savingCapacity(emptyList(), php))
    }

    @Test
    fun `bills coverage and top categories`() {
        assertEquals(p(30_000), BillsCoverage(p(130_000), p(100_000)).shortfall)
        assertEquals(p(0), BillsCoverage(p(50_000), p(100_000)).shortfall)
        assertEquals(
            listOf("a" to p(300), "c" to p(300)),
            InsightRules.top(mapOf("b" to p(100), "a" to p(300), "c" to p(300), "z" to p(0)), 2),
        )
    }
}
