package ph.financewallet.data.advanced

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.ledger.LedgerReads
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.PageCursor
import ph.financewallet.data.ledger.TransactionQueries
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.budget.Period
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.id.UuidV7Generator
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.EntryTemplates
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.LedgerCalculator
import ph.financewallet.domain.ledger.LedgerTransaction
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.ledger.TransactionDraft
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.report.RangeKind
import ph.financewallet.domain.report.ReportCalculator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * AC-ADV-6: on a random ledger built through the real use cases, every report figure from [ReportService] (SQL and
 * range-bounded queries) equals an independent recompute from the full history with the domain calculators.
 */
@RunWith(RobolectricTestRunner::class)
class ReportCrossCheckTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: LedgerService
    private lateinit var reports: ReportService
    private val clock = Clock.fixed(Instant.parse("2026-12-31T03:00:00Z"), ZoneOffset.UTC)
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "test-device")
    private val php = CurrencyCode.PHP
    private val year = Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    private fun p(minor: Long) = Money(minor, php)

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            ledger = LedgerService(db, ctx)
            reports = ReportService(db, ledger)
            ledger.ensureSystemAccounts()
        }

    @After
    fun tearDown() = db.close()

    /** Accounts and categories used by the random ledger. */
    private data class Fixture(
        val assets: List<String>,
        val realIds: List<String>,
        val parents: Map<String, String>,
    )

    @Test
    fun `every report figure equals an independent recompute`() =
        runBlocking<Unit> {
            val f = buildLedger()
            val all = allTransactions()
            val directory = ledger.directory()
            checkCategoriesAndBuckets(LedgerCalculator(directory), ReportCalculator(directory), all, f)
            checkCashFlowAndNetWorth(LedgerCalculator(directory), all, f)
        }

    private suspend fun buildLedger(): Fixture {
        val start = LocalDate.of(2025, 12, 1)
        val assets =
            listOf(
                FinancialAccountType.EWALLET,
                FinancialAccountType.BANK,
                FinancialAccountType.CASH,
                FinancialAccountType.SAVINGS,
            ).mapIndexed {
                i,
                t,
                ->
                ledger.createAccount(NewAccount("A$i", t, php, start, p(1_000_000L * (i + 1)))).getOrThrow()
            }
        val card = ledger.createAccount(NewAccount("Card", FinancialAccountType.CREDIT_CARD, php, start, p(50_000))).getOrThrow()
        val loan = ledger.createAccount(NewAccount("Loan", FinancialAccountType.LOAN, php, start, p(0))).getOrThrow()
        val food = ledger.createCategory("Food", AccountClass.EXPENSE).getOrThrow()
        val groceries = ledger.createCategory("Groceries", AccountClass.EXPENSE, food).getOrThrow()
        val transport = ledger.createCategory("Transport", AccountClass.EXPENSE).getOrThrow()
        val salary = ledger.createCategory("Salary", AccountClass.INCOME).getOrThrow()
        val expenses = listOf(food, groceries, transport)
        val rnd = Random(20261106)
        var recorded = 0
        repeat(OPS) { i ->
            val date = start.plusDays(rnd.nextLong(0, 420))
            val amount = p(rnd.nextLong(1, 2_000_000))
            val a = assets[rnd.nextInt(assets.size)]
            val b = assets[(assets.indexOf(a) + 1 + rnd.nextInt(assets.size - 1)) % assets.size]
            val result =
                ledger.record(
                    TxnHeader(date, "op$i"),
                ) { t, r -> randomDraft(t, r, Ops(assets, card, loan, salary, expenses), rnd, a, b, amount) }
            if (result is DomainResult.Ok) recorded++
        }
        assertEquals("most operations must succeed for a meaningful check", true, recorded > OPS * 3 / 4)
        return Fixture(assets, assets + card + loan, mapOf(groceries to food))
    }

    private class Ops(
        val assets: List<String>,
        val card: String,
        val loan: String,
        val salary: String,
        val expenses: List<String>,
    )

    private suspend fun randomDraft(
        t: EntryTemplates,
        r: LedgerReads,
        o: Ops,
        rnd: Random,
        a: String,
        b: String,
        amount: Money,
    ): DomainResult<TransactionDraft> =
        when (rnd.nextInt(9)) {
            0, 1 -> t.income(a, listOf(Split(o.salary, amount)))
            2, 3 -> t.expense(if (rnd.nextBoolean()) a else o.card, listOf(Split(o.expenses[rnd.nextInt(3)], amount)))
            4 -> t.transfer(a, b, amount)
            5 -> t.savingsContribution(a, o.assets[3], amount)
            6 -> t.debtPayment(a, o.loan, amount, p(amount.minor / 20), p(rnd.nextLong(0, 5_000)))
            7 -> t.debtDisbursement(a, o.loan, amount)
            else -> refundOrAdjustment(t, r, o, a, amount)
        }

    private suspend fun refundOrAdjustment(
        t: EntryTemplates,
        r: LedgerReads,
        o: Ops,
        a: String,
        amount: Money,
    ): DomainResult<TransactionDraft> {
        val last = lastExpense(r) ?: return t.balanceAdjustment(a, amount)
        val cat = last.entries.first { it.amount.isPositive && it.accountId in o.expenses }
        val payer = last.entries.first { it.amount.isNegative }.accountId
        return t.refund(payer, Money(minOf(amount.minor, cat.amount.minor), php), cat.accountId, r.refundOriginal(last.id))
    }

    private suspend fun checkCategoriesAndBuckets(
        calc: LedgerCalculator,
        independent: ReportCalculator,
        all: List<LedgerTransaction>,
        f: Fixture,
    ) {
        // spending and income by category (with roll-up)
        val spendingLines = reports.spendingByCategory(year, php)
        val expected = independent.rollUp(calc.spendingByCategory(year.start, year.endInclusive, all), f.parents::get)
        assertEquals(expected, spendingLines.associate { it.categoryId to it.total })
        val summary = calc.summary(year.start, year.endInclusive, all).getValue(php)
        assertEquals(summary.spending, Money.sum(spendingLines.map { it.total }, php))
        assertEquals(summary.income, Money.sum(reports.incomeByCategory(year, php).map { it.total }, php))

        // income vs expense buckets add up to the year
        val months = reports.incomeVsExpense(RangeKind.MONTH, year.start, year.endInclusive, php)
        assertEquals(12, months.size)
        assertEquals(summary.income, Money.sum(months.mapNotNull { it.summary?.income }, php))
        assertEquals(summary.spending, Money.sum(months.mapNotNull { it.summary?.spending }, php))

        // spending by account (range query) vs full history
        assertEquals(independent.spendingByAccount(year, all), reports.spendingByAccount(year))
    }

    private suspend fun checkCashFlowAndNetWorth(
        calc: LedgerCalculator,
        all: List<LedgerTransaction>,
        f: Fixture,
    ) {
        // cash flow per month: net = change of asset balances
        reports.cashFlow(RangeKind.MONTH, year.start, year.endInclusive, php).forEach { (period, flow) ->
            val before = f.assets.sumOf { calc.balance(it, period.start.minusDays(1), all).minor }
            val after = f.assets.sumOf { calc.balance(it, period.endInclusive, all).minor }
            assertEquals("cash flow ${period.start}", after - before, flow.net.minor)
        }

        // net worth and history
        listOf(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 12, 31)).forEach { d ->
            assertEquals(calc.netWorth(f.realIds, d, all).getValue(php), reports.netWorth(d).single().total)
        }
        reports.netWorthHistory(year.start, year.endInclusive, php).forEach { (d, nw) ->
            assertEquals("net worth $d", calc.netWorth(f.realIds, d, all).getValue(php), nw)
        }
    }

    private suspend fun lastExpense(r: LedgerReads): LedgerTransaction? {
        val page = TransactionQueries(db).page(limit = 20)
        return page.items.filter { it.type == TxnType.EXPENSE }.firstNotNullOfOrNull { r.transaction(it.id) }
    }

    private suspend fun allTransactions(): List<LedgerTransaction> {
        val q = TransactionQueries(db)
        val out = mutableListOf<LedgerTransaction>()
        var cursor: PageCursor? = null
        do {
            val page = q.page(cursor, limit = TransactionQueries.MAX_PAGE)
            page.items.forEach { out += checkNotNull(ledger.loadTransaction(it.id)) }
            cursor = page.next
        } while (cursor != null)
        return out
    }

    private companion object {
        const val OPS = 300
    }
}
