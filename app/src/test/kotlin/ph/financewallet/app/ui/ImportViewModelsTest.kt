package ph.financewallet.app.ui

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
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.imports.ImportHistoryViewModel
import ph.financewallet.app.ui.imports.ImportStep
import ph.financewallet.app.ui.imports.ImportViewModel
import ph.financewallet.app.ui.imports.RulesViewModel
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.IntegrityChecker
import ph.financewallet.data.imports.RowDecision
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.domain.common.getOrThrow
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The import flow as the screens drive it: choose → map → preview → import, history rollback, rules. */
@RunWith(RobolectricTestRunner::class)
class ImportViewModelsTest {
    private lateinit var db: FinanceDatabase
    private lateinit var s: AppServices
    private val clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC)
    private lateinit var gcash: String
    private val far = LocalDate.of(2100, 1, 1)

    private fun p(minor: Long) = Money(minor, CurrencyCode.PHP)

    // 11/05 and 11/06 fit both month-first and day-first, so the user must choose.
    private val ambiguous =
        "Date,Description,Debit,Credit,Balance\n" +
            "11/05/2026,Salary,,\"30,000.00\",\"31,000.00\"\n" +
            "11/06/2026,Jollibee,450.50,,\"30,549.50\"\n" +
            "11/06/2026,Grab,180.00,,\"30,369.50\"\n"

    @Before
    fun setUp() =
        runBlocking<Unit> {
            db = FinanceDatabase.build(ApplicationProvider.getApplicationContext(), openHelperFactory = null, inMemory = true)
            s = AppServices.create(db, clock)
            gcash =
                s.ledger
                    .createAccount(
                        NewAccount("GCash", FinancialAccountType.EWALLET, CurrencyCode.PHP, LocalDate.of(2026, 10, 1), p(100_000)),
                    ).getOrThrow()
            s.categories.create("Food", AccountClass.EXPENSE).getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            val report = IntegrityChecker(db).run()
            db.close()
            assertTrue("integrity: ${report.failures}", report.isHealthy)
        }

    @Test
    fun `ambiguous dates must be chosen, then the preview imports and reconciles`() =
        runBlocking<Unit> {
            val vm = ImportViewModel(s)
            vm.loadOptions()
            vm.load("history.csv", ambiguous.toByteArray())
            val mapped = vm.state.value
            assertEquals(ImportStep.MAP, mapped.step)
            assertEquals(
                setOf("MM/dd/yyyy", "dd/MM/yyyy"),
                mapped.dateCandidates.filter { it.contains("dd/") || it.contains("/dd") }.toSet(),
            )
            assertNull(mapped.draft.datePattern) // never guessed
            assertEquals(R.string.import_err_mapping_incomplete, vm.preview())

            vm.editDraft { it.copy(datePattern = "MM/dd/yyyy") }
            assertNull(vm.preview(saveAsProfile = "GCash export"))
            val preview = checkNotNull(vm.state.value.preview)
            assertEquals(3, preview.rows.size)
            assertEquals(LocalDate.of(2026, 11, 5), preview.from)
            vm.decide(4, RowDecision.Skip) // the Grab row
            assertNull(vm.commit())
            val result = checkNotNull(vm.state.value.result)
            assertEquals(2 to 1, result.imported to result.skipped)
            assertEquals(ImportStep.DONE, vm.state.value.step)
            assertEquals(p(100_000 + 3_000_000 - 45_050), s.ledger.balance(gcash, far))

            // statement says 30,369.50 but the skipped Grab row is not recorded: reconciliation shows the difference
            assertNull(vm.reconcile(createAdjustment = false))
            assertEquals(p(3_036_950), s.accounts.reconciledBalance(gcash)?.statementBalance)

            // the saved profile recognizes the next file of the same layout, including the chosen date format
            vm.restart()
            vm.load("next.csv", ambiguous.toByteArray())
            assertEquals("MM/dd/yyyy", vm.state.value.draft.datePattern)
            assertTrue(vm.state.value.profileId != null)
        }

    @Test
    fun `refused files explain why`() =
        runBlocking<Unit> {
            val vm = ImportViewModel(s)
            vm.loadOptions()
            vm.load("statement.pdf", "%PDF-1.7".toByteArray())
            assertEquals(R.string.import_file_pdf, vm.state.value.error)
            assertEquals(ImportStep.CHOOSE, vm.state.value.step)
            vm.load("empty.csv", ByteArray(0))
            assertEquals(R.string.import_file_empty, vm.state.value.error)
        }

    @Test
    fun `history rollback and rules`() =
        runBlocking<Unit> {
            val vm = ImportViewModel(s)
            vm.loadOptions()
            vm.load("history.csv", ambiguous.toByteArray())
            vm.editDraft { it.copy(datePattern = "dd/MM/yyyy") }
            vm.preview()
            vm.commit()
            val history = ImportHistoryViewModel(s)
            val batch = history.load().batches.single()
            assertEquals(0, history.editedCount(batch.id))
            assertNull(history.rollback(batch.id, acknowledgeEdited = false))
            assertTrue(
                history
                    .load()
                    .batches
                    .single()
                    .rolledBack,
            )
            assertEquals(p(100_000), s.ledger.balance(gcash, far))

            val rules = RulesViewModel(s)
            val food =
                rules
                    .load()
                    .categories
                    .single()
                    .id
            assertEquals(R.string.err_category_required, rules.create("jollibee", null, auto = true))
            assertNull(rules.create("jollibee", food, auto = true))
            val rule = rules.load().rules.single()
            assertNull(rules.setEnabled(rule.id, false))
            assertNull(rules.delete(rule.id))
            assertTrue(rules.load().rules.isEmpty())
        }
}
