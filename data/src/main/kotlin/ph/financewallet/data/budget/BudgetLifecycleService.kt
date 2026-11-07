package ph.financewallet.data.budget

import ph.financewallet.data.account.audit
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.BudgetEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.money.CurrencyCode
import java.time.LocalDate

/**
 * Undo for budgets (UC-16/19): list and restore archived budgets, and reopen a period that was closed by mistake.
 * Separate from [BudgetService] so each stays small; both write through the same DAO inside one transaction.
 */
class BudgetLifecycleService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val dao get() = db.budgetDao()

    /** Archived budgets, most recently archived first (shown under "Closed" so they can be restored). */
    suspend fun archivedBudgets(): List<BudgetHeader> =
        dao.archivedBudgets().map {
            BudgetHeader(
                it.id,
                it.name,
                CurrencyCode.of(it.currency),
                IncomeMode.valueOf(it.incomeMode),
                it.version,
            )
        }

    /**
     * Restores an archived budget (undo of [archiveBudget], e.g. after a mis-tap). Its settings, periods and history are
     * unchanged; periods missed while it was archived are created when it is next used.
     */
    suspend fun unarchiveBudget(
        budgetId: String,
        expectedVersion: Long,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val b = dao.budget(budgetId) ?: notFound("budget", budgetId)
            ensure(b.archivedAt != null, "budgetId", "NOT_ARCHIVED")
            ensure(b.version == expectedVersion, "budgetId", "STALE_VERSION")
            dao.updateBudget(touch(b).copy(archivedAt = null))
            db.accountDao().audit(ctx, "budget.unarchive", "budget", budgetId, mapOf("name" to b.name))
        }

    /**
     * Reopens a period that was closed by mistake (UC-19 undo). The rollover, sweep and leftover moves written for it
     * are reversed: their allocation groups are soft-deleted (kept for the record, key freed so a later close works
     * again). Refused when it would no longer be a clean undo:
     * - the period has already ended (it would be closed again automatically);
     * - a later period of the budget is closed;
     * - money a move put into a category or goal has since been moved on or spent from the allocation
     *   (`MOVED_MONEY_USED`), because reversing would leave that bucket negative.
     * Returns the number of moves reversed.
     */
    suspend fun reopenPeriod(periodId: String): DomainResult<Int> =
        db.validatedWrite {
            val p = dao.period(periodId) ?: notFound("period", periodId)
            ensure(p.closedAt != null, "periodId", "PERIOD_NOT_CLOSED")
            val b = activeBudget(p.budgetId)
            ensure(!LocalDate.parse(p.endDate).isBefore(ctx.today()), "periodId", "PERIOD_ALREADY_ENDED")
            ensure(dao.periods(b.id).none { it.startDate > p.endDate && it.closedAt != null }, "periodId", "LATER_PERIOD_CLOSED")
            val groups = dao.liveGroupsWithKeyPrefix(BudgetService.rolloverKey(p.id, ""))
            ensureMovesUnused(groups.flatMap { dao.allocationEntries(it.id) })
            val now = ctx.nowMillis()
            groups.forEach { g ->
                dao.updateGroup(
                    g.copy(
                        deletedAt = now,
                        idempotencyKey = "${g.idempotencyKey}:reopened:${ctx.ids.newId()}",
                        reason = "period reopened",
                        version = g.version + 1,
                        updatedAt = now,
                        updatedByDevice = ctx.deviceId,
                        hlc = ctx.nextHlc(),
                    ),
                )
            }
            dao.updatePeriod(p.copy(closedAt = null, version = p.version + 1, updatedAt = now, hlc = ctx.nextHlc()))
            db.accountDao().audit(
                ctx,
                "budget.period_reopen",
                "budget_period",
                p.id,
                mapOf(
                    "budget" to b.name,
                    "moves" to groups.size.toString(),
                ),
            )
            groups.size
        }

    /** Every bucket a move added money to must still hold at least that much, so the reversal cannot make it negative. */
    private suspend fun ValidationScope.ensureMovesUnused(entries: List<ph.financewallet.data.db.entity.AllocationEntryEntity>) {
        entries
            .filter { it.amountMinor > 0 && (it.bucket == "GOAL" || it.bucket == "CATEGORY") }
            .groupBy { Triple(it.bucket, it.budgetPeriodId, it.goalId ?: it.categoryId) }
            .forEach { (key, added) ->
                val (bucket, periodId, id) = key
                val holds =
                    when (bucket) {
                        "GOAL" -> dao.goalAllocated(checkNotNull(id))
                        else -> dao.categoryAllocated(checkNotNull(periodId), checkNotNull(id))
                    }
                ensure(holds >= added.sumOf { it.amountMinor }, "periodId", "MOVED_MONEY_USED")
            }
    }

    /** Same rule as BudgetSupport.activeBudget: the budget exists and is not archived. */
    private suspend fun ValidationScope.activeBudget(id: String): BudgetEntity {
        val b = dao.budget(id) ?: notFound("budget", id)
        ensure(b.archivedAt == null, "budgetId", "BUDGET_ARCHIVED")
        return b
    }

    private fun touch(b: BudgetEntity) =
        b.copy(version = b.version + 1, updatedAt = ctx.nowMillis(), updatedByDevice = ctx.deviceId, hlc = ctx.nextHlc())
}
