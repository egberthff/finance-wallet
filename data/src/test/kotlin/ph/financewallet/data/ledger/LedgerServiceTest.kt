package ph.financewallet.data.ledger

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
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.errorOrNull
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.id.UuidV7Generator
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.LedgerCalculator
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.ledger.TxnStatus
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class LedgerServiceTest {
    private lateinit var db: FinanceDatabase
    private lateinit var service: LedgerService
    private val clock = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneOffset.UTC)
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "test-device")
    private val php = CurrencyCode.PHP
    private val nov5 = LocalDate.of(2026, 11, 5)
    private val far = LocalDate.of(2100, 1, 1)
    private var keySeq = 0

    private fun p(minor: Long) = Money(minor, php)

    private fun header(date: LocalDate = nov5) = TxnHeader(date, "k${++keySeq}")

    @Before
    fun setUp() =
        runBlocking {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            service = LedgerService(db, ctx)
            service.ensureSystemAccounts()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity after test: ${report.failures}", report.isHealthy)
        }

    private suspend fun account(
        name: String,
        type: FinancialAccountType = FinancialAccountType.EWALLET,
        opening: Long = 0,
    ) = service.createAccount(NewAccount(name, type, php, nov5, p(opening))).getOrThrow()

    private suspend fun category(
        name: String,
        cls: AccountClass = AccountClass.EXPENSE,
    ) = service.createCategory(name, cls).getOrThrow()

    @Test
    fun `system accounts are created once`() =
        runBlocking<Unit> {
            service.ensureSystemAccounts()
            assertEquals(6, db.ledgerDao().allLedgerAccounts().count { it.systemKey != null })
        }

    @Test
    fun `account with opening balance, liability opening is owed`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 500_000)
            val card = account("Card", FinancialAccountType.CREDIT_CARD, opening = 250_000)
            assertEquals(p(500_000), service.balance(gcash, far))
            assertEquals(p(-250_000), service.balance(card, far))
            assertEquals(p(250_000), service.summary(nov5, nov5).getValue(php).openings)
            assertEquals(
                "NAME_LENGTH",
                service.createAccount(NewAccount(" ", FinancialAccountType.CASH, php, nov5, p(0))).errorOrNull()?.code,
            )
            assertEquals(
                "AMOUNT_NEGATIVE",
                service.createAccount(NewAccount("x", FinancialAccountType.CASH, php, nov5, p(-1))).errorOrNull()?.code,
            )
        }

    @Test
    fun `EX-5 transfer via use case leaves income and spending untouched (AC-CORE-1)`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 500_000)
            val bpi = account("BPI", FinancialAccountType.BANK)
            service.record(header()) { t, _ -> t.transfer(gcash, bpi, p(200_000)) }.getOrThrow()
            assertEquals(p(300_000), service.balance(gcash, far))
            assertEquals(p(200_000), service.balance(bpi, far))
            val s = service.summary(nov5, nov5).getValue(php)
            assertEquals(p(0), s.income)
            assertEquals(p(0), s.spending)
        }

    @Test
    fun `same idempotency key twice stores one transaction (TXN-09)`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 1_000)
            val food = category("Food")
            val h = header()
            val first = service.record(h) { t, _ -> t.expense(gcash, listOf(Split(food, p(100)))) }.getOrThrow()
            val second = service.record(h) { t, _ -> t.expense(gcash, listOf(Split(food, p(100)))) }.getOrThrow()
            assertEquals(first, second)
            assertEquals(p(900), service.balance(gcash, far))
        }

    @Test
    fun `EX-10 edit writes a revision and reports show the new amount (AC-CORE-8)`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 100_000)
            val food = category("Food")
            val h = header()
            val id = service.record(h) { t, _ -> t.expense(gcash, listOf(Split(food, p(50_000)))) }.getOrThrow()
            service
                .edit(id, expectedVersion = 1, header = h.copy(description = "Lunch")) { t, _ ->
                    t.expense(gcash, listOf(Split(food, p(45_000))))
                }.getOrThrow()
            assertEquals(p(45_000), service.summary(nov5, nov5).getValue(php).spending)
            val revisions = db.ledgerDao().revisions(id)
            assertEquals(listOf("CREATE", "UPDATE"), revisions.map { it.changeKind })
            assertTrue(checkNotNull(revisions[1].beforeJson).contains("\"amountMinor\":\"50000\""))
            assertTrue(checkNotNull(revisions[1].afterJson).contains("\"amountMinor\":\"45000\""))
            // Categories must exist before the write starts: templates see a snapshot taken inside the transaction.
            val pay = category("Pay", AccountClass.INCOME)
            assertEquals(
                "STALE_VERSION",
                service
                    .edit(
                        id,
                        expectedVersion = 1,
                        header = h,
                    ) { t, _ -> t.expense(gcash, listOf(Split(food, p(1)))) }
                    .errorOrNull()
                    ?.code,
            )
            assertEquals(
                "TYPE_CHANGE_NOT_ALLOWED",
                service
                    .edit(
                        id,
                        expectedVersion = 2,
                        header = h,
                    ) { t, _ -> t.income(gcash, listOf(Split(pay, p(1)))) }
                    .errorOrNull()
                    ?.code,
            )
        }

    @Test
    fun `delete is soft, audited, and excluded from balances (TXN-07)`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 1_000)
            val food = category("Food")
            val id = service.record(header()) { t, _ -> t.expense(gcash, listOf(Split(food, p(300)))) }.getOrThrow()
            service.delete(id, "duplicate").getOrThrow()
            assertEquals(p(1_000), service.balance(gcash, far))
            assertEquals(listOf("CREATE", "DELETE"), db.ledgerDao().revisions(id).map { it.changeKind })
            assertTrue(checkNotNull(db.ledgerDao().txn(id)).deletedAt != null)
            assertEquals("TXN_DELETED", service.delete(id).errorOrNull()?.code)
        }

    @Test
    fun `refunds use stored history and cannot exceed the original (TXN-05, AC-CORE-7)`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 100_000)
            val food = category("Food")
            val original = service.record(header()) { t, _ -> t.expense(gcash, listOf(Split(food, p(50_000)))) }.getOrThrow()
            service.record(header()) { t, r -> t.refund(gcash, p(20_000), original = r.refundOriginal(original)) }.getOrThrow()
            val over = service.record(header()) { t, r -> t.refund(gcash, p(30_001), original = r.refundOriginal(original)) }
            assertEquals("REFUND_EXCEEDS_ORIGINAL", over.errorOrNull()?.code)
            assertEquals(p(30_000), service.summary(nov5, nov5).getValue(php).spending)
            assertEquals(p(0), service.summary(nov5, nov5).getValue(php).income)
            assertEquals("TXN_HAS_REFUNDS", service.delete(original).errorOrNull()?.code)
        }

    @Test
    fun `an original can be reversed once and then not deleted`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 10_000)
            val food = category("Food")
            val original = service.record(header()) { t, _ -> t.expense(gcash, listOf(Split(food, p(2_500)))) }.getOrThrow()
            service.record(header()) { t, r -> t.reversal(checkNotNull(r.transaction(original)), r.isReversed(original)) }.getOrThrow()
            val again = service.record(header()) { t, r -> t.reversal(checkNotNull(r.transaction(original)), r.isReversed(original)) }
            assertEquals("ALREADY_REVERSED", again.errorOrNull()?.code)
            assertEquals(p(10_000), service.balance(gcash, far))
            assertEquals("TXN_REVERSED_DELETE_REVERSAL_FIRST", service.delete(original).errorOrNull()?.code)
        }

    @Test
    fun `pending transactions are excluded from posted balance`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 10_000)
            val food = category("Food")
            service.record(header().copy(status = TxnStatus.PENDING)) { t, _ -> t.expense(gcash, listOf(Split(food, p(700)))) }.getOrThrow()
            assertEquals(p(10_000), service.balance(gcash, far))
            assertEquals(p(-700), service.pendingBalance(gcash))
        }

    @Test
    fun `AC-CORE-9 a crash mid-write leaves nothing behind`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 10_000)
            val food = category("Food")
            for (checkpoint in listOf("after_txn_row", "after_entries")) {
                val crashing = LedgerService(db, ctx) { if (it == checkpoint) throw IllegalStateException("simulated crash at $it") }
                val h = header()
                val result = runCatching { crashing.record(h) { t, _ -> t.expense(gcash, listOf(Split(food, p(999)))) } }
                assertTrue("crash at $checkpoint must propagate", result.isFailure)
                assertNull("no txn row after crash at $checkpoint", db.ledgerDao().txnByIdempotencyKey(h.idempotencyKey))
            }
            assertEquals(p(10_000), service.balance(gcash, far))
            assertEquals(
                0,
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM txn WHERE type = 'EXPENSE'").use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
        }

    @Test
    fun `header validation`() =
        runBlocking<Unit> {
            val gcash = account("GCash", opening = 10_000)
            val food = category("Food")
            val long = header().copy(description = "x".repeat(201))
            assertEquals("TOO_LONG", service.record(long) { t, _ -> t.expense(gcash, listOf(Split(food, p(1)))) }.errorOrNull()?.code)
            val blank = header().copy(idempotencyKey = " ")
            assertEquals(
                "IDEMPOTENCY_KEY_REQUIRED",
                service.record(blank) { t, _ -> t.expense(gcash, listOf(Split(food, p(1)))) }.errorOrNull()?.code,
            )
            assertEquals(p(10_000), service.balance(gcash, far))
        }

    @Suppress("LongParameterList") // test helper: every value is an independent random input
    private suspend fun randomOperation(
        kind: Int,
        a: String,
        b: String,
        card: String,
        loan: String,
        food: String,
        entertainment: String,
        salary: String,
        amount: Money,
        date: LocalDate,
        ids: List<String>,
        rnd: Random,
    ): DomainResult<String> =
        when (kind) {
            0 -> {
                service.record(header(date)) { t, _ -> t.income(a, listOf(Split(salary, amount))) }
            }

            1 -> {
                service.record(header(date)) { t, _ -> t.expense(a, listOf(Split(food, amount))) }
            }

            2 -> {
                service.record(header(date)) { t, _ -> t.expense(card, listOf(Split(entertainment, amount))) }
            }

            3 -> {
                service.record(header(date)) { t, _ -> t.transfer(a, b, amount) }
            }

            4 -> {
                service.record(header(date)) { t, _ -> t.debtPayment(a, loan, amount, p(amount.minor / 20), p(0)) }
            }

            5 -> {
                val victim = ids.randomOrNull(rnd)
                if (victim != null) service.delete(victim)
                DomainResult.Ok("")
            }

            6 -> {
                service.record(header(date)) { t, _ -> t.balanceAdjustment(a, if (rnd.nextBoolean()) amount else -amount) }
            }

            else -> {
                service.record(header(date)) { t, _ -> t.debtDisbursement(a, loan, amount) }
            }
        }

    @Test
    fun `SQL balances and report totals equal the domain calculator over random operations`() =
        runBlocking<Unit> {
            val rnd = Random(20261105)
            val accounts =
                listOf(
                    account("GCash", opening = 5_000_000),
                    account("BPI", FinancialAccountType.BANK, 2_000_000),
                    account("Cash", FinancialAccountType.CASH),
                )
            val card = account("Card", FinancialAccountType.CREDIT_CARD)
            val loan = account("Loan", FinancialAccountType.LOAN)
            val food = category("Food")
            val entertainment = category("Fun")
            val salary = category("Salary", AccountClass.INCOME)
            val ids = mutableListOf<String>()
            repeat(300) {
                val a = accounts[rnd.nextInt(3)]
                val b = accounts[(accounts.indexOf(a) + 1 + rnd.nextInt(2)) % 3]
                val amount = p(rnd.nextLong(1, 200_000))
                val date = nov5.plusDays(rnd.nextLong(-40, 40))
                val r = randomOperation(rnd.nextInt(8), a, b, card, loan, food, entertainment, salary, amount, date, ids, rnd)
                (r as? DomainResult.Ok)?.value?.takeIf { it.isNotEmpty() }?.let { ids += it }
            }
            val all = db.ledgerDao().let { dao -> (dao.allLedgerAccounts().map { it.id }) }
            val txns =
                db.openHelper.readableDatabase
                    .query("SELECT id FROM txn")
                    .use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0)) }
                    }.mapNotNull { service.loadTransaction(it) }
            val calc = LedgerCalculator(service.directory())
            for (acct in accounts + card + loan) {
                for (asOf in listOf(nov5.minusDays(20), nov5, far)) {
                    assertEquals("balance $acct @ $asOf", calc.balance(acct, asOf, txns), service.balance(acct, asOf))
                }
            }
            for ((from, to) in listOf(nov5.minusDays(40) to nov5.plusDays(40), nov5.minusDays(7) to nov5, nov5 to nov5)) {
                assertEquals("summary $from..$to", calc.summary(from, to, txns), service.summary(from, to))
            }
            assertTrue(all.size > 6)
        }
}
