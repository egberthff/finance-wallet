package ph.financewallet.data.advanced

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.OccurrenceEntity
import ph.financewallet.data.db.entity.RecurringRuleEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence
import ph.financewallet.domain.recurring.RecurrenceCalculator
import java.time.LocalDate

enum class RuleKind { BILL, SUBSCRIPTION, RECURRING_TXN, EXPECTED_INCOME }

enum class RuleStatus { ACTIVE, PAUSED, ENDED }

/** Display state of an occurrence. DUE_TODAY and OVERDUE are derived from dates, never stored (domain §9). */
enum class OccurrenceState { UPCOMING, DUE_TODAY, OVERDUE, PAID, SKIPPED }

data class RuleSpec(
    val kind: RuleKind,
    val name: String,
    val amount: Money,
    val recurrence: Recurrence,
    val amountIsEstimate: Boolean = false,
    val endDate: LocalDate? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    /** Only for RECURRING_TXN, opt-in (INC-03). Requires an account and a category. */
    val autoPost: Boolean = false,
    val reminderDaysBefore: Int? = null,
)

data class UpcomingItem(
    val occurrenceId: String,
    val ruleId: String,
    val name: String,
    val kind: RuleKind,
    val dueDate: LocalDate,
    val amount: Money,
    val amountIsEstimate: Boolean,
    val state: OccurrenceState,
    val paidTxnId: String?,
)

data class RecurringCost(
    val ruleId: String,
    val name: String,
    val kind: RuleKind,
    /** Exact sum of the rule's amounts due in the next 12 months (no averaging, no rounding). */
    val nextTwelveMonths: Money,
)

/**
 * Bills, subscriptions, recurring transactions and expected income: UC-22 (rules, occurrence window), UC-23 (mark
 * paid by creating or linking a real transaction, skip), UC-24 (opt-in auto-post). A scheduled bill is never a
 * payment: an occurrence is PAID only when linked to a transaction (BILL-02, enforced by trigger CK_OCC_PAID_LINK).
 * Expected income never touches balances (INC-02).
 */
