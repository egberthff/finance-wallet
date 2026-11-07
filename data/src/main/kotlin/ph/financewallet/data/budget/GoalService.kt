package ph.financewallet.data.budget

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.GoalAccountEntity
import ph.financewallet.data.db.entity.SavingsGoalEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.allocation.AllocationDraft
import ph.financewallet.domain.allocation.AllocationTemplates
import ph.financewallet.domain.allocation.Bucket
import ph.financewallet.domain.allocation.BudgetMath
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

enum class GoalStatus { ACTIVE, PAUSED, ACHIEVED, ARCHIVED }

data class GoalDetails(
    val name: String,
    val target: Money,
    val targetDate: LocalDate? = null,
    val notes: String? = null,
    val icon: String = "savings",
    val colorArgb: Long = 0xFF2E7D32L,
)

data class GoalSummary(
    val id: String,
    val name: String,
    val status: GoalStatus,
    val target: Money,
    val allocated: Money,
    /** Floor-rounded, capped at 100 % (domain §6). */
    val progressBp: Int,
    val linkedAccountIds: Set<String>,
    /** Remaining room under the linked-account cap, or null for a virtual goal. */
    val linkedHeadroom: Money?,
    val version: Long,
)

/**
 * Savings goals (SAV-01..04): UC-20 create/edit/link and UC-21 contribute/withdraw/transfer/earmark
 * (docs/10-domain-model.md §6). Goal money is an allocation, never an expense, and net worth reads only the money
 * ledger, so goals can never inflate it. Withdrawals and goal-to-goal moves need `confirmed = true` and are also
 * rejected by a database trigger without a confirmation time.
 */
