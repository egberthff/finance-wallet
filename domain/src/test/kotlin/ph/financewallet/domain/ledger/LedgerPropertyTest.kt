package ph.financewallet.domain.ledger

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.money.CurrencyCode
import java.time.LocalDate

/**
 * AC-CORE-2 / EX-9: for any sequence of valid operations, per currency,
 *   Σ real-account balances = income − spending + openings + adjustments + fx,
 * and transfers never change income, spending or net worth.
 */
class LedgerPropertyTest {
    private val start = LocalDate.of(2026, 1, 1)
    private val end = LocalDate.of(2026, 12, 31)

    private val phpAssets = listOf("gcash", "bpi", "bpi-savings", "cash")
    private val categories = listOf("food", "transport", "shopping")

    /** One random operation, encoded as (kind, a, b) and decoded against the current ledger. */
    private fun apply(
        f: LedgerFixture,
        txns: MutableList<LedgerTransaction>,
        op: Triple<Int, Int, Long>,
    ) {
        val (_, pick, _) = op
        val result = draftFor(f, txns, op)
        val draft = (result as? DomainResult.Ok)?.value ?: return
        val before = f.calculator.summary(start, end, txns)
        val netWorthBefore = f.calculator.netWorth(f.phpRealAccounts, end, txns)
        val tx = f.post(draft, start.plusDays((pick % 365).toLong()))
        txns += tx
        assertEquals(
            0L,
            tx.entries
                .groupBy { it.amount.currency }
                .values
                .sumOf { g -> g.sumOf { it.amount.minor } },
            "I-1",
        )
        if (tx.type == TxnType.TRANSFER && tx.entries.size == 2) {
            assertEquals(before, f.calculator.summary(start, end, txns), "transfer changed income/spending")
            assertEquals(netWorthBefore, f.calculator.netWorth(f.phpRealAccounts, end, txns), "transfer changed net worth")
        }
        assertIdentity(f, txns)
    }

    private fun draftFor(
        f: LedgerFixture,
        txns: List<LedgerTransaction>,
        op: Triple<Int, Int, Long>,
    ): DomainResult<TransactionDraft>? {
        val (kind, pick, raw) = op
        val amount = f.p(raw)
        val t = f.templates
        return when (kind % OPERATION_KINDS) {
            0 -> t.income(phpAssets[pick % 4], listOf(Split("salary", amount)))
            1 -> t.expense((phpAssets + "card")[pick % 5], listOf(Split(categories[pick % 3], amount)))
            2 -> t.transfer(phpAssets[pick % 4], phpAssets[(pick + 1) % 4], amount)
            3 -> refundOfLastExpense(f, txns, amount)
            4 -> lastReversible(txns)?.let { t.reversal(it, alreadyReversed = false) }
            5 -> t.balanceAdjustment(phpAssets[pick % 4], if (pick % 2 == 0) amount else -amount)
            6 -> t.debtDisbursement("bpi", "loan", amount)
            7 -> t.debtPayment("bpi", "loan", amount, f.p(raw / 10), f.p(pick.toLong() % 50))
            8 -> t.transfer("bpi", "card", amount)
            9 -> t.crossCurrencyTransfer("bpi", amount, "usd-bank", f.u(raw / 56 + 1))
            else -> t.openingBalance("cash", amount)
        }
    }

    private fun refundOfLastExpense(
        f: LedgerFixture,
        txns: List<LedgerTransaction>,
        amount: ph.financewallet.domain.money.Money,
    ): DomainResult<TransactionDraft>? {
        val original = txns.lastOrNull { it.type == TxnType.EXPENSE && it.isEffective } ?: return null
        val prior =
            txns.filter { it.refundOfTxnId == original.id }.sumOf { r ->
                r.entries
                    .first()
                    .amount.minor
            }
        val account = original.entries.first().accountId
        return f.templates.refund(account, amount, original = RefundOriginal(original, f.p(prior)))
    }

    private fun lastReversible(txns: List<LedgerTransaction>): LedgerTransaction? {
        val reversed = txns.mapNotNull { it.reversesTxnId }.toSet()
        return txns.lastOrNull { it.type != TxnType.REVERSAL && it.id !in reversed && it.isEffective }
    }

    private fun assertIdentity(
        f: LedgerFixture,
        txns: List<LedgerTransaction>,
    ) {
        val summaries = f.calculator.summary(start, end, txns)
        val realBalances =
            f.calculator.netWorth(f.phpRealAccounts + "usd-bank", end, txns)
        for (currency in listOf(f.php, f.usd)) {
            val s = summaries[currency]
            val expected = if (s == null) 0L else s.income.minor - s.spending.minor + s.openings.minor + s.adjustments.minor + s.fx.minor
            assertEquals(expected, realBalances[currency]?.minor ?: 0L, "ledger identity for $currency")
        }
        assertTrue(realBalances.keys.all { it in setOf(CurrencyCode.PHP, f.usd) })
    }

    @Test
    fun `AC-CORE-2 ledger identity holds after every step of 10,000 random sequences`() =
        runBlocking<Unit> {
            val op = Arb.triple(Arb.int(0..100), Arb.int(0..10_000), Arb.long(1L..5_000_000L))
            var sequences = 0
            var operations = 0
            checkAll(10_000, Arb.list(op, 1..12)) { ops ->
                val f = LedgerFixture()
                val txns = mutableListOf<LedgerTransaction>()
                ops.forEach { apply(f, txns, it) }
                sequences++
                operations += txns.size
            }
            assertEquals(10_000, sequences, "every generated sequence was checked")
            assertTrue(operations > 10_000, "applied $operations valid operations")
            println("LedgerPropertyTest: $sequences sequences, $operations operations applied")
        }

    private companion object {
        const val OPERATION_KINDS = 11
    }
}
