package ph.financewallet.domain.money

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AllocationTest {
    @Test
    fun `EX-1 split 100 pesos by 3333-3333-3334 bp`() {
        val r = Allocation.split(10_000L, listOf("a" to 3333L, "b" to 3333L, "c" to 3334L))
        assertEquals(listOf(3333L, 3333L, 3334L), r.values.toList())
    }

    @Test
    fun `EX-2 equal three-way split gives the extra centavo to the smallest key`() {
        val r = Allocation.split(10_000L, listOf("A" to 1L, "B" to 1L, "C" to 1L))
        assertEquals(listOf(3334L, 3333L, 3333L), r.values.toList())
    }

    @Test
    fun `EX-2 tie-break is by key, not by input position`() {
        val r = Allocation.split(10_000L, listOf("C" to 1L, "B" to 1L, "A" to 1L))
        assertEquals(mapOf("C" to 3333L, "B" to 3333L, "A" to 3334L), r)
        assertEquals(listOf("C", "B", "A"), r.keys.toList(), "result keeps input order")
    }

    @Test
    fun `EX-3 negative total keeps sign on every share`() {
        val r = Allocation.split(-5L, listOf("a" to 1L, "b" to 1L))
        assertEquals(listOf(-3L, -2L), r.values.toList())
    }

    @Test
    fun `EX-16 percentage allocation of 25000 pesos`() {
        val r =
            Allocation.split(
                Money(2_500_000L, CurrencyCode.PHP),
                listOf("Bills" to 5500L, "Food" to 3000L, "Transport" to 1500L),
            )
        assertEquals(1_375_000L, r.getValue("Bills").minor)
        assertEquals(750_000L, r.getValue("Food").minor)
        assertEquals(375_000L, r.getValue("Transport").minor)
    }

    @Test
    fun `zero weights receive nothing`() {
        val r = Allocation.split(101L, listOf("a" to 0L, "b" to 1L, "c" to 1L))
        assertEquals(listOf(0L, 51L, 50L), r.values.toList())
    }

    @Test
    fun `extreme totals do not overflow`() {
        val r = Allocation.split(Long.MAX_VALUE, listOf("a" to Long.MAX_VALUE, "b" to Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, Math.addExact(r.getValue("a"), r.getValue("b")))
        val n = Allocation.split(Long.MIN_VALUE + 1, listOf("a" to 1L, "b" to 2L))
        assertEquals(Long.MIN_VALUE + 1, Math.addExact(n.getValue("a"), n.getValue("b")))
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertThrows<IllegalArgumentException> { Allocation.split(1L, emptyList<Pair<String, Long>>()) }
        assertThrows<IllegalArgumentException> { Allocation.split(1L, listOf("a" to 0L)) }
        assertThrows<IllegalArgumentException> { Allocation.split(1L, listOf("a" to -1L, "b" to 2L)) }
        assertThrows<IllegalArgumentException> { Allocation.split(1L, listOf("a" to 1L, "a" to 2L)) }
    }

    @Test
    fun `property - shares always sum exactly to the total and are deterministic`() =
        runBlocking {
            checkAll(2_000, Arb.long(-1_000_000_000_000L..1_000_000_000_000L), Arb.list(Arb.int(0..10_000), 1..12)) { total, ws ->
                val weights = ws.mapIndexed { i, w -> "k%02d".format(i) to w.toLong() }
                if (weights.sumOf { it.second } == 0L) return@checkAll
                val first = Allocation.split(total, weights)
                val second = Allocation.split(total, weights)
                assertEquals(total, first.values.fold(0L) { a, b -> Math.addExact(a, b) })
                assertEquals(first, second)
                assertTrue(first.values.all { it == 0L || (it > 0) == (total > 0) })
            }
        }

    @Test
    fun `property - each share is within one minor unit of its exact proportion`() =
        runBlocking {
            checkAll(2_000, Arb.long(0L..10_000_000L), Arb.list(Arb.int(1..10_000), 1..8)) { total, ws ->
                val weights = ws.mapIndexed { i, w -> i to w.toLong() }
                val sum = weights.sumOf { it.second }
                Allocation.split(total, weights).forEach { (i, share) ->
                    val floor = total * weights[i].second / sum // fits in Long for these ranges
                    assertTrue(share == floor || share == floor + 1, "share $share vs floor $floor")
                }
            }
        }
}