class RecurringService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
    private val ledger: LedgerService,
) {
    private val dao get() = db.advancedDao()

    suspend fun createRule(spec: RuleSpec): DomainResult<String> =
        db.validatedWrite {
            checkSpec(spec)
            val id = ctx.ids.newId()
            dao.insertRule(entity(id, spec, created = ctx.nowMillis(), version = 1))
            materializeRule(checkNotNull(dao.rule(id)), ctx.today())
            id
        }

    /** Changes apply to future, unpaid occurrences only; paid and skipped history stays as it was (domain §9). */
    suspend fun editRule(
        ruleId: String,
        expectedVersion: Long,
        spec: RuleSpec,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val current = dao.rule(ruleId) ?: notFound("rule", ruleId)
            ensure(current.version == expectedVersion, "ruleId", "STALE_VERSION")
            ensure(spec.kind.name == current.kind, "kind", "KIND_CHANGE_NOT_ALLOWED")
            checkSpec(spec)
            val updated = entity(ruleId, spec, current.createdAt, current.version + 1).copy(status = current.status)
            dao.updateRule(updated)
            dao.deleteUpcomingFrom(ruleId, ctx.today().toString())
            materializeRule(updated, ctx.today())
        }

    suspend fun setStatus(
        ruleId: String,
        status: RuleStatus,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val current = dao.rule(ruleId) ?: notFound("rule", ruleId)
            dao.updateRule(
                current.copy(status = status.name, version = current.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()),
            )
            if (status != RuleStatus.ACTIVE) dao.deleteUpcomingFrom(ruleId, ctx.today().toString())
        }

    /**
     * UC-22 internal: ensures occurrences exist for every active rule in the window today − 31 d … today + 62 d
     * (idempotent: unique by rule and due date). Occurrences whose linked transaction was deleted become due again.
     */
    suspend fun materialize(): DomainResult<Int> =
        db.validatedWrite {
            dao.paidWithDeletedTxn().forEach { o ->
                dao.updateOccurrence(
                    o.copy(
                        status = "UPCOMING",
                        paidTxnId = null,
                        version = o.version + 1,
                        updatedAt = ctx.nowMillis(),
                        hlc = ctx.nextHlc(),
                    ),
                )
            }
            dao.rules().filter { it.status == RuleStatus.ACTIVE.name }.sumOf { materializeRule(it, ctx.today()) }
        }

    /** UC-23: records the payment (or income) as a new transaction and links it. [amount] may differ from the rule's. */
    suspend fun markPaid(
        occurrenceId: String,
        header: TxnHeader,
        accountId: String? = null,
        categoryId: String? = null,
        amount: Money? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            db.ledgerDao().txnByIdempotencyKey(header.idempotencyKey)?.let { return@validatedWrite it.id }
            val (o, rule) = unpaid(occurrenceId)
            val account = accountId ?: rule.accountId ?: fail("accountId", "ACCOUNT_REQUIRED")
            val category = categoryId ?: rule.categoryId ?: fail("categoryId", "CATEGORY_REQUIRED")
            val money = amount ?: Money(o.amountMinor, CurrencyCode.of(rule.currency))
            val txnId = recordFor(rule, header, account, category, money)
            link(o, txnId)
            txnId
        }

    /** UC-23: links an existing transaction (e.g. one imported or entered earlier) instead of creating one. */
    suspend fun linkExisting(
        occurrenceId: String,
        txnId: String,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val (o, _) = unpaid(occurrenceId)
            val txn = db.ledgerDao().txn(txnId) ?: notFound("transaction", txnId)
            ensure(txn.deletedAt == null, "txnId", "TXN_DELETED")
            ensure(dao.occurrenceForTxn(txnId) == null, "txnId", "TXN_ALREADY_LINKED")
            link(o, txnId)
        }

    suspend fun skip(occurrenceId: String): DomainResult<Unit> =
        db.validatedWrite {
            val (o, _) = unpaid(occurrenceId)
            dao.updateOccurrence(o.copy(status = "SKIPPED", version = o.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()))
        }

    /**
     * UC-24: for RECURRING_TXN rules with auto-post enabled, creates the transactions due on or before today.
     * Idempotent per (rule, due date) through the key `recurring:{rule}:{date}`. Returns how many were created.
     */
    suspend fun autoPost(): DomainResult<Int> =
        db.validatedWrite {
            materialize().bind()
            val today = ctx.today()
            var posted = 0
            val templates =
                dao.rules().filter {
                    it.status == RuleStatus.ACTIVE.name && it.autoPost &&
                        it.kind == RuleKind.RECURRING_TXN.name
                }
            templates.forEach { rule ->
                val due = dao.occurrences(rule.id).filter { it.status == "UPCOMING" && !LocalDate.parse(it.dueDate).isAfter(today) }
                due.forEach { o ->
                    val header =
                        TxnHeader(
                            LocalDate.parse(o.dueDate),
                            "recurring:${rule.id}:${o.dueDate}",
                            description = rule.name,
                            source = "SYSTEM",
                        )
                    val txnId =
                        recordFor(
                            rule,
                            header,
                            checkNotNull(rule.accountId),
                            checkNotNull(rule.categoryId),
                            Money(o.amountMinor, CurrencyCode.of(rule.currency)),
                        )
                    link(o, txnId)
                    posted++
                }
            }
            posted
        }

    /** Occurrences from [from] to [to] with their derived state (dashboard "upcoming bills", bills screen). */
    suspend fun upcoming(
        from: LocalDate,
        to: LocalDate,
    ): List<UpcomingItem> {
        val rules = dao.rules().associateBy { it.id }
        val today = ctx.today()
        return dao.occurrencesBetween(from.toString(), to.toString()).mapNotNull { o ->
            val r = rules[o.ruleId] ?: return@mapNotNull null
            val due = LocalDate.parse(o.dueDate)
            val state =
                when {
                    o.status == "PAID" -> OccurrenceState.PAID
                    o.status == "SKIPPED" -> OccurrenceState.SKIPPED
                    due.isBefore(today) -> OccurrenceState.OVERDUE
                    due == today -> OccurrenceState.DUE_TODAY
                    else -> OccurrenceState.UPCOMING
                }
            UpcomingItem(
                o.id,
                r.id,
                r.name,
                RuleKind.valueOf(r.kind),
                due,
                Money(o.amountMinor, CurrencyCode.of(r.currency)),
                r.amountIsEstimate,
                state,
                o.paidTxnId,
            )
        }
    }

    /** Unpaid occurrences whose reminder window has started (or that are overdue), for local notifications (BILL-03). */
    suspend fun remindersDue(): List<UpcomingItem> {
        val today = ctx.today()
        val rules = dao.rules().associateBy { it.id }
        return upcoming(today.minusDays(WINDOW_BACK), today.plusDays(MAX_REMINDER_DAYS.toLong())).filter { item ->
            val days = rules[item.ruleId]?.reminderDaysBefore ?: return@filter false
            item.state in setOf(OccurrenceState.UPCOMING, OccurrenceState.DUE_TODAY, OccurrenceState.OVERDUE) &&
                !item.dueDate.minusDays(days.toLong()).isAfter(today) && item.kind != RuleKind.EXPECTED_INCOME
        }
    }

    /** Recurring-expenses report (RPT-01): each active rule's exact total due in the next 12 months. */
    suspend fun costs(): List<RecurringCost> {
        val today = ctx.today()
        return dao.rules().filter { it.status == RuleStatus.ACTIVE.name }.map { r ->
            val dates =
                RecurrenceCalculator.dueDates(
                    recurrenceOf(r),
                    today,
                    today.plusYears(1).minusDays(1),
                    r.endDate?.let(LocalDate::parse),
                )
            val c = CurrencyCode.of(r.currency)
            RecurringCost(r.id, r.name, RuleKind.valueOf(r.kind), Money(Math.multiplyExact(r.amountMinor, dates.size.toLong()), c))
        }
    }

    suspend fun rules() = dao.rules()

    // ---- internals ----------------------------------------------------------------------------------------------

    private suspend fun materializeRule(
        rule: RecurringRuleEntity,
        today: LocalDate,
    ): Int {
        if (rule.status != RuleStatus.ACTIVE.name) return 0
        val dates =
            RecurrenceCalculator.dueDates(
                recurrenceOf(rule),
                today.minusDays(WINDOW_BACK),
                today.plusDays(WINDOW_AHEAD),
                rule.endDate?.let(LocalDate::parse),
            )
        val now = ctx.nowMillis()
        val rows =
            dates.map {
                OccurrenceEntity(
                    ctx.ids.newId(),
                    rule.id,
                    it.toString(),
                    rule.amountMinor,
                    "UPCOMING",
                    null,
                    1,
                    now,
                    ctx.deviceId,
                    ctx.nextHlc(),
                )
            }
        return if (rows.isEmpty()) 0 else dao.insertOccurrences(rows).count { it != -1L }
    }

    private suspend fun ValidationScope.recordFor(
        rule: RecurringRuleEntity,
        header: TxnHeader,
        accountId: String,
        categoryId: String,
        amount: Money,
    ): String {
        val isIncome = db.ledgerDao().ledgerAccount(categoryId)?.accountClass == "INCOME"
        ensure(
            isIncome == (rule.kind == RuleKind.EXPECTED_INCOME.name) || rule.kind == RuleKind.RECURRING_TXN.name,
            "categoryId",
            "CATEGORY_WRONG_CLASS",
        )
        return ledger
            .record(header.copy(description = header.description.ifBlank { rule.name })) { t, _ ->
                if (isIncome) {
                    t.income(
                        accountId,
                        listOf(Split(categoryId, amount)),
                    )
                } else {
                    t.expense(accountId, listOf(Split(categoryId, amount)))
                }
            }.bind()
    }

    private suspend fun link(
        o: OccurrenceEntity,
        txnId: String,
    ) = dao.updateOccurrence(
        o.copy(status = "PAID", paidTxnId = txnId, version = o.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()),
    )

    private suspend fun ValidationScope.unpaid(occurrenceId: String): Pair<OccurrenceEntity, RecurringRuleEntity> {
        val o = dao.occurrence(occurrenceId) ?: notFound("occurrence", occurrenceId)
        ensure(o.status == "UPCOMING", "occurrenceId", if (o.status == "PAID") "ALREADY_PAID" else "OCCURRENCE_SKIPPED")
        return o to checkNotNull(dao.rule(o.ruleId))
    }

    private suspend fun ValidationScope.checkSpec(s: RuleSpec) {
        ensure(s.name.trim().length in 1..MAX_NAME, "name", "NAME_LENGTH")
        ensure(s.amount.isPositive, "amount", "AMOUNT_NOT_POSITIVE")
        ensure(s.reminderDaysBefore == null || s.reminderDaysBefore in 0..MAX_REMINDER_DAYS, "reminderDaysBefore", "REMINDER_OUT_OF_RANGE")
        ensure(s.endDate == null || !s.endDate.isBefore(s.recurrence.anchorDate), "endDate", "END_BEFORE_START")
        ensure(!s.autoPost || s.kind == RuleKind.RECURRING_TXN, "autoPost", "AUTO_POST_ONLY_FOR_TEMPLATES")
        if (s.autoPost) ensure(s.accountId != null && s.categoryId != null, "autoPost", "AUTO_POST_NEEDS_ACCOUNT_AND_CATEGORY")
        s.accountId?.let { id ->
            val a = db.ledgerDao().ledgerAccount(id)
            ensure(a != null && db.ledgerDao().financialAccount(id) != null, "accountId", "ACCOUNT_NOT_REAL")
            ensure(checkNotNull(a).currency == s.amount.currency.code, "accountId", "CURRENCY_MISMATCH")
        }
        s.categoryId?.let { id ->
            val cls = db.ledgerDao().ledgerAccount(id)?.accountClass ?: notFound("category", id)
            ensure(db.ledgerDao().category(id) != null, "categoryId", "NOT_A_CATEGORY")
            val expected =
                when (s.kind) {
                    RuleKind.EXPECTED_INCOME -> setOf("INCOME")
                    RuleKind.RECURRING_TXN -> setOf("INCOME", "EXPENSE")
                    else -> setOf("EXPENSE")
                }
            ensure(cls in expected, "categoryId", "CATEGORY_WRONG_CLASS")
        }
    }

    private fun entity(
        id: String,
        s: RuleSpec,
        created: Long,
        version: Long,
    ): RecurringRuleEntity {
        val r = s.recurrence
        val cols =
            when (r) {
                is Recurrence.Weekly -> RuleColumns("WEEKLY")
                is Recurrence.Biweekly -> RuleColumns("BIWEEKLY")
                is Recurrence.EveryNDays -> RuleColumns("EVERY_N_DAYS", interval = r.days)
                is Recurrence.Monthly -> RuleColumns("MONTHLY", day1 = r.day)
                is Recurrence.SemiMonthly -> RuleColumns("SEMI_MONTHLY", day1 = r.day1, day2 = r.day2)
                is Recurrence.Quarterly -> RuleColumns("QUARTERLY", day1 = r.day)
                is Recurrence.Yearly -> RuleColumns("YEARLY", day1 = r.day, month = r.month)
            }
        val now = ctx.nowMillis()
        return RecurringRuleEntity(
            id = id,
            kind = s.kind.name,
            name = s.name.trim(),
            amountMinor = s.amount.minor,
            currency = s.amount.currency.code,
            amountIsEstimate = s.amountIsEstimate,
            frequency = cols.frequency,
            intervalN = cols.interval,
            day1 = cols.day1,
            day2 = cols.day2,
            monthOfYear = cols.month,
            anchorDate = r.anchorDate.toString(),
            endDate = s.endDate?.toString(),
            accountId = s.accountId,
            categoryId = s.categoryId,
            templateJson = null,
            autoPost = s.autoPost,
            reminderDaysBefore = s.reminderDaysBefore,
            status = RuleStatus.ACTIVE.name,
            createdAt = created,
            version = version,
            updatedAt = now,
            updatedByDevice = ctx.deviceId,
            hlc = ctx.nextHlc(),
        )
    }

    private data class RuleColumns(
        val frequency: String,
        val interval: Int? = null,
        val day1: Int? = null,
        val day2: Int? = null,
        val month: Int? = null,
    )

    companion object {
        private const val MAX_NAME = 80
        const val MAX_REMINDER_DAYS = 30
        private const val WINDOW_BACK = 31L
        private const val WINDOW_AHEAD = 62L

        fun recurrenceOf(r: RecurringRuleEntity): Recurrence {
            val anchor = LocalDate.parse(r.anchorDate)
            return when (r.frequency) {
                "WEEKLY" -> Recurrence.Weekly(anchor)
                "BIWEEKLY" -> Recurrence.Biweekly(anchor)
                "EVERY_N_DAYS" -> Recurrence.EveryNDays(anchor, checkNotNull(r.intervalN))
                "MONTHLY" -> Recurrence.Monthly(anchor, checkNotNull(r.day1))
                "SEMI_MONTHLY" -> Recurrence.SemiMonthly(anchor, checkNotNull(r.day1), checkNotNull(r.day2))
                "QUARTERLY" -> Recurrence.Quarterly(anchor, checkNotNull(r.day1))
                "YEARLY" -> Recurrence.Yearly(anchor, checkNotNull(r.monthOfYear), checkNotNull(r.day1))
                else -> error("unknown frequency ${r.frequency}")
            }
        }
    }
}