class GoalService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
    private val ledger: LedgerService,
) {
    private val support = BudgetSupport(db, ctx)
    private val store = AllocationStore(db, ctx)
    private val dao get() = db.budgetDao()

    suspend fun createGoal(
        currency: CurrencyCode,
        details: GoalDetails,
        linkedAccountIds: Set<String> = emptySet(),
    ): DomainResult<String> =
        db.validatedWrite {
            checkDetails(currency, details)
            val id = ctx.ids.newId()
            val now = ctx.nowMillis()
            dao.insertGoal(
                SavingsGoalEntity(
                    id = id,
                    name = details.name.trim(),
                    currency = currency.code,
                    targetMinor = details.target.minor,
                    targetDate = details.targetDate?.toString(),
                    status = GoalStatus.ACTIVE.name,
                    notes = details.notes?.ifBlank { null },
                    icon = details.icon,
                    colorArgb = details.colorArgb,
                    createdAt = now,
                    version = 1,
                    updatedAt = now,
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                ),
            )
            link(id, currency, linkedAccountIds)
            id
        }

    suspend fun editGoal(
        goalId: String,
        expectedVersion: Long,
        details: GoalDetails,
        status: GoalStatus,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val g = current(goalId, expectedVersion)
            checkDetails(CurrencyCode.of(g.currency), details)
            dao.updateGoal(
                touch(g).copy(
                    name = details.name.trim(),
                    targetMinor = details.target.minor,
                    targetDate = details.targetDate?.toString(),
                    notes = details.notes?.ifBlank { null },
                    icon = details.icon,
                    colorArgb = details.colorArgb,
                    status = status.name,
                ),
            )
        }

    /** Replaces the goal's linked accounts. Rejected if any goal would then hold more than its accounts' balance (EX-17). */
    suspend fun linkAccounts(
        goalId: String,
        expectedVersion: Long,
        accountIds: Set<String>,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val g = current(goalId, expectedVersion)
            dao.deleteGoalAccounts(goalId)
            link(goalId, CurrencyCode.of(g.currency), accountIds)
            dao.updateGoal(touch(g))
        }

    /** From a budget pool or category into a goal (SAV-02, EX-15). */
    suspend fun contribute(
        source: Bucket,
        goalId: String,
        amount: Money,
        idempotencyKey: String,
        relatedTxnId: String? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            store.existing(idempotencyKey) ?: contributeInternal(source, goalId, amount, idempotencyKey, relatedTxnId)
        }

    /** Goal → a period's pool. Requires [confirmed] (SAV-04). */
    suspend fun withdraw(
        goalId: String,
        toPeriodId: String,
        amount: Money,
        confirmed: Boolean,
        idempotencyKey: String,
        reason: String? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            store.existing(idempotencyKey) ?: run {
                val (b, _) = with(support) { openPeriod(toPeriodId) }
                val g = goal(goalId)
                ensure(g.currency == b.currency, "goalId", "GOAL_CURRENCY_MISMATCH")
                val c = CurrencyCode.of(g.currency)
                apply(c, listOf(Bucket.Goal(goalId), Bucket.Unallocated(toPeriodId)), idempotencyKey) {
                    it.withdrawFromGoal(goalId, toPeriodId, amount, confirmed, reason)
                }
            }
        }

    /** Goal → goal. Requires [confirmed] (SAV-04). */
    suspend fun transfer(
        fromGoalId: String,
        toGoalId: String,
        amount: Money,
        confirmed: Boolean,
        idempotencyKey: String,
        reason: String? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            store.existing(idempotencyKey) ?: run {
                val from = goal(fromGoalId)
                val to = contributable(toGoalId)
                ensure(from.currency == to.currency, "toGoalId", "GOAL_CURRENCY_MISMATCH")
                val c = CurrencyCode.of(from.currency)
                apply(c, listOf(Bucket.Goal(fromGoalId), Bucket.Goal(toGoalId)), idempotencyKey) {
                    it.transferBetweenGoals(fromGoalId, toGoalId, amount, confirmed, reason)
                }
            }
        }

    /** Earmark (+) or release (−, needs [confirmed]) "free money" for a goal without a budget (GOAL_DIRECT). */
    suspend fun earmark(
        goalId: String,
        delta: Money,
        idempotencyKey: String,
        confirmed: Boolean = false,
        relatedTxnId: String? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            store.existing(idempotencyKey) ?: earmarkInternal(goalId, delta, idempotencyKey, confirmed, relatedTxnId)
        }

    /**
     * One user action (domain §6): records a SAVINGS_CONTRIBUTION transfer [fromAccountId] → [toAccountId] and
     * allocates the same amount to the goal, from [source] if given (budget pool or category), otherwise as a direct
     * earmark. Both are stored in one database transaction with `related_txn_id`, or neither is.
     * The allocation is checked against the linked-account cap *after* the transfer, so moving money into a linked
     * account makes room for it.
     */
    suspend fun saveToAccount(
        header: TxnHeader,
        fromAccountId: String,
        toAccountId: String,
        amount: Money,
        goalId: String,
        source: Bucket? = null,
    ): DomainResult<SavedToGoal> =
        db.validatedWrite {
            val allocationKey = "${header.idempotencyKey}:goal"
            val txnId = ledger.record(header) { t, _ -> t.savingsContribution(fromAccountId, toAccountId, amount) }.bind()
            val groupId =
                store.existing(allocationKey)
                    ?: if (source != null) {
                        contributeInternal(source, goalId, amount, allocationKey, txnId)
                    } else {
                        earmarkInternal(goalId, amount, allocationKey, confirmed = false, relatedTxnId = txnId)
                    }
            SavedToGoal(txnId, groupId)
        }

    suspend fun summary(goalId: String): GoalSummary {
        val g = checkNotNull(dao.goal(goalId)) { "goal $goalId" }
        val c = CurrencyCode.of(g.currency)
        val allocated = Money(dao.goalAllocated(goalId), c)
        val target = Money(g.targetMinor, c)
        return GoalSummary(
            id = g.id,
            name = g.name,
            status = GoalStatus.valueOf(g.status),
            target = target,
            allocated = allocated,
            progressBp = BudgetMath.goalProgressBp(allocated, target),
            linkedAccountIds =
                dao
                    .allGoalAccounts()
                    .filter { it.goalId == goalId }
                    .map { it.ledgerAccountId }
                    .toSet(),
            linkedHeadroom = store.goalCap(c).headroom(goalId, c),
            version = g.version,
        )
    }

    suspend fun goals(): List<GoalSummary> = dao.goals().map { summary(it.id) }

    /** A goal's editable details (name, target, date, notes, look), for the edit form. Null if missing. */
    suspend fun details(goalId: String): GoalDetails? =
        dao.goal(goalId)?.let { g ->
            GoalDetails(
                name = g.name,
                target = Money(g.targetMinor, CurrencyCode.of(g.currency)),
                targetDate = g.targetDate?.let(LocalDate::parse),
                notes = g.notes,
                icon = g.icon,
                colorArgb = g.colorArgb,
            )
        }

    /**
     * Free money (dashboard, domain §6) in [currency] = Σ current balances of spendable asset accounts − Σ allocations
     * of goals not linked to any account (linked goals are already inside their accounts' balances).
     */
    suspend fun freeMoney(currency: CurrencyCode): Money {
        val today = ctx.today().toString()
        val spendable =
            db.ledgerDao().allFinancialAccounts().filter { it.spendable }.mapNotNull { fa ->
                val account = db.ledgerDao().ledgerAccount(fa.ledgerAccountId) ?: return@mapNotNull null
                if (account.accountClass != "ASSET" || account.currency != currency.code) return@mapNotNull null
                Money(db.ledgerDao().balance(fa.ledgerAccountId, today), currency)
            }
        val linked = dao.allGoalAccounts().map { it.goalId }.toSet()
        val unlinked =
            dao.goals().filter { it.currency == currency.code && it.id !in linked }.map { Money(dao.goalAllocated(it.id), currency) }
        return BudgetMath.freeMoney(currency, spendable, unlinked)
    }

    // ---- internals ----------------------------------------------------------------------------------------------

    private suspend fun ValidationScope.contributeInternal(
        source: Bucket,
        goalId: String,
        amount: Money,
        key: String,
        relatedTxnId: String?,
    ): String {
        val periodId =
            when (source) {
                is Bucket.Unallocated -> source.periodId
                is Bucket.Category -> source.periodId
                is Bucket.Goal -> fail("source", "USE_GOAL_TRANSFER")
            }
        val (b, _) = with(support) { openPeriod(periodId) }
        if (source is Bucket.Category) with(support) { expenseSpec(b.id, source.categoryId) }
        val g = contributable(goalId)
        ensure(g.currency == b.currency, "goalId", "GOAL_CURRENCY_MISMATCH")
        return apply(CurrencyCode.of(g.currency), listOf(source, Bucket.Goal(goalId)), key, relatedTxnId) {
            it.contributeToGoal(source, goalId, amount)
        }
    }

    private suspend fun ValidationScope.earmarkInternal(
        goalId: String,
        delta: Money,
        key: String,
        confirmed: Boolean,
        relatedTxnId: String?,
    ): String {
        val g = if (delta.isPositive) contributable(goalId) else goal(goalId)
        return apply(CurrencyCode.of(g.currency), listOf(Bucket.Goal(goalId)), key, relatedTxnId) {
            it.earmarkDirect(goalId, delta, confirmed)
        }
    }

    private suspend fun ValidationScope.apply(
        currency: CurrencyCode,
        buckets: List<Bucket>,
        key: String,
        relatedTxnId: String? = null,
        build: (AllocationTemplates) -> DomainResult<AllocationDraft>,
    ): String {
        val templates = AllocationTemplates(currency, store.balancesFor(buckets, currency), store.goalCap(currency))
        val d = build(templates).bind()
        return with(store) { writeGroup(d.kind, d.entries, d.changes, key, d.reason, d.confirmed, relatedTxnId) }
    }

    private suspend fun ValidationScope.link(
        goalId: String,
        currency: CurrencyCode,
        accountIds: Set<String>,
    ) {
        accountIds.forEach { id ->
            val account = db.ledgerDao().ledgerAccount(id)
            ensure(account != null && db.ledgerDao().financialAccount(id) != null, "accountIds", "ACCOUNT_NOT_REAL")
            ensure(checkNotNull(account).accountClass == "ASSET", "accountIds", "ACCOUNT_NOT_ASSET")
            ensure(account.currency == currency.code, "accountIds", "CURRENCY_MISMATCH")
            ensure(account.archivedAt == null, "accountIds", "ACCOUNT_ARCHIVED")
        }
        val now = ctx.nowMillis()
        if (accountIds.isNotEmpty()) {
            dao.insertGoalAccounts(accountIds.sorted().map { GoalAccountEntity(goalId, it, 1, now, ctx.deviceId, ctx.nextHlc()) })
        }
        val cap = store.goalCap(currency)
        dao.goals().filter { it.currency == currency.code }.forEach { other ->
            val headroom = cap.headroom(other.id, currency)
            ensure(headroom == null || !headroom.isNegative, "accountIds", "GOAL_EXCEEDS_LINKED_BALANCE")
        }
    }

    private suspend fun ValidationScope.goal(id: String): SavingsGoalEntity = dao.goal(id) ?: notFound("goal", id)

    private suspend fun ValidationScope.contributable(id: String): SavingsGoalEntity {
        val g = goal(id)
        ensure(g.status in CONTRIBUTABLE, "goalId", "GOAL_NOT_ACTIVE")
        return g
    }

    private suspend fun ValidationScope.current(
        id: String,
        expectedVersion: Long,
    ): SavingsGoalEntity {
        val g = goal(id)
        ensure(g.version == expectedVersion, "goalId", "STALE_VERSION")
        return g
    }

    private fun ValidationScope.checkDetails(
        currency: CurrencyCode,
        d: GoalDetails,
    ) {
        ensure(d.name.trim().length in 1..BudgetService.MAX_NAME, "name", "NAME_LENGTH")
        ensure(d.target.currency == currency, "target", "CURRENCY_MISMATCH")
        ensure(d.target.isPositive, "target", "AMOUNT_NOT_POSITIVE")
        ensure((d.notes?.length ?: 0) <= MAX_NOTES, "notes", "TOO_LONG")
    }

    private fun touch(g: SavingsGoalEntity) =
        g.copy(version = g.version + 1, updatedAt = ctx.nowMillis(), updatedByDevice = ctx.deviceId, hlc = ctx.nextHlc())

    companion object {
        /** Goals that may receive money; PAUSED and ARCHIVED goals only release it. */
        internal val CONTRIBUTABLE = setOf(GoalStatus.ACTIVE.name, GoalStatus.ACHIEVED.name)
        private const val MAX_NOTES = 2000
    }
}

data class SavedToGoal(
    val txnId: String,
    val allocationGroupId: String,
)
