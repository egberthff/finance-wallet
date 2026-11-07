package ph.financewallet.app

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import ph.financewallet.data.account.AccountService
import ph.financewallet.data.account.CategoryService
import ph.financewallet.data.advanced.AssetService
import ph.financewallet.data.advanced.CsvExporter
import ph.financewallet.data.advanced.DebtService
import ph.financewallet.data.advanced.RecurringService
import ph.financewallet.data.advanced.ReportService
import ph.financewallet.data.backup.BackupService
import ph.financewallet.data.budget.AllocationService
import ph.financewallet.data.budget.BudgetService
import ph.financewallet.data.budget.GoalService
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.imports.CategoryRuleService
import ph.financewallet.data.imports.ImportService
import ph.financewallet.data.insight.InsightsService
import ph.financewallet.data.ledger.EntryPreferences
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.TransactionDetailQueries
import ph.financewallet.data.ledger.TransactionQueries
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.data.ledger.deviceId
import ph.financewallet.domain.id.UuidV7Generator
import java.time.Clock

/**
 * All use-case and query services over one open database. Built once after unlock ([create]); screens get it from
 * their ViewModel factory. Nothing here holds UI state.
 */
class AppServices(
    val db: FinanceDatabase,
    val ctx: WriteContext,
    appVersion: Int = 1,
    safetyCopy: (suspend () -> Unit)? = null,
) {
    val ledger = LedgerService(db, ctx)
    val accounts = AccountService(db, ctx, ledger)
    val categories = CategoryService(db, ctx)
    val budgets = BudgetService(db, ctx)
    val allocations = AllocationService(db, ctx)
    val goals = GoalService(db, ctx, ledger)
    val transactions = TransactionQueries(db)
    val transactionDetails = TransactionDetailQueries(db)
    val entryPreferences = EntryPreferences(db)
    val debts = DebtService(db, ctx, ledger)
    val assets = AssetService(db, ctx)
    val recurring = RecurringService(db, ctx, ledger)
    val reports = ReportService(db, ledger)
    val csv = CsvExporter(db, ctx)

    /** Statement import (UC-26..28); XLSX is parsed with the platform XML pull parser (DTDs refused, IMP-07). */
    val imports = ImportService(db, ctx, ledger, { android.util.Xml.newPullParser() })
    val rules = CategoryRuleService(db, ctx)

    /** Encrypted backup and restore, plaintext export (UC-29/30). */
    val backup = BackupService(db, ctx, appVersion, safetyCopy)

    /** On-device insights (ADR-0012): read-only, nothing leaves the phone. */
    val insights = InsightsService(db, ctx, ledger, reports, recurring, goals)

    /** A fresh idempotency key for one user action (TXN-09: a double tap saves once). */
    fun newKey(): String = ctx.ids.newId()

    /**
     * Emits after any write to [tables] (Room `InvalidationTracker.createFlow`, verified in room-runtime 2.8.5:
     * `createFlow(vararg tables, emitInitialState = true): Flow<Set<String>>`). No initial emission; bursts of writes
     * (an import, a multi-table use case) are merged by a short debounce, so a screen reloads once (blueprint F, R-1/R-2).
     */
    @OptIn(FlowPreview::class)
    fun changes(vararg tables: String): Flow<Unit> =
        db.invalidationTracker
            .createFlow(*tables, emitInitialState = false)
            .debounce(CHANGE_DEBOUNCE_MS)
            .map { }

    companion object {
        const val CHANGE_DEBOUNCE_MS = 100L

        /** Prepares system accounts and the stable per-install device id (ADR-0007). */
        suspend fun create(
            db: FinanceDatabase,
            clock: Clock = Clock.systemUTC(),
            appVersion: Int = 1,
            safetyCopy: (suspend () -> Unit)? = null,
        ): AppServices {
            val ids = UuidV7Generator(clock)
            val deviceId = db.deviceId(ids::newId)
            return AppServices(db, WriteContext(clock, ids, deviceId), appVersion, safetyCopy).also { it.ledger.ensureSystemAccounts() }
        }
    }
}
