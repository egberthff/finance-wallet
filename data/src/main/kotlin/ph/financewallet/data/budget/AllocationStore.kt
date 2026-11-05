package ph.financewallet.data.budget

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.AllocationEntryEntity
import ph.financewallet.data.db.entity.AllocationGroupEntity
import ph.financewallet.data.db.entity.BudgetEntity
import ph.financewallet.data.db.entity.BudgetPeriodEntity
import ph.financewallet.data.ledger.RevisionJson
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.allocation.AllocationEntry
import ph.financewallet.domain.allocation.AllocationKind
import ph.financewallet.domain.allocation.Bucket
import ph.financewallet.domain.allocation.BucketBalances
import ph.financewallet.domain.allocation.BucketChange
import ph.financewallet.domain.allocation.BudgetMath
import ph.financewallet.domain.allocation.GoalLink
import ph.financewallet.domain.allocation.IncomeMode
import ph.financewallet.domain.allocation.LinkedGoalCap
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money

/**
 * Shared allocation-ledger plumbing for [BudgetService] and [GoalService]: derived figures (funding, available,
 * spent), bucket balances for the domain templates, the linked-goal cap, and writing one allocation group.
 * Every function runs inside the caller's database transaction.
 */
internal class AllocationStore(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
) {
    private val dao get() = db.budgetDao()

    // ---- derived budget figures (docs/10-domain-model.md §5.4) -------------------------------------------------

    suspend fun funding(
        budget: BudgetEntity,
        period: BudgetPeriodEntity,
    ): Money {
        val c = CurrencyCode.of(budget.currency)
        val mode = IncomeMode.valueOf(budget.incomeMode)
        val tracked = if (mode == IncomeMode.TRACKED) Money(trackedIncome(budget, period), c) else Money.zero(c)
        return BudgetMath.funding(
            mode = mode,
            trackedIncome = tracked,
            fixedAmount = budget.fixedIncomeMinor?.let { Money(it, c) },
            manualFunds = Money(dao.manualFunds(period.id), c),
        )
    }

    /** TRACKED income: the budget's own income categories if it has any, otherwise all income in scope (documented). */
    private suspend fun trackedIncome(
        budget: BudgetEntity,
        period: BudgetPeriodEntity,
    ): Long {
        val hasIncomeCategories =
            dao.budgetCategories(budget.id).any { db.ledgerDao().ledgerAccount(it.categoryId)?.accountClass == "INCOME" }
        return if (hasIncomeCategories) {
            dao.budgetIncome(budget.id, period.startDate, period.endDate, budget.currency, budget.accountFilter)
        } else {
            dao.allIncome(budget.id, period.startDate, period.endDate, budget.currency, budget.accountFilter)
        }
    }

    suspend fun available(
        budget: BudgetEntity,
        period: BudgetPeriodEntity,
    ): Money =
        BudgetMath.availableToBudget(
            funding(budget, period),
            Money(dao.unallocatedMovements(period.id), CurrencyCode.of(budget.currency)),
        )

    suspend fun spent(
        budget: BudgetEntity,
        period: BudgetPeriodEntity,
        categoryId: String,
    ): Money =
        Money(
            dao.categorySpent(budget.id, categoryId, period.startDate, period.endDate, budget.currency, budget.accountFilter),
            CurrencyCode.of(budget.currency),
        )

    suspend fun allocated(
        periodId: String,
        categoryId: String,
        currency: CurrencyCode,
    ): Money = Money(dao.categoryAllocated(periodId, categoryId), currency)

    // ---- inputs for the domain templates -----------------------------------------------------------------------

    /** Current balance of each of [buckets]: available-to-budget for UNALLOCATED, Σ entries otherwise. */
    suspend fun balancesFor(
        buckets: Collection<Bucket>,
        currency: CurrencyCode,
    ): BucketBalances {
        val values =
            buckets.distinct().associateWith { bucket ->
                when (bucket) {
                    is Bucket.Unallocated -> {
                        val period = checkNotNull(dao.period(bucket.periodId)) { "period ${bucket.periodId}" }
                        available(checkNotNull(dao.budget(period.budgetId)), period)
                    }

                    is Bucket.Category -> {
                        allocated(bucket.periodId, bucket.categoryId, currency)
                    }

                    is Bucket.Goal -> {
                        Money(dao.goalAllocated(bucket.goalId), currency)
                    }
                }
            }
        return BucketBalances { bucket -> values[bucket] ?: error("balance of $bucket was not loaded") }
    }

    /** Linked-account cap over all goals (SAV-03, EX-17); account balances are current balances as of today. */
    suspend fun goalCap(currency: CurrencyCode): LinkedGoalCap {
        val links = dao.allGoalAccounts().groupBy({ it.goalId }, { it.ledgerAccountId })
        val goals = dao.goals()
        val allocatedByGoal = goals.associate { it.id to Money(dao.goalAllocated(it.id), CurrencyCode.of(it.currency)) }
        val accountIds = links.values.flatten().toSet()
        val today = ctx.today().toString()
        val balances =
            accountIds.associateWith { id ->
                val account = checkNotNull(db.ledgerDao().ledgerAccount(id))
                Money(db.ledgerDao().balance(id, today), CurrencyCode.of(checkNotNull(account.currency)))
            }
        return LinkedGoalCap(
            links = goals.map { GoalLink(it.id, links[it.id].orEmpty().toSet()) },
            allocated = { id -> allocatedByGoal[id] ?: Money.zero(currency) },
            accountBalance = { id -> balances.getValue(id) },
        )
    }

    // ---- writing ----------------------------------------------------------------------------------------------

    /** Existing group id for [key], if this action was already applied (idempotency). */
    suspend fun existing(key: String): String? = dao.groupByKey(key)?.id

    /**
     * Writes one allocation group with its entries. [changes] are recorded as the before/after snapshot (SAV-04);
     * [note] adds context (e.g. the rollover remaining) to the snapshot.
     */
    suspend fun ValidationScope.writeGroup(
        kind: AllocationKind,
        entries: List<AllocationEntry>,
        changes: List<BucketChange>,
        idempotencyKey: String,
        reason: String? = null,
        confirmed: Boolean = false,
        relatedTxnId: String? = null,
        note: Map<String, String> = emptyMap(),
    ): String {
        ensure(idempotencyKey.isNotBlank(), "idempotencyKey", "IDEMPOTENCY_KEY_REQUIRED")
        if (relatedTxnId != null) {
            ensure(db.ledgerDao().txn(relatedTxnId)?.deletedAt == null, "relatedTxnId", "RELATED_TXN_NOT_FOUND")
        }
        val id = ctx.ids.newId()
        val now = ctx.nowMillis()
        dao.insertGroup(
            AllocationGroupEntity(
                id = id,
                kind = kind.name,
                effectiveDate = ctx.today().toString(),
                reason = reason?.trim()?.ifEmpty { null },
                relatedTxnId = relatedTxnId,
                snapshotJson = snapshotJson(kind, changes, note),
                confirmedAt = if (confirmed) now else null,
                idempotencyKey = idempotencyKey,
                createdAt = now,
                deletedAt = null,
                version = 1,
                updatedAt = now,
                updatedByDevice = ctx.deviceId,
                hlc = ctx.nextHlc(),
            ),
        )
        if (entries.isNotEmpty()) dao.insertAllocationEntries(entries.map { entryRow(id, it) })
        return id
    }

    private fun entryRow(
        groupId: String,
        e: AllocationEntry,
    ): AllocationEntryEntity {
        val b = e.bucket
        return AllocationEntryEntity(
            id = ctx.ids.newId(),
            groupId = groupId,
            bucket =
                when (b) {
                    is Bucket.Unallocated -> "UNALLOCATED"
                    is Bucket.Category -> "CATEGORY"
                    is Bucket.Goal -> "GOAL"
                },
            budgetPeriodId = (b as? Bucket.Unallocated)?.periodId ?: (b as? Bucket.Category)?.periodId,
            categoryId = (b as? Bucket.Category)?.categoryId,
            goalId = (b as? Bucket.Goal)?.goalId,
            amountMinor = e.amount.minor,
            currency = e.amount.currency.code,
        )
    }

    private fun snapshotJson(
        kind: AllocationKind,
        changes: List<BucketChange>,
        note: Map<String, String>,
    ): String =
        buildString {
            append("{\"kind\":\"").append(kind.name).append("\",\"changes\":[")
            changes.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append("{\"bucket\":\"").append(RevisionJson.escape(describe(c.bucket))).append('"')
                append(",\"previousMinor\":\"").append(c.previous.minor).append('"')
                append(",\"updatedMinor\":\"").append(c.updated.minor).append('"')
                append(",\"currency\":\"").append(c.updated.currency.code).append("\"}")
            }
            append(']')
            note.toSortedMap().forEach { (k, v) ->
                append(",\"")
                    .append(RevisionJson.escape(k))
                    .append("\":\"")
                    .append(RevisionJson.escape(v))
                    .append('"')
            }
            append('}')
        }

    private fun describe(bucket: Bucket): String =
        when (bucket) {
            is Bucket.Unallocated -> "UNALLOCATED:${bucket.periodId}"
            is Bucket.Category -> "CATEGORY:${bucket.periodId}:${bucket.categoryId}"
            is Bucket.Goal -> "GOAL:${bucket.goalId}"
        }
}
