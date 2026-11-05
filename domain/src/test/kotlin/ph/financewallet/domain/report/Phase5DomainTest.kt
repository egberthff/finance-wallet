package ph.financewallet.domain.report

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.errorOrNull
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.debt.DebtMath
import ph.financewallet.domain.debt.ScheduleItem
import ph.financewallet.domain.ledger.LedgerFixture
import ph.financewallet.domain.ledger.LedgerTransaction
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.ledger.TransactionDraft
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence
import java.time.LocalDate

/** Debts (AC-ADV-1/2), report ranges and calculations (AC-ADV-6), net worth with valuations (AC-ADV-5). */
class Phase5DomainTest {
    private val f = LedgerFixture()
    private val reports = ReportCalculator(f.directory)
    private val nov = Period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))

    private fun d(s: String) = LocalDate.parse(s)

    // ---- debts ------------------------------------------------------------------------------------------------

    @Test
    fun `equal schedule sums exactly with the remainder on the last item`() {
        val items = DebtMath.equalSchedule(f.p(1_000_000), 3, Recurrence.Monthly(d("2026-12-15"), 15)).getOrThrow()
        assertEquals(listOf(333_333L, 333_333L, 333_334L), items.map { it.amountDue.minor })
        assertEquals(listOf("2026-12-15", "2027-01-15", "2027-02-15"), items.map { it.dueDate.toString() })
        assertEquals(listOf(1, 2, 3), items.map { it.seq })
        assertEquals(2, DebtMath.remaining(items, setOf(1)))
    }

    @Test
    fun `long schedules span many years and still sum exactly`() {
        val items = DebtMath.equalSchedule(f.p(123_456_789), 360, Recurrence.Monthly(d("2026-01-31"), 31)).getOrThrow()
        assertEquals(360, items.size)
        assertEquals(123_456_789L, items.sumOf { it.amountDue.minor })
        assertEquals(d("2055-12-31"), items.last().dueDate)
    }

    @Test
    fun `schedule validation and outstanding`() {
        val r = Recurrence.Monthly(d("2026-12-15"), 15)
        assertEquals("AMOUNT_NOT_POSITIVE", DebtMath.equalSchedule(f.p(0), 3, r).errorOrNull()?.code)
        assertEquals("INSTALLMENTS_OUT_OF_RANGE", DebtMath.equalSchedule(f.p(100), 0, r).errorOrNull()?.code)
        assertEquals("INSTALLMENTS_OUT_OF_RANGE", DebtMath.equalSchedule(f.p(100_000), 601, r).errorOrNull()?.code)
        assertEquals("INSTALLMENTS_EXCEED_AMOUNT", DebtMath.equalSchedule(f.p(2), 3, r).errorOrNull()?.code)
        assertEquals(f.p(250_000), DebtMath.outstanding(f.p(-250_000)))
        assertEquals(0, DebtMath.remaining(listOf(ScheduleItem(1, d("2026-01-01"), f.p(1))), setOf(1)))
    }

    @Test
    fun `debt payment splits principal from interest and fees, disbursement is not income (AC-ADV-1)`() {
        val t = f.templates
        val txns =
            listOf(
                f.postOk(t.debtDisbursement("bpi", "loan", f.p(5_000_000)), d("2026-11-02")),
                f.postOk(t.debtPayment("bpi", "loan", f.p(400_000), f.p(50_000), f.p(5_000)), d("2026-11-20")),
            )
        val s = f.calculator.summary(nov.start, nov.endInclusive, txns).getValue(f.php)
        assertEquals(f.p(0), s.income)
        assertEquals(f.p(55_000), s.spending)
        assertEquals(f.p(4_600_000), DebtMath.outstanding(f.calculator.balance("loan", nov.endInclusive, txns)))
        assertEquals(mapOf("bpi" to f.p(55_000)), reports.spendingByAccount(nov, txns))
    }

    // ---- ranges ----------------------------------------------------------------------------------------------

    @Test
    fun `calendar ranges and buckets`() {
        assertEquals(Period(d("2026-11-02"), d("2026-11-08")), ReportRanges.containing(RangeKind.WEEK, d("2026-11-05")))
        assertEquals(Period(d("2028-02-01"), d("2028-02-29")), ReportRanges.containing(RangeKind.MONTH, d("2028-02-10")))
        assertEquals(Period(d("2026-10-01"), d("2026-12-31")), ReportRanges.containing(RangeKind.QUARTER, d("2026-11-05")))
        assertEquals(Period(d("2026-01-01"), d("2026-12-31")), ReportRanges.containing(RangeKind.YEAR, d("2026-11-05")))
        assertEquals(
            listOf(
                Period(d("2026-11-15"), d("2026-11-30")),
                Period(d("2026-12-01"), d("2026-12-31")),
                Period(d("2027-01-01"), d("2027-01-10")),
            ),
            ReportRanges.buckets(RangeKind.MONTH, d("2026-11-15"), d("2027-01-10")),
        )
        assertEquals(53, ReportRanges.buckets(RangeKind.WEEK, d("2026-01-01"), d("2026-12-31")).size)
        assertThrows<IllegalArgumentException> { ReportRanges.buckets(RangeKind.WEEK, d("2026-02-01"), d("2026-01-01")) }
    }

    // ---- reports -----------------------------------------------------------------------------------------------

    @Test
    fun `subcategory spending rolls up to the parent`() {
        val parents = mapOf("groceries" to "food", "dining" to "food")
        val rolled =
            reports.rollUp(mapOf("groceries" to f.p(300), "dining" to f.p(200), "food" to f.p(50), "transport" to f.p(0)), parents::get)
        assertEquals(mapOf("food" to f.p(550)), rolled)
    }

    @Test
    fun `refunds reduce the paying account's spending, card spending belongs to the card`() {
        val t = f.templates
        val expense = f.postOk(t.expense("gcash", listOf(Split("food", f.p(10_000)))))
        val txns =
            listOf(
                expense,
                f.postOk(
                    t.refund(
                        "gcash",
                        f.p(2_500),
                        "food",
                        ph.financewallet.domain.ledger
                            .RefundOriginal(expense, f.p(0)),
                    ),
                ),
                f.postOk(t.expense("card", listOf(Split("shopping", f.p(7_000))))),
                f.postOk(t.transfer("gcash", "bpi", f.p(1_000))),
            )
        assertEquals(mapOf("gcash" to f.p(7_500), "card" to f.p(7_000)), reports.spendingByAccount(nov, txns))
    }

    @Test
    fun `cash flow ignores moves between own asset accounts but counts paying the card`() {
        val t = f.templates
        val txns =
            listOf(
                f.postOk(t.income("bpi", listOf(Split("salary", f.p(3_000_000))))),
                f.postOk(t.transfer("bpi", "gcash", f.p(500_000))),
                f.postOk(t.savingsContribution("bpi", "bpi-savings", f.p(200_000))),
                f.postOk(t.expense("gcash", listOf(Split("food", f.p(45_000))))),
                f.postOk(t.expense("card", listOf(Split("shopping", f.p(120_000))))),
                f.postOk(t.transfer("bpi", "card", f.p(120_000))),
            )
        val flow = reports.cashFlow(nov, f.php, txns)
        assertEquals(f.p(3_000_000), flow.inflow)
        assertEquals(f.p(165_000), flow.outflow)
        assertEquals(f.p(2_835_000), flow.net)
    }

    /** AC-ADV-6: on random ledgers, net cash flow equals the change in total asset balances; account spending sums to total spending. */
    @Test
    fun `cash flow and spending by account agree with independent recomputes`() =
        runBlocking<Unit> {
            val assets = listOf("gcash", "bpi", "bpi-savings", "cash")
            var checked = 0
            checkAll(
                1_000,
                Arb.list(Arb.triple(Arb.int(0..99), Arb.int(0..1_000), Arb.long(1L..5_000_000L)), 1..40),
            ) { ops ->
                val fx = LedgerFixture()
                val txns = mutableListOf<LedgerTransaction>()
                ops.forEach { (kind, pick, amount) ->
                    val draft = randomDraft(fx, txns, kind, pick, amount) ?: return@forEach
                    txns += fx.post(draft, d("2026-01-01").plusDays((pick % 365).toLong()))
                }
                val calc = ReportCalculator(fx.directory)
                val year = Period(d("2026-03-01"), d("2026-09-30"))
                val before = assets.sumOf { fx.calculator.balance(it, year.start.minusDays(1), txns).minor }
                val after = assets.sumOf { fx.calculator.balance(it, year.endInclusive, txns).minor }
                assertEquals(after - before, calc.cashFlow(year, fx.php, txns).net.minor)
                val spending =
                    fx.calculator
                        .summary(year.start, year.endInclusive, txns)[fx.php]
                        ?.spending
                        ?.minor ?: 0L
                assertEquals(spending, calc.spendingByAccount(year, txns).values.sumOf { it.minor })
                checked++
            }
            assertEquals(1_000, checked)
        }

    private fun randomDraft(
        fx: LedgerFixture,
        txns: List<LedgerTransaction>,
        kind: Int,
        pick: Int,
        amount: Long,
    ): TransactionDraft? {
        val t = fx.templates
        val assets = listOf("gcash", "bpi", "bpi-savings", "cash")
        val result: DomainResult<TransactionDraft> =
            when (kind % 8) {
                0 -> {
                    t.income(assets[pick % 4], listOf(Split("salary", fx.p(amount))))
                }

                1 -> {
                    t.expense((assets + "card")[pick % 5], listOf(Split(listOf("food", "transport")[pick % 2], fx.p(amount))))
                }

                2 -> {
                    t.transfer(assets[pick % 4], assets[(pick + 1) % 4], fx.p(amount))
                }

                3 -> {
                    t.transfer("bpi", "card", fx.p(amount))
                }

                4 -> {
                    t.debtDisbursement("bpi", "loan", fx.p(amount))
                }

                5 -> {
                    t.debtPayment("gcash", "loan", fx.p(amount), fx.p(amount / 10), fx.p(pick.toLong()))
                }

                6 -> {
                    t.balanceAdjustment(assets[pick % 4], fx.p(if (pick % 2 == 0) amount else -amount))
                }

                else -> {
                    val last = txns.lastOrNull { it.type == ph.financewallet.domain.ledger.TxnType.EXPENSE } ?: return null
                    val cat = last.entries.first { it.amount.isPositive && it.accountId in setOf("food", "transport") }
                    t.refund(
                        last.entries.first { it.amount.isNegative }.accountId,
                        Money(minOf(amount, cat.amount.minor), fx.php),
                        cat.accountId,
                        ph.financewallet.domain.ledger
                            .RefundOriginal(last, fx.p(0)),
                    )
                }
            }
        return (result as? DomainResult.Ok)?.value
    }

    // ---- net worth ---------------------------------------------------------------------------------------------

    @Test
    fun `valuations use the latest value on or before the date, per currency`() {
        val vals =
            listOf(
                Valuation("house", d("2026-01-01"), f.p(500_000_000)),
                Valuation("house", d("2026-06-01"), f.p(550_000_000)),
                Valuation("car", d("2026-03-01"), f.p(80_000_000)),
                Valuation("fund", d("2026-02-01"), f.u(100_000)),
            )
        assertEquals(mapOf(f.php to f.p(500_000_000)), NetWorthCalculator.valuationTotals(vals, d("2026-02-01")).filterKeys { it == f.php })
        assertEquals(f.p(630_000_000), NetWorthCalculator.valuationTotals(vals, d("2026-12-31")).getValue(f.php))
        assertEquals(f.u(100_000), NetWorthCalculator.valuationTotals(vals, d("2026-12-31")).getValue(f.usd))
        assertEquals(
            mapOf(f.php to f.p(630_100_000), f.usd to f.u(100_000)),
            NetWorthCalculator.combine(mapOf(f.php to f.p(100_000)), NetWorthCalculator.valuationTotals(vals, d("2026-12-31"))),
        )
    }

    @Test
    fun `month ends end at the given date`() {
        assertEquals(
            listOf(d("2026-11-30"), d("2026-12-31"), d("2027-01-15")),
            NetWorthCalculator.monthEnds(d("2026-11-05"), d("2027-01-15")),
        )
        assertThrows<IllegalArgumentException> { NetWorthCalculator.monthEnds(d("2026-02-01"), d("2026-01-01")) }
    }
}
