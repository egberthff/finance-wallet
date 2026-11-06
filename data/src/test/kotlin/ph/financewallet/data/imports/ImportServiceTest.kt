package ph.financewallet.data.imports

import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import ph.financewallet.importers.FileError
import ph.financewallet.importers.ImportFileException
import ph.financewallet.importers.RowError
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** UC-26..28 against Room: AC-IMP-1..4, profiles, rules, linking and rollback. IC-1..8 after every test. */
@RunWith(RobolectricTestRunner::class)
class ImportServiceTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: LedgerService
    private lateinit var imports: ImportService
    private lateinit var rules: CategoryRuleService
    private val clock = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneOffset.UTC)
    private val ctx = WriteContext(clock, UuidV7Generator(clock), deviceId = "test-device")
    private val php = CurrencyCode.PHP
    private val far = LocalDate.of(2100, 1, 1)
    private lateinit var gcash: String
    private lateinit var bpi: String
    private lateinit var food: String
    private lateinit var salary: String

    private fun p(minor: Long) = Money(minor, php)

    private val statement =
        "Date,Description,Debit,Credit,Balance\n" +
            "2026-11-01,Salary,,30000.00,31000.00\n" +
            "2026-11-02,Jollibee SM North,450.50,,30549.50\n" +
            "2026-11-03,Grab ride,180.00,,30369.50\n" +
            "2026-11-03,Grab ride,180.00,,30189.50\n" +
            "2026-11-04,Broken row,12..5,,\n"

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            ledger = LedgerService(db, ctx)
            imports = ImportService(db, ctx, ledger, { Xml.newPullParser() })
            rules = CategoryRuleService(db, ctx)
            ledger.ensureSystemAccounts()
            gcash =
                ledger
                    .createAccount(
                        NewAccount("GCash", FinancialAccountType.EWALLET, php, LocalDate.of(2026, 10, 1), p(100_000)),
                    ).getOrThrow()
            bpi = ledger.createAccount(NewAccount("BPI", FinancialAccountType.BANK, php, LocalDate.of(2026, 10, 1), p(0))).getOrThrow()
            food = ledger.createCategory("Food", AccountClass.EXPENSE).getOrThrow()
            salary = ledger.createCategory("Salary", AccountClass.INCOME).getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity after test: ${report.failures}", report.isHealthy)
        }

    private suspend fun previewOf(
        text: String,
        account: String = gcash,
    ): ImportPreview {
        val file = imports.read("statement.csv", text.toByteArray())
        val mapping = checkNotNull(imports.suggest(file).second.mapping)
        return imports.preview(account, file, mapping)
    }

    @Test
    fun `importing the same file twice creates nothing the second time (AC-IMP-1)`() =
        runBlocking<Unit> {
            val first = previewOf(statement)
            assertEquals(listOf(RowClass.NEW, RowClass.NEW, RowClass.NEW, RowClass.NEW, RowClass.REJECTED), first.rows.map { it.cls })
            val result = imports.commit(first).getOrThrow()
            assertEquals(4, result.imported)
            assertEquals(1, result.rejected)
            assertEquals(p(100_000 + 3_000_000 - 45_050 - 18_000 - 18_000), ledger.balance(gcash, far))

            val second = previewOf(statement)
            assertNotNull(second.alreadyImportedAt)
            assertEquals(4, second.count(RowClass.EXACT_DUPLICATE))
            val again = imports.commit(second).getOrThrow()
            assertEquals(0, again.imported)
            assertEquals(4, again.duplicates)
            assertEquals(p(3_018_950), ledger.balance(gcash, far))
        }

    @Test
    fun `a malformed row is reported with its number and never partly written (AC-IMP-2)`() =
        runBlocking<Unit> {
            val preview = previewOf(statement)
            val rejected = preview.rows.single { it.cls == RowClass.REJECTED }
            assertEquals(6, rejected.rowNo)
            assertEquals(RowError.AMOUNT_UNPARSEABLE, rejected.error)
            assertEquals("12..5", rejected.badCell)
            val batch = imports.commit(preview).getOrThrow().batchId
            val row = imports.rows(batch).single { it.rowNo == 6 }
            assertEquals("REJECTED" to null, row.outcome to row.txnId)
            assertEquals("AMOUNT_UNPARSEABLE", row.errorCode)
        }

    @Test
    fun `rollback restores every balance and allows importing again (AC-IMP-3)`() =
        runBlocking<Unit> {
            val before = ledger.balance(gcash, far)
            val summaryBefore = ledger.summary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
            val batch = imports.commit(previewOf(statement)).getOrThrow().batchId
            assertTrue(ledger.balance(gcash, far) != before)
            imports.rollback(batch).getOrThrow()
            assertEquals(before, ledger.balance(gcash, far))
            assertEquals(summaryBefore, ledger.summary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
            assertTrue(imports.history().single().rolledBack)
            assertEquals("ALREADY_ROLLED_BACK", imports.rollback(batch).errorOrNull()?.code)
            assertEquals(4, previewOf(statement).count(RowClass.NEW)) // fingerprints were removed
        }

    @Test
    fun `rows edited after import need confirmation before rollback`() =
        runBlocking<Unit> {
            val batch = imports.commit(previewOf(statement)).getOrThrow().batchId
            val txn = checkNotNull(db.ledgerDao().txnByIdempotencyKey("import:$batch:3"))
            ledger
                .edit(txn.id, txn.version, TxnHeader(LocalDate.of(2026, 11, 2), txn.idempotencyKey, description = "Lunch")) { t, _ ->
                    t.expense(gcash, listOf(Split(food, p(45_050))))
                }.getOrThrow()
            assertEquals(listOf(txn.id), imports.editedAfterImport(batch))
            assertEquals("EDITED_AFTER_IMPORT", imports.rollback(batch).errorOrNull()?.code)
            imports.rollback(batch, acknowledgeEdited = true).getOrThrow()
            assertEquals(p(100_000), ledger.balance(gcash, far))
        }

    @Test
    fun `overlaps with manual entries are flagged and only linked on request (AC-IMP-4)`() =
        runBlocking<Unit> {
            val manual =
                ledger
                    .record(TxnHeader(LocalDate.of(2026, 11, 3), "m1", description = "Jollibee - SM North")) { t, _ ->
                        t.expense(gcash, listOf(Split(food, p(45_050))))
                    }.getOrThrow()
            val preview = previewOf(statement)
            val probable = preview.rows.single { it.cls == RowClass.PROBABLE_DUPLICATE }
            assertEquals(3 to manual, probable.rowNo to probable.matchTxnId)

            // default: skipped, nothing merged
            val skipped = imports.commit(preview).getOrThrow()
            assertEquals(3, skipped.imported)
            assertEquals(1 + 1, skipped.skipped + skipped.rejected)
            assertEquals(p(100_000 + 3_000_000 - 45_050 - 18_000 - 18_000), ledger.balance(gcash, far))

            // linking on a fresh preview attaches the row to the manual transaction
            val linkPreview = previewOf(statement)
            val row = linkPreview.rows.single { it.rowNo == 3 }
            assertEquals(RowClass.PROBABLE_DUPLICATE, row.cls)
            val linked = imports.commit(linkPreview, mapOf(3 to RowDecision.Link(manual))).getOrThrow()
            assertEquals(1, linked.duplicates - 3) // 3 exact duplicates + 1 link
            assertEquals(RowClass.EXACT_DUPLICATE, previewOf(statement).rows.single { it.rowNo == 3 }.cls)
            assertEquals(p(100_000 + 3_000_000 - 45_050 - 18_000 - 18_000), ledger.balance(gcash, far))
        }

    @Test
    fun `a preview is stale once the account changed, and committing twice imports once`() =
        runBlocking<Unit> {
            val preview = previewOf(statement)
            imports.commit(preview).getOrThrow()
            assertEquals("PREVIEW_STALE", imports.commit(preview).errorOrNull()?.code)
            assertEquals(1, imports.history().size)
        }

    @Test
    fun `decisions - transfer, import anyway, uncategorized and pending rows`() =
        runBlocking<Unit> {
            val text = "Date,Description,Amount,Status\n2026-11-01,Cash in from BPI,500.00,Posted\n2026-11-02,Load,-99.00,Pending\n"
            val file = imports.read("gcash.csv", text.toByteArray())
            val mapping = checkNotNull(imports.suggest(file).second.mapping).copy(pendingValues = setOf("pending"))
            val preview = imports.preview(gcash, file, mapping)
            imports.commit(preview, mapOf(2 to RowDecision.Transfer(bpi))).getOrThrow()
            assertEquals(p(-50_000), ledger.balance(bpi, far)) // money came from BPI: not income
            assertEquals(p(0), ledger.summary(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1))[php]?.income ?: p(0))
            assertEquals(p(-9_900), ledger.pendingBalance(gcash))

            val again = imports.preview(gcash, file, mapping)
            assertEquals(2, again.count(RowClass.EXACT_DUPLICATE))
            val forced = imports.commit(again, mapOf(3 to RowDecision.Import(null))).getOrThrow()
            assertEquals(1, forced.imported) // "import anyway" needs an explicit decision and gets a new fingerprint
            assertEquals(p(-19_800), ledger.pendingBalance(gcash))
        }

    @Test
    fun `rules and merchant history suggest categories`() =
        runBlocking<Unit> {
            rules.create(NewRule("grab", food, mode = RuleMode.AUTO)).getOrThrow()
            rules.create(NewRule("salary", salary)).getOrThrow()
            assertEquals("PATTERN_LENGTH", rules.create(NewRule("  ", food)).errorOrNull()?.code)
            ledger
                .record(TxnHeader(LocalDate.of(2026, 10, 15), "h1", description = "Jollibee SM North")) { t, _ ->
                    t.expense(gcash, listOf(Split(food, p(30_000))))
                }.getOrThrow()
            val rows = previewOf(statement).rows.associateBy { it.rowNo }
            assertEquals(salary to SuggestionSource.RULE_SUGGEST, rows.getValue(2).let { it.suggestedCategoryId to it.suggestion })
            assertEquals(food to SuggestionSource.HISTORY, rows.getValue(3).let { it.suggestedCategoryId to it.suggestion })
            assertEquals(food to SuggestionSource.RULE_AUTO, rows.getValue(4).let { it.suggestedCategoryId to it.suggestion })
            val batch = imports.commit(previewOf(statement)).getOrThrow().batchId
            val grab = checkNotNull(ledger.loadTransaction(checkNotNull(db.ledgerDao().txnByIdempotencyKey("import:$batch:4")).id))
            assertTrue(grab.entries.any { it.accountId == food })
            rules.setEnabled(rules.rules().first().id, false).getOrThrow()
            rules.delete(rules.rules().last().id).getOrThrow()
            assertEquals(1, rules.rules().size)
        }

    @Test
    fun `saved profiles map the same layout again, files are guarded`() =
        runBlocking<Unit> {
            val text = "Posted,Narrative,Out,In\n05/11/2026,Coffee,150.00,\n"
            val file = imports.read("odd.csv", text.toByteArray())
            assertNull(imports.suggest(file).second.mapping) // unknown headers: the user maps columns
            val mapping =
                ph.financewallet.importers.ColumnMapping(
                    headerRow = 0,
                    date = 0,
                    description = 1,
                    amount =
                        ph.financewallet.importers.AmountColumns
                            .DebitCredit(2, 3),
                    datePattern = "dd/MM/yyyy",
                )
            val profile = imports.saveProfile("My bank", file, mapping).getOrThrow()
            val (matched, suggestion) =
                imports.suggest(
                    imports.read("next-month.csv", "Posted,Narrative,Out,In\n06/12/2026,Tea,90.00,\n".toByteArray()),
                )
            assertEquals(profile, matched)
            assertEquals(mapping, suggestion.mapping)
            assertEquals(listOf("My bank"), imports.profiles().map { it.name })
            assertEquals(
                FileError.PDF_LAYOUT_UNSUPPORTED,
                runCatching { imports.read("s.pdf", "%PDF-1.7".toByteArray()) }.exceptionOrNull().let { (it as ImportFileException).error },
            )
        }

    @Test
    fun `xlsx files go through the platform XML parser`() =
        runBlocking<Unit> {
            val file = imports.read("statement.xlsx", xlsx())
            val mapping = checkNotNull(imports.suggest(file).second.mapping)
            val preview = imports.preview(gcash, file, mapping)
            assertEquals(listOf(p(-45_050)), preview.rows.mapNotNull { it.row?.amount })
            assertEquals(
                LocalDate.of(2026, 11, 2),
                preview.rows
                    .single()
                    .row
                    ?.txnDate,
            )
        }

    private fun xlsx(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(
                n: String,
                t: String,
            ) {
                z.putNextEntry(ZipEntry(n))
                z.write(t.toByteArray())
                z.closeEntry()
            }
            put("xl/workbook.xml", "<workbook><sheets><sheet name=\"S\" sheetId=\"1\"/></sheets></workbook>")
            put("xl/styles.xml", "<styleSheet><cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>")
            put(
                "xl/worksheets/sheet1.xml",
                "<worksheet><sheetData>" +
                    "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Date</t></is></c>" +
                    "<c r=\"B1\" t=\"inlineStr\"><is><t>Description</t></is></c>" +
                    "<c r=\"C1\" t=\"inlineStr\"><is><t>Amount</t></is></c></row>" +
                    "<row r=\"2\"><c r=\"A2\" s=\"1\"><v>46328</v></c>" +
                    "<c r=\"B2\" t=\"inlineStr\"><is><t>Jollibee</t></is></c><c r=\"C2\"><v>-450.5</v></c></row>" +
                    "</sheetData></worksheet>",
            )
        }
        return out.toByteArray()
    }
}
