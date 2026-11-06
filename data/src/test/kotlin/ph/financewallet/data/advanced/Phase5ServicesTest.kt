package ph.financewallet.data.advanced

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
import ph.financewallet.domain.common.errorOrNull
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.id.UuidV7Generator
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence
import java.io.StringWriter
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Debts, assets, recurring rules (AC-ADV-1..3) and CSV export (AC-ADV-7) against Room. IC-1..8 after every test. */
@RunWith(RobolectricTestRunner::class)
class Phase5ServicesTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: LedgerService
    private lateinit var debts: DebtService
    private lateinit var assets: AssetService
    private lateinit var recurring: RecurringService
    private lateinit var reports: ReportService

    // 2026-11-06 11:00 Asia/Manila
    private val clock = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneOffset.UTC)
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "test-device")
    private val php = CurrencyCode.PHP
    private val today = LocalDate.of(2026, 11, 6)
    private val far = LocalDate.of(2100, 1, 1)
    private var keySeq = 0

    private lateinit var bpi: String
    private lateinit var gcash: String
    private lateinit var internet: String
    private lateinit var salary: String

    private fun p(minor: Long) = Money(minor, php)

    private fun key() = "k${++keySeq}"

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            ledger = LedgerService(db, ctx)
            debts = DebtService(db, ctx, ledger)
            assets = AssetService(db, ctx)
            recurring = RecurringService(db, ctx, ledger)
            reports = ReportService(db, ledger)
            ledger.ensureSystemAccounts()
            bpi = ledger.createAccount(NewAccount("BPI", FinancialAccountType.BANK, php, today.minusMonths(2), p(10_000_000))).getOrThrow()
            gcash =
                ledger.createAccount(NewAccount("GCash", FinancialAccountType.EWALLET, php, today.minusMonths(2), p(500_000))).getOrThrow()
            internet = ledger.createCategory("Internet", AccountClass.EXPENSE).getOrThrow()
            salary = ledger.createCategory("Salary", AccountClass.INCOME).getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity after test: ${report.failures}", report.isHealthy)
        }

    // ---- debts (AC-ADV-1/2) --------------------------------------------------------------------------------------

    @Test
    fun `a loan received into a bank is not income, payments split principal from interest`() =
        runBlocking<Unit> {
            val k = key()
            val req =
                NewDebt(
                    "Salary loan",
                    DebtKind.PERSONAL_LOAN,
                    php,
                    p(5_000_000),
                    today,
                    receivedIntoAccountId = bpi,
                    plan = InstallmentPlan(Recurrence.Monthly(LocalDate.of(2026, 12, 15), 15), 12),
                    idempotencyKey = k,
                )
            val loan = debts.create(req).getOrThrow()
            assertEquals(loan, debts.create(req).getOrThrow()) // idempotent
            assertEquals(p(15_000_000), ledger.balance(bpi, far))
            assertNull("no income, spending or equity entries at all", ledger.summary(today, today)[php])
            assertEquals(12, debts.schedule(loan).size)
            assertEquals(5_000_000L, debts.schedule(loan).sumOf { it.amountDueMinor })

            val pay =
                debts
                    .recordPayment(
                        loan,
                        TxnHeader(LocalDate.of(2026, 11, 6), key()),
                        bpi,
                        p(400_000),
                        p(50_000),
                        p(1_000),
                        scheduleSeq = 1,
                    ).getOrThrow()
            val s = debts.summary(loan)
            assertEquals(p(4_600_000), s.outstanding)
            assertEquals(p(51_000), s.interestAndFeesPaid)
            assertEquals(11, s.remainingInstallments)
            assertEquals(LocalDate.of(2027, 1, 15), s.nextDue)
            assertEquals(p(51_000), ledger.summary(today, today).getValue(php).spending)
            assertEquals(
                "INSTALLMENT_ALREADY_PAID",
                debts.recordPayment(loan, TxnHeader(today, key()), bpi, p(1), p(0), p(0), scheduleSeq = 1).errorOrNull()?.code,
            )
            ledger.delete(pay).getOrThrow()
            assertEquals(12, debts.summary(loan).remainingInstallments) // a deleted payment no longer counts
            assertEquals(p(5_000_000), debts.summary(loan).outstanding)
        }

    @Test
    fun `an existing card balance becomes PAID_OFF when fully repaid`() =
        runBlocking<Unit> {
            val card =
                debts
                    .create(
                        NewDebt(
                            "Card",
                            DebtKind.CREDIT_CARD,
                            php,
                            p(1_200_000),
                            today,
                            currentlyOwed = p(1_200_000),
                            idempotencyKey = key(),
                        ),
                    ).getOrThrow()
            assertEquals(p(1_200_000), debts.summary(card).outstanding)
            assertNull(debts.summary(card).remainingInstallments)
            assertEquals(
                "DEBT_SOURCE_REQUIRED",
                debts.create(NewDebt("Bad", DebtKind.OTHER, php, p(1), today, idempotencyKey = key())).errorOrNull()?.code,
            )
            debts.recordPayment(card, TxnHeader(today, key()), bpi, p(1_200_000), p(0), p(0)).getOrThrow()
            assertEquals(DebtStatus.PAID_OFF, debts.summary(card).status)
            assertEquals(p(0), debts.summary(card).outstanding)
        }

    // ---- assets and net worth (AC-ADV-5) -------------------------------------------------------------------------

    @Test
    fun `net worth adds latest valuations, excludes liabilities' sign confusion, history per month end`() =
        runBlocking<Unit> {
            val car = assets.create("Car", AssetKind.VEHICLE, php).getOrThrow()
            assets.addValuation(car, LocalDate.of(2026, 9, 30), p(60_000_000)).getOrThrow()
            val oct = assets.addValuation(car, LocalDate.of(2026, 10, 31), p(58_000_000)).getOrThrow()
            assertEquals("DATE_IN_FUTURE", assets.addValuation(car, today.plusDays(1), p(1)).errorOrNull()?.code)
            debts
                .create(
                    NewDebt("Card", DebtKind.CREDIT_CARD, php, p(300_000), today, currentlyOwed = p(300_000), idempotencyKey = key()),
                ).getOrThrow()

            val nw = reports.netWorth(today).single()
            assertEquals(p(10_500_000), nw.accounts)
            assertEquals(p(-300_000), nw.liabilities)
            assertEquals(p(58_000_000), nw.otherAssets)
            assertEquals(p(68_200_000), nw.total)

            // transfers never change net worth
            ledger.record(TxnHeader(today, key())) { t, _ -> t.transfer(bpi, gcash, p(1_000_000)) }.getOrThrow()
            assertEquals(nw.total, reports.netWorth(today).single().total)

            val history = reports.netWorthHistory(LocalDate.of(2026, 9, 1), today, php)
            assertEquals(listOf(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31), today), history.map { it.first })
            assertEquals(listOf(p(70_500_000), p(68_500_000), p(68_200_000)), history.map { it.second })

            // a second value on the same date replaces the first, in the list and in net worth alike
            assets.addValuation(car, LocalDate.of(2026, 10, 31), p(57_000_000)).getOrThrow()
            assertEquals(p(57_000_000), reports.netWorth(today).single().otherAssets)
            assertEquals(p(57_000_000), assets.assets().single().latestValue)
            assets.addValuation(car, LocalDate.of(2026, 10, 31), p(58_000_000)).getOrThrow().let { assets.deleteValuation(it).getOrThrow() }
            assertEquals("ALREADY_DELETED", assets.deleteValuation(oct).errorOrNull()?.code)
            assertEquals(p(60_000_000), reports.netWorth(today).single().otherAssets)
            assertEquals(p(60_000_000), assets.assets().single().latestValue)
        }

    // ---- recurring (AC-ADV-3) ------------------------------------------------------------------------------------

    @Test
    fun `bills materialize once, are paid only with a real transaction, and revert if it is deleted`() =
        runBlocking<Unit> {
            val bill =
                recurring
                    .createRule(
                        RuleSpec(
                            RuleKind.BILL,
                            "Internet",
                            p(169_900),
                            Recurrence.Monthly(LocalDate.of(2026, 10, 1), 15),
                            accountId = gcash,
                            categoryId = internet,
                            reminderDaysBefore = 3,
                        ),
                    ).getOrThrow()
            assertEquals(0, recurring.materialize().getOrThrow()) // created on rule creation; second run adds nothing
            val window = recurring.upcoming(today.minusDays(31), today.plusDays(62))
            assertEquals(listOf("2026-10-15", "2026-11-15", "2026-12-15"), window.map { it.dueDate.toString() })
            assertEquals(OccurrenceState.OVERDUE, window.first().state)
            assertEquals(p(500_000), ledger.balance(gcash, far)) // a scheduled bill is not a payment (BILL-02)

            val nov = window[1]
            val txn = recurring.markPaid(nov.occurrenceId, TxnHeader(today, key()), amount = p(175_000)).getOrThrow()
            assertEquals(p(325_000), ledger.balance(gcash, far))
            assertEquals(OccurrenceState.PAID, recurring.upcoming(today, today.plusDays(30)).first().state)
            assertEquals("ALREADY_PAID", recurring.skip(nov.occurrenceId).errorOrNull()?.code)
            // Reminders: only the overdue October bill (November's 3-day reminder starts on Nov 12).
            assertEquals(listOf(LocalDate.of(2026, 10, 15)), recurring.remindersDue().map { it.dueDate })
            recurring.skip(window[0].occurrenceId).getOrThrow()
            assertEquals(emptyList<UpcomingItem>(), recurring.remindersDue())

            ledger.delete(txn).getOrThrow()
            recurring.materialize().getOrThrow()
            assertEquals(OccurrenceState.UPCOMING, recurring.upcoming(today, today.plusDays(30)).first().state)
            assertEquals(p(169_900 * 12), recurring.costs().single { it.ruleId == bill }.nextTwelveMonths)
        }

    @Test
    fun `linking an existing transaction marks the occurrence paid once`() =
        runBlocking<Unit> {
            recurring
                .createRule(
                    RuleSpec(RuleKind.SUBSCRIPTION, "Streaming", p(54_900), Recurrence.Monthly(today, 20), categoryId = internet),
                ).getOrThrow()
            val occ = recurring.upcoming(today, today.plusDays(30)).single()
            val txn = ledger.record(TxnHeader(today, key())) { t, _ -> t.expense(gcash, listOf(Split(internet, p(54_900)))) }.getOrThrow()
            recurring.linkExisting(occ.occurrenceId, txn).getOrThrow()
            assertEquals(txn, recurring.upcoming(today, today.plusDays(30)).single().paidTxnId)
            assertEquals("ALREADY_PAID", recurring.linkExisting(occ.occurrenceId, txn).errorOrNull()?.code)
            assertEquals(
                "ACCOUNT_REQUIRED",
                recurring
                    .markPaid(
                        recurring.upcoming(today, today.plusDays(62)).last().occurrenceId,
                        TxnHeader(today, key()),
                    ).errorOrNull()
                    ?.code,
            )
        }

    @Test
    fun `auto-post is opt-in for templates only and idempotent per due date`() =
        runBlocking<Unit> {
            assertEquals(
                "AUTO_POST_ONLY_FOR_TEMPLATES",
                recurring
                    .createRule(
                        RuleSpec(RuleKind.BILL, "Rent", p(1_000_000), Recurrence.Monthly(today, 1), autoPost = true),
                    ).errorOrNull()
                    ?.code,
            )
            recurring
                .createRule(
                    RuleSpec(
                        RuleKind.RECURRING_TXN,
                        "Allowance",
                        p(200_000),
                        Recurrence.Weekly(LocalDate.of(2026, 10, 23)),
                        accountId = bpi,
                        categoryId = salary,
                        autoPost = true,
                    ),
                ).getOrThrow()
            assertEquals(3, recurring.autoPost().getOrThrow()) // Oct 23, Oct 30, Nov 6
            assertEquals(0, recurring.autoPost().getOrThrow())
            assertEquals(p(10_600_000), ledger.balance(bpi, far))
        }

    @Test
    fun `expected income never touches balances and rule edits change only the future`() =
        runBlocking<Unit> {
            val rule =
                recurring
                    .createRule(
                        RuleSpec(
                            RuleKind.EXPECTED_INCOME,
                            "Payday",
                            p(3_000_000),
                            Recurrence.SemiMonthly(LocalDate.of(2026, 10, 1), 15, 31),
                            categoryId = salary,
                        ),
                    ).getOrThrow()
            assertEquals(p(10_000_000), ledger.balance(bpi, far))
            val before = recurring.upcoming(today.minusDays(31), today.plusDays(62))
            recurring.markPaid(before.first().occurrenceId, TxnHeader(LocalDate.of(2026, 10, 15), key()), accountId = bpi).getOrThrow()
            recurring
                .editRule(
                    rule,
                    1,
                    RuleSpec(
                        RuleKind.EXPECTED_INCOME,
                        "Payday",
                        p(3_200_000),
                        Recurrence.SemiMonthly(LocalDate.of(2026, 10, 1), 15, 31),
                        categoryId = salary,
                    ),
                ).getOrThrow()
            val after = recurring.upcoming(today.minusDays(31), today.plusDays(62))
            assertEquals(before.map { it.dueDate }, after.map { it.dueDate })
            assertEquals(p(3_000_000), after.first().amount) // paid history kept
            assertTrue(after.filter { !it.dueDate.isBefore(today) }.all { it.amount == p(3_200_000) })
            assertEquals(emptyList<UpcomingItem>(), recurring.remindersDue())
        }

    // ---- CSV (AC-ADV-7) ------------------------------------------------------------------------------------------

    @Test
    fun `csv export is exact, escaped and parses back to the same rows`() =
        runBlocking<Unit> {
            ledger
                .record(TxnHeader(today, key(), description = "=HYPERLINK(\"x\")", merchant = "Jollibee, SM North")) { t, _ ->
                    t.expense(gcash, listOf(Split(internet, p(123_456))))
                }.getOrThrow()
            ledger.record(TxnHeader(today, key(), description = "line1\nline2")) { t, _ -> t.transfer(bpi, gcash, p(5)) }.getOrThrow()
            val out = StringWriter()
            val rows = CsvExporter(db, ctx).exportTransactions(today.minusMonths(3), today, out)
            assertEquals(4, rows) // 2 openings + 2 above
            val parsed = parseCsv(out.toString())
            assertEquals(CsvExporter.HEADER, parsed.first())
            val expense = parsed.single { it[1] == "EXPENSE" }
            assertEquals("'=HYPERLINK(\"x\")", expense[3])
            assertEquals("Jollibee, SM North", expense[4])
            assertEquals("-1234.56", expense[8])
            assertEquals("line1\nline2", parsed.single { it[1] == "TRANSFER" }[3])
            assertEquals("0.05", parsed.single { it[1] == "TRANSFER" }[8])
            val audits =
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM audit_event WHERE kind = 'EXPORT_CSV'").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            assertEquals(1, audits)
        }

    /** Minimal RFC 4180 reader for the round-trip check. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }

                quoted && c == '"' -> {
                    quoted = false
                }

                quoted -> {
                    cell.append(c)
                }

                c == '"' -> {
                    quoted = true
                }

                c == ',' -> {
                    row += cell.toString()
                    cell.clear()
                }

                c == '\r' && text.getOrNull(i + 1) == '\n' -> {
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = mutableListOf()
                    i++
                }

                else -> {
                    cell.append(c)
                }
            }
            i++
        }
        return rows
    }
}
