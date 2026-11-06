package ph.financewallet.data.advanced

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.DebtEntity
import ph.financewallet.data.db.entity.DebtScheduleItemEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.NewAccount
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.debt.DebtMath
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.recurring.Recurrence
import java.time.LocalDate

enum class DebtKind(
    val accountType: FinancialAccountType,
) {
    PERSONAL_LOAN(FinancialAccountType.LOAN),
    CREDIT_CARD(FinancialAccountType.CREDIT_CARD),
    INSTALLMENT(FinancialAccountType.LOAN),
    BORROWED(FinancialAccountType.BORROWED),
    OTHER(FinancialAccountType.LOAN),
}

enum class DebtStatus { ACTIVE, PAID_OFF, DEFAULTED, CLOSED }

/** Installment plan: [count] payments on [recurrence]; each is [installment], or an equal split of the original. */
data class InstallmentPlan(
    val recurrence: Recurrence,
    val count: Int,
    val installment: Money? = null,
)

/**
 * UC-12. Either the borrowed money arrived in [receivedIntoAccountId] (recorded as a DEBT_DISBURSEMENT, never income,
 * DEBT-03), or the debt already existed and [currentlyOwed] is its opening balance.
 */
data class NewDebt(
    val name: String,
    val kind: DebtKind,
    val currency: CurrencyCode,
    val original: Money,
    val startDate: LocalDate,
    val receivedIntoAccountId: String? = null,
    val currentlyOwed: Money? = null,
    val counterparty: String? = null,
    /** Informational only: the app never computes lender interest (domain §7). */
    val interestRateBp: Int? = null,
    val plan: InstallmentPlan? = null,
    val idempotencyKey: String,
)

data class DebtSummary(
    val id: String,
    val name: String,
    val kind: DebtKind,
    val status: DebtStatus,
    val original: Money,
    val outstanding: Money,
    val interestAndFeesPaid: Money,
    val remainingInstallments: Int?,
    val nextDue: LocalDate?,
    val nextDueAmount: Money?,
)

/**
 * Debts and liabilities UC-11 (payments) and UC-12 (create / disbursement), DEBT-01..03, docs/10-domain-model.md §7.
 * Outstanding is always −balance of the liability account; it is never stored.
 */
