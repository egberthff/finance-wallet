package ph.financewallet.domain.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ph.financewallet.domain.common.getOrThrow
import java.time.LocalDate

class LedgerCalculatorTest {
    private val f = LedgerFixture()
    private val t = f.templates
    private val c = f.calculator
    private val nov5 = LocalDate.of(2026, 11, 5)
    private val far = LocalDate.of(2100, 1, 1)

    private fun summary(txns: List<LedgerTransaction>) =
        c.summary(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), txns).getValue(f.php)

    @Test
    fun `EX-5 transfer changes balances but not income, spending or net worth (AC-CORE-1)`() {
        val opening = listOf(f.postOk(t.openingBalance("gcash", f.p(500_000))), f.postOk(t.openingBalance("bpi", f.p(100_000))))
        val before = c.netWorth(f.phpRealAccounts, far, opening)
        val txns = opening + f.postOk(t.transfer("gcash", "bpi", f.p(200_000)))
        assertEquals(f.p(300_000), c.balance("gcash", far, txns))
        assertEquals(f.p(300_000), c.balance("bpi", far, txns))
        val s = summary(txns)
        assertEquals(f.p(0), s.income)
        assertEquals(f.p(0), s.spending)
        assertEquals(before, c.netWorth(f.phpRealAccounts, far, txns))
    }

    @Test
    fun `EX-6 card spending counted once, at purchase`() {
        val txns =
            listOf(
                f.postOk(t.openingBalance("bpi", f.p(500_000))),
                f.postOk(t.expense("card", listOf(Split("shopping", f.p(120_000))))),
                f.postOk(t.transfer("bpi", "card", f.p(120_000))),
            )
        assertEquals(f.p(120_000), summary(txns).spending)
        assertEquals(f.p(0), c.balance("card", far, txns))
        assertEquals(f.p(380_000), c.netWorth(f.phpRealAccounts, far, txns).getValue(f.php))
    }

    @Test
    fun `EX-7 linked refund reduces category spending and is not income (AC-CORE-7)`() {
        val expense = f.postOk(t.expense("gcash", listOf(Split("food", f.p(50_000)))))
        val refund = f.postOk(t.refund("gcash", f.p(20_000), original = RefundOriginal(expense, f.p(0))))
        val txns = listOf(expense, refund)
        assertEquals(f.p(30_000), summary(txns).spending)
        assertEquals(f.p(0), summary(txns).income)
        assertEquals(mapOf("food" to f.p(30_000)), c.spendingByCategory(nov5, nov5, txns))
    }

    @Test
    fun `EX-8 loan principal is not spending, interest is`() {
        val txns =
            listOf(
                f.postOk(t.debtDisbursement("bpi", "loan", f.p(1_000_000))),
                f.postOk(t.debtPayment("bpi", "loan", f.p(100_000), f.p(10_000), f.p(0))),
            )
        val s = summary(txns)
        assertEquals(f.p(0), s.income, "disbursement is not income")
        assertEquals(f.p(10_000), s.spending)
        assertEquals(f.p(-900_000), c.balance("loan", far, txns))
        assertEquals(f.p(890_000), c.balance("bpi", far, txns))
    }

    @Test
    fun `balances respect status, deletion and as-of date`() {
        val posted = f.postOk(t.income("bpi", listOf(Split("salary", f.p(1_000)))), nov5)
        val future = f.postOk(t.income("bpi", listOf(Split("salary", f.p(200)))), nov5.plusDays(10))
        val pending = f.post(t.expense("bpi", listOf(Split("food", f.p(50)))).getOrThrow(), nov5, TxnStatus.PENDING)
        val deleted = f.postOk(t.expense("bpi", listOf(Split("food", f.p(70))))).copy(deleted = true)
        val txns = listOf(posted, future, pending, deleted)
        assertEquals(f.p(1_000), c.balance("bpi", nov5, txns), "current")
        assertEquals(f.p(1_200), c.balance("bpi", far, txns), "projected includes future-dated")
        assertEquals(f.p(-50), c.pendingBalance("bpi", txns))
        assertEquals(f.p(1_000), summary(listOf(posted, pending, deleted)).income)
        assertEquals(f.p(0), summary(listOf(posted, pending, deleted)).spending)
    }

    @Test
    fun `summary separates adjustments, openings and fx, and filters by date`() {
        val txns =
            listOf(
                f.postOk(t.openingBalance("cash", f.p(10_000)), nov5),
                f.postOk(t.balanceAdjustment("cash", f.p(-300)), nov5),
                f.postOk(t.crossCurrencyTransfer("bpi", f.p(56_000), "usd-bank", f.u(1_000)), nov5),
                f.postOk(t.expense("cash", listOf(Split("food", f.p(999)))), LocalDate.of(2025, 12, 31)),
            )
        val all = c.summary(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), txns)
        val s = all.getValue(f.php)
        assertEquals(f.p(10_000), s.openings)
        assertEquals(f.p(-300), s.adjustments)
        assertEquals(f.p(-56_000), s.fx)
        assertEquals(f.p(0), s.spending, "2025 expense is outside the range")
        assertEquals(f.u(1_000), all.getValue(f.usd).fx)
        assertThrows<IllegalArgumentException> { c.summary(nov5, nov5.minusDays(1), txns) }
    }

    @Test
    fun `net worth is per currency and liabilities subtract`() {
        val txns =
            listOf(
                f.postOk(t.openingBalance("bpi", f.p(1_000_000))),
                f.postOk(t.openingBalance("card", f.p(-250_000))),
                f.postOk(t.openingBalance("usd-bank", f.u(50_000))),
            )
        val nw = c.netWorth(f.phpRealAccounts + "usd-bank", far, txns)
        assertEquals(f.p(750_000), nw.getValue(f.php))
        assertEquals(f.u(50_000), nw.getValue(f.usd))
    }

    @Test
    fun `calculator rejects unknown or nominal accounts for balances`() {
        assertThrows<IllegalArgumentException> { c.balance("food", far, emptyList()) }
        assertThrows<IllegalArgumentException> { c.balance("ghost", far, emptyList()) }
    }
}
