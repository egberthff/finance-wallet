package ph.financewallet.data.insight

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.financewallet.data.advanced.RecurringService
import ph.financewallet.data.advanced.ReportService
import ph.financewallet.data.advanced.RuleKind
import ph.financewallet.data.advanced.RuleSpec
import ph.financewallet.data.budget.GoalService
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.id.UuidV7Generator
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** INS-01..03 against Room: each insight is reproducible from the recorded transactions, and nothing is written. */
@RunWith(RobolectricTestRunner::class)
class InsightsServiceTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: LedgerService
    private lateinit var insights: InsightsService
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC) // Nov 20, Manila
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "d")
    private val php = CurrencyCode.PHP
    private var n = 0

    private fun p(minor: Long) = Money(minor, php)

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            ledger = LedgerService(db, ctx).also { it.ensureSystemAccounts() }
            val recurring = RecurringService(db, ctx, ledger)
            insights = InsightsService(db, ctx, ledger, ReportService(db, ledger), recurring, GoalService(db, ctx, ledger))
        }

    @After
    fun tearDown() = db.close()

    private suspend fun spend(
        account: String,
        category: String,
        amount: Long,
        date: LocalDate,
    ) = ledger.record(TxnHeader(date, "k${++n}")) { t, _ -> t.expense(account, listOf(Split(category, p(amount)))) }.getOrThrow()

    /** Four months of salary, transport, ordinary meals, one unusual dinner, a savings deposit and a bill; returns (food, dinner). */
    private suspend fun seed(): Pair<String, String> {
        val bpi =
            ledger
                .createAccount(
                    NewAccount("BPI", FinancialAccountType.BANK, php, LocalDate.of(2026, 7, 1), p(10_000_000)),
                ).getOrThrow()
        val savings =
            ledger
                .createAccount(
                    NewAccount("Savings", FinancialAccountType.SAVINGS, php, LocalDate.of(2026, 7, 1), p(0)),
                ).getOrThrow()
        val salary = ledger.createCategory("Salary", AccountClass.INCOME).getOrThrow()
        val food = ledger.createCategory("Food", AccountClass.EXPENSE).getOrThrow()
        val transport = ledger.createCategory("Transport", AccountClass.EXPENSE).getOrThrow()
        for (m in 8..11) {
            ledger
                .record(
                    TxnHeader(LocalDate.of(2026, m, 15), "sal$m"),
                ) { t, _ -> t.income(bpi, listOf(Split(salary, p(3_000_000)))) }
                .getOrThrow()
        }
        // Aug–Oct transport 2,000,000 each month
        for (m in 8..10) spend(bpi, transport, 2_000_000, LocalDate.of(2026, m, 3))
        // food history: five ordinary meals in the 90 days before November
        listOf(25_000L, 30_000, 22_000, 28_000, 25_000).forEachIndexed { i, a -> spend(bpi, food, a, LocalDate.of(2026, 10, 1 + i)) }
        spend(bpi, food, 40_000, LocalDate.of(2026, 10, 10)) // same days of October: food 1,700 total
        // November so far: food 5,700: an ordinary 700 meal and one unusual 5,000 dinner
        spend(bpi, food, 70_000, LocalDate.of(2026, 11, 3))
        val dinner = spend(bpi, food, 500_000, LocalDate.of(2026, 11, 8))
        ledger
            .record(
                TxnHeader(LocalDate.of(2026, 11, 16), "sv"),
            ) { t, _ -> t.savingsContribution(bpi, savings, p(500_000)) }
            .getOrThrow()
        RecurringService(db, ctx, ledger)
            .createRule(
                RuleSpec(
                    RuleKind.BILL,
                    "Internet",
                    p(169_900),
                    Recurrence.Monthly(LocalDate.of(2026, 11, 1), 25),
                    accountId = bpi,
                    categoryId = transport,
                ),
            ).getOrThrow()
        return food to dinner
    }

    @Test
    fun `month flow, top categories, increases, unusual expenses, bills and saving capacity`() =
        runBlocking<Unit> {
            val (food, dinner) = seed()
            val countBefore =
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM txn").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            val r = insights.report()
            assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 20), r.period.start to r.period.endInclusive)
            assertEquals(LocalDate.of(2026, 10, 20), r.previousPeriod.endInclusive)
            assertEquals(MonthFlow(p(3_000_000), p(570_000), p(500_000)), r.flow)
            assertEquals(p(1_930_000), r.flow.leftOver)
            assertEquals(listOf(food to p(570_000)), r.topCategories)
            assertEquals(listOf(food), r.increases.map { it.categoryId })
            assertEquals(listOf(dinner), r.unusual.map { it.expense.txnId })
            assertEquals(p(169_900), r.bills.dueSoon)
            assertEquals(p(0), r.bills.shortfall)
            // Aug, Sep: 30,000 − 20,000; Oct: 30,000 − 20,000 − 1,700 food -> (10,000 + 10,000 + 8,300) / 3, floored
            assertEquals(p(943_333), r.savingCapacity?.perMonth)
            assertEquals(3, r.savingCapacity?.monthsUsed)
            assertEquals("Food", r.categoryNames[food])
            val countAfter =
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM txn").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            assertEquals("insights never create records (INS-03)", countBefore, countAfter)
        }

    @Test
    fun `an empty ledger gives empty insights, not errors`() =
        runBlocking<Unit> {
            val r = insights.report()
            assertTrue(r.topCategories.isEmpty() && r.increases.isEmpty() && r.unusual.isEmpty())
            assertEquals(null, r.savingCapacity)
            assertEquals(p(0), r.flow.leftOver)
        }
}
