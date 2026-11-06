package ph.financewallet.app

import ph.financewallet.data.account.AccountService
import ph.financewallet.data.account.CategoryService
import ph.financewallet.data.advanced.AssetService
import ph.financewallet.data.advanced.CsvExporter
import ph.financewallet.data.advanced.DebtService
import ph.financewallet.data.advanced.RecurringService
import ph.financewallet.data.advanced.ReportService
import ph.financewallet.data.budget.AllocationService
import ph.financewallet.data.budget.BudgetService
import ph.financewallet.data.budget.GoalService
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.ledger.LedgerService
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
) {
    val ledger = LedgerService(db, ctx)
    val accounts = AccountService(db, ctx, ledger)
    val categories = CategoryService(db, ctx)
    val budgets = BudgetService(db, ctx)
    val allocations = AllocationService(db, ctx)
    val goals = GoalService(db, ctx, ledger)
    val transactions = TransactionQueries(db)
    val debts = DebtService(db, ctx, ledger)
    val assets = AssetService(db, ctx)
    val recurring = RecurringService(db, ctx, ledger)
    val reports = ReportService(db, ledger)
    val csv = CsvExporter(db, ctx)

    /** A fresh idempotency key for one user action (TXN-09: a double tap saves once). */
    fun newKey(): String = ctx.ids.newId()

    companion object {
        /** Prepares system accounts and the stable per-install device id (ADR-0007). */
        suspend fun create(
            db: FinanceDatabase,
            clock: Clock = Clock.systemUTC(),
        ): AppServices {
            val ids = UuidV7Generator(clock)
            val deviceId = db.deviceId(ids::newId)
            return AppServices(db, WriteContext(clock, ids, deviceId)).also { it.ledger.ensureSystemAccounts() }
        }
    }
}