class DebtService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
    private val ledger: LedgerService,
) {
    private val dao get() = db.advancedDao()

    suspend fun create(req: NewDebt): DomainResult<String> =
        db.validatedWrite {
            ensure(req.idempotencyKey.isNotBlank(), "idempotencyKey", "IDEMPOTENCY_KEY_REQUIRED")
            val createdKey = "debt-create:${req.idempotencyKey}"
            db.accountDao().meta(createdKey)?.let { return@validatedWrite it }
            ensure(req.original.currency == req.currency && req.original.isPositive, "original", "AMOUNT_NOT_POSITIVE")
            ensure((req.receivedIntoAccountId == null) != (req.currentlyOwed == null), "receivedIntoAccountId", "DEBT_SOURCE_REQUIRED")
            req.currentlyOwed?.let { ensure(it.currency == req.currency && !it.isNegative, "currentlyOwed", "AMOUNT_NEGATIVE") }
            ensure(req.interestRateBp == null || req.interestRateBp >= 0, "interestRateBp", "AMOUNT_NEGATIVE")
            val opening = req.currentlyOwed ?: Money.zero(req.currency)
            val id = ledger.createAccount(NewAccount(req.name, req.kind.accountType, req.currency, req.startDate, opening)).bind()
            req.receivedIntoAccountId?.let { into ->
                ledger
                    .record(
                        TxnHeader(req.startDate, "${req.idempotencyKey}:disbursement", description = req.name, source = "MANUAL"),
                    ) { t, _ ->
                        t.debtDisbursement(into, id, req.original)
                    }.bind()
            }
            dao.insertDebt(entity(id, req))
            req.plan?.let { writeSchedule(id, req.original, it) }
            db.accountDao().setMeta(createdKey, id)
            id
        }

    /**
     * UC-11: a payment from [fromAccountId]. Only [principal] reduces the debt; [interest] and [fees] are spending
     * (DEBT-02). With [scheduleSeq], the payment is linked to that installment. A debt whose outstanding reaches zero
     * is marked PAID_OFF.
     */
    suspend fun recordPayment(
        debtId: String,
        header: TxnHeader,
        fromAccountId: String,
        principal: Money,
        interest: Money,
        fees: Money,
        scheduleSeq: Int? = null,
        feeCategoryId: String? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            val debt = dao.debt(debtId) ?: notFound("debt", debtId)
            val item =
                scheduleSeq?.let { seq ->
                    dao.unpaidSchedule(debtId).firstOrNull { it.seq == seq } ?: fail("scheduleSeq", "INSTALLMENT_ALREADY_PAID")
                }
            val txnId =
                ledger
                    .record(
                        header,
                    ) { t, _ -> t.debtPayment(fromAccountId, debtId, principal, interest, fees, feeCategoryId) }
                    .bind()
            item?.let {
                dao.updateScheduleItem(
                    it.copy(paidTxnId = txnId, version = it.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()),
                )
            }
            val outstanding = DebtMath.outstanding(ledger.balance(debtId, MAX_DATE))
            if (!outstanding.isPositive && debt.status == DebtStatus.ACTIVE.name) {
                dao.updateDebt(touch(debt).copy(status = DebtStatus.PAID_OFF.name))
            }
            txnId
        }

    suspend fun setStatus(
        debtId: String,
        expectedVersion: Long,
        status: DebtStatus,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val debt = dao.debt(debtId) ?: notFound("debt", debtId)
            ensure(debt.version == expectedVersion, "debtId", "STALE_VERSION")
            dao.updateDebt(touch(debt).copy(status = status.name))
        }

    suspend fun summaries(): List<DebtSummary> = dao.debts().map { summary(it) }

    suspend fun summary(debtId: String): DebtSummary = summary(checkNotNull(dao.debt(debtId)) { "debt $debtId" })

    suspend fun schedule(debtId: String) = dao.schedule(debtId)

    private suspend fun summary(d: DebtEntity): DebtSummary {
        val account = checkNotNull(db.ledgerDao().ledgerAccount(d.ledgerAccountId))
        val c = CurrencyCode.of(checkNotNull(account.currency))
        val unpaid = dao.unpaidSchedule(d.ledgerAccountId)
        val hasSchedule = dao.schedule(d.ledgerAccountId).isNotEmpty()
        return DebtSummary(
            id = d.ledgerAccountId,
            name = account.name,
            kind = DebtKind.valueOf(d.kind),
            status = DebtStatus.valueOf(d.status),
            original = Money(d.originalMinor, c),
            outstanding = DebtMath.outstanding(ledger.balance(d.ledgerAccountId, MAX_DATE)),
            interestAndFeesPaid = Money(dao.interestAndFeesPaid(d.ledgerAccountId), c),
            remainingInstallments = if (hasSchedule) unpaid.size else null,
            nextDue = unpaid.firstOrNull()?.let { LocalDate.parse(it.dueDate) },
            nextDueAmount = unpaid.firstOrNull()?.let { Money(it.amountDueMinor, c) },
        )
    }

    private suspend fun ValidationScope.writeSchedule(
        debtId: String,
        original: Money,
        plan: InstallmentPlan,
    ) {
        val total =
            plan.installment?.let {
                ensure(it.currency == original.currency && it.isPositive, "installment", "AMOUNT_NOT_POSITIVE")
                Money(Math.multiplyExact(it.minor, plan.count.toLong()), it.currency)
            } ?: original
        val items = DebtMath.equalSchedule(total, plan.count, plan.recurrence).bind()
        val now = ctx.nowMillis()
        dao.insertScheduleItems(
            items.map {
                DebtScheduleItemEntity(
                    ctx.ids.newId(),
                    debtId,
                    it.seq,
                    it.dueDate.toString(),
                    it.amountDue.minor,
                    null,
                    1,
                    now,
                    ctx.deviceId,
                    ctx.nextHlc(),
                )
            },
        )
    }

    private fun entity(
        id: String,
        req: NewDebt,
    ): DebtEntity {
        val now = ctx.nowMillis()
        val plan = req.plan
        return DebtEntity(
            ledgerAccountId = id,
            kind = req.kind.name,
            counterparty = req.counterparty?.trim()?.ifEmpty { null },
            originalMinor = req.original.minor,
            startDate = req.startDate.toString(),
            interestRateBp = req.interestRateBp,
            interestBasis = null,
            paymentFrequency = plan?.recurrence?.let(::frequencyOf),
            installmentMinor = plan?.installment?.minor,
            installmentsTotal = plan?.count,
            dueDay = (plan?.recurrence as? Recurrence.Monthly)?.day,
            status = DebtStatus.ACTIVE.name,
            version = 1,
            updatedAt = now,
            updatedByDevice = ctx.deviceId,
            hlc = ctx.nextHlc(),
        )
    }

    private fun frequencyOf(r: Recurrence): String =
        when (r) {
            is Recurrence.Weekly -> "WEEKLY"
            is Recurrence.Biweekly -> "BIWEEKLY"
            is Recurrence.SemiMonthly -> "SEMI_MONTHLY"
            is Recurrence.Monthly -> "MONTHLY"
            is Recurrence.Quarterly -> "QUARTERLY"
            is Recurrence.Yearly -> "YEARLY"
            is Recurrence.EveryNDays -> "IRREGULAR"
        }

    private fun touch(d: DebtEntity) =
        d.copy(version = d.version + 1, updatedAt = ctx.nowMillis(), updatedByDevice = ctx.deviceId, hlc = ctx.nextHlc())

    private companion object {
        const val MAX_DATE: String = "9999-12-31"
    }
}

private suspend fun LedgerService.balance(
    accountId: String,
    asOf: String,
): Money = balance(accountId, LocalDate.parse(asOf))
