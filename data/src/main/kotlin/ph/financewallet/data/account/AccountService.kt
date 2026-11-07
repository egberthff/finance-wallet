package ph.financewallet.data.account

import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.ReconciliationEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import java.time.LocalDate

/** Editable account fields (UC-02). Amounts are user-facing: liabilities are entered as the positive amount owed. */
data class AccountEdit(
    val name: String,
    val institution: String? = null,
    val accountNumberLast4: String? = null,
    val includeInNetWorth: Boolean = true,
    val spendable: Boolean = true,
    val creditLimit: Money? = null,
    val reconcileReminderDays: Int? = null,
    /** Only changeable while the account has no transactions besides its opening balance. */
    val currency: CurrencyCode,
    val openingDate: LocalDate,
    val openingAmount: Money,
    /**
     * New account type, or null to keep it. Within the same class (held money: cash, bank, e-wallet, savings, other;
     * owed money: credit card, loan, borrowed) it can always change, except away from savings once savings transfers
     * exist. A change of class is allowed only while the account has nothing but its opening balance and no links.
     */
    val type: FinancialAccountType? = null,
)

/** An account's editable values with the version to send back, and whether its currency is locked (UC-02). */
data class AccountEditState(
    val edit: AccountEdit,
    val version: Long,
    val currencyLocked: Boolean,
)

data class ReconcileResult(
    val reconciliationId: String,
    /** Ledger balance as of the date, before any adjustment (signed ledger convention). */
    val calculated: Money,
    /** statement − calculated (signed ledger convention). */
    val difference: Money,
    val adjustmentTxnId: String?,
)

/** Latest reconciliation, shown so a reconciled balance is never confused with a merely calculated one (ACC-05/06). */
data class ReconciledBalance(
    val asOf: LocalDate,
    val statementBalance: Money,
    val adjusted: Boolean,
)

/**
 * Account management UC-02 (edit), UC-03 (archive/unarchive), UC-04 (delete), UC-13 (reconcile)
 * (docs/12-use-cases.md). Each change writes an `audit_event`. History is never destroyed: an account that has
 * any transaction can only be archived, because transaction revisions are append-only (TXN-06) and reference it.
 */
class AccountService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
    private val ledger: LedgerService,
) {
    private val dao get() = db.accountDao()
    private val ledgerDao get() = db.ledgerDao()

    suspend fun accounts(includeArchived: Boolean = false): List<AccountRow> = dao.accounts(includeArchived)

    /**
     * The current values of an account as an [AccountEdit] (for the edit form, UC-02), its version for the stale-edit
     * check, and whether the currency can still change. Null if the account does not exist.
     */
    suspend fun editState(accountId: String): AccountEditState? {
        val account = ledgerDao.ledgerAccount(accountId) ?: return null
        val financial = ledgerDao.financialAccount(accountId) ?: return null
        val currency = CurrencyCode.of(checkNotNull(account.currency))
        val opening = ledgerDao.liveOpeningOf(accountId)
        val signed = opening?.let { o -> ledgerDao.entries(o.id).firstOrNull { it.ledgerAccountId == accountId }?.amountMinor } ?: 0L
        val amount = if (account.accountClass == AccountClass.LIABILITY.name) -signed else signed
        return AccountEditState(
            AccountEdit(
                name = account.name,
                institution = financial.institution,
                accountNumberLast4 = financial.accountNumberLast4,
                includeInNetWorth = financial.includeInNetWorth,
                spendable = financial.spendable,
                creditLimit = financial.creditLimitMinor?.let { Money(it, currency) },
                reconcileReminderDays = financial.reconcileReminderDays,
                currency = currency,
                openingDate = LocalDate.parse(opening?.txnDate ?: financial.openingDate),
                openingAmount = Money(amount, currency),
                type = FinancialAccountType.valueOf(financial.type),
            ),
            version = financial.version,
            currencyLocked = dao.nonOpeningEntryCount(accountId) > 0,
        )
    }

    /** UC-02. The opening balance is edited through its own SYSTEM transaction, so it keeps a revision history. */
    suspend fun edit(
        accountId: String,
        expectedVersion: Long,
        edit: AccountEdit,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val (account, financial) = load(accountId)
            ensure(financial.version == expectedVersion, "accountId", "STALE_VERSION")
            ensure(edit.name.trim().length in 1..MAX_NAME, "name", "NAME_LENGTH")
            ensure((edit.institution?.length ?: 0) <= MAX_INSTITUTION, "institution", "TOO_LONG")
            ensure(edit.accountNumberLast4 == null || edit.accountNumberLast4.matches(LAST4), "accountNumberLast4", "LAST4_FORMAT")
            ensure(edit.reconcileReminderDays == null || edit.reconcileReminderDays > 0, "reconcileReminderDays", "AMOUNT_NOT_POSITIVE")
            ensure(edit.openingAmount.currency == edit.currency, "openingAmount", "CURRENCY_MISMATCH")
            ensure(!edit.openingAmount.isNegative, "openingAmount", "AMOUNT_NEGATIVE")
            edit.creditLimit?.let { ensure(it.currency == edit.currency && !it.isNegative, "creditLimit", "AMOUNT_NEGATIVE") }
            val currencyChanged = edit.currency.code != account.currency
            if (currencyChanged) ensure(dao.nonOpeningEntryCount(accountId) == 0, "currency", "CURRENCY_LOCKED")
            val newType = edit.type ?: FinancialAccountType.valueOf(financial.type)
            val newClass = checkTypeChange(accountId, account.accountClass, financial.type, newType)

            val now = ctx.nowMillis()
            dao.updateLedgerAccount(
                account.copy(
                    name = edit.name.trim(),
                    currency = edit.currency.code,
                    accountClass = newClass.name,
                    version = account.version + 1,
                    updatedAt = now,
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                ),
            )
            dao.updateFinancialAccount(
                financial.copy(
                    type = newType.name,
                    institution = edit.institution?.trim()?.ifEmpty { null },
                    accountNumberLast4 = edit.accountNumberLast4,
                    openingDate = edit.openingDate.toString(),
                    includeInNetWorth = edit.includeInNetWorth,
                    spendable = edit.spendable && newClass == AccountClass.ASSET,
                    creditLimitMinor = edit.creditLimit?.minor,
                    reconcileReminderDays = edit.reconcileReminderDays,
                    version = financial.version + 1,
                    updatedAt = now,
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                ),
            )
            // The opening amount is kept as entered; its sign follows the (possibly new) class.
            updateOpening(accountId, newClass, edit)
            audit(
                "ACCOUNT_EDIT",
                accountId,
                mapOf(
                    "name" to edit.name.trim(),
                    "currencyChanged" to currencyChanged.toString(),
                    "type" to "${financial.type}->${newType.name}",
                ),
            )
        }

    /**
     * UC-03. Archiving hides the account from entry screens and rejects new transactions on it; history stays.
     * A non-zero balance needs [acknowledgeNonZeroBalance] (the UI asks first).
     */
    suspend fun archive(
        accountId: String,
        acknowledgeNonZeroBalance: Boolean = false,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val (account, _) = load(accountId)
            ensure(account.archivedAt == null, "accountId", "ALREADY_ARCHIVED")
            val balance = ledgerDao.balance(accountId, MAX_DATE) + ledgerDao.pendingBalance(accountId)
            ensure(balance == 0L || acknowledgeNonZeroBalance, "accountId", "BALANCE_NOT_ZERO")
            dao.updateLedgerAccount(
                account.copy(archivedAt = ctx.nowMillis(), version = account.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()),
            )
            audit("ACCOUNT_ARCHIVE", accountId, mapOf("balanceMinor" to balance.toString()))
        }

    suspend fun unarchive(accountId: String): DomainResult<Unit> =
        db.validatedWrite {
            val (account, _) = load(accountId)
            ensure(account.archivedAt != null, "accountId", "NOT_ARCHIVED")
            dao.updateLedgerAccount(
                account.copy(archivedAt = null, version = account.version + 1, updatedAt = ctx.nowMillis(), hlc = ctx.nextHlc()),
            )
            audit("ACCOUNT_UNARCHIVE", accountId, emptyMap())
        }

    /**
     * UC-04: permanent delete, only for an account that never had a transaction (including an opening balance) and is
     * not linked to a budget, goal or reconciliation. Anything else returns ACCOUNT_HAS_HISTORY; the UI offers archive.
     */
    suspend fun delete(accountId: String): DomainResult<Unit> =
        db.validatedWrite {
            val (account, _) = load(accountId)
            ensure(dao.entryCount(accountId) == 0, "accountId", "ACCOUNT_HAS_HISTORY")
            ensure(dao.accountReferences(accountId) == 0, "accountId", "ACCOUNT_HAS_LINKS")
            dao.deleteFinancialAccount(accountId)
            dao.deleteLedgerAccount(accountId)
            audit("ACCOUNT_DELETE", accountId, mapOf("name" to account.name))
        }

    /**
     * UC-13. [statementBalance] is what the bank or e-wallet shows on [asOf] (liabilities: the positive amount owed).
     * With [createAdjustment], a non-zero difference is booked as a BALANCE_ADJUSTMENT on [asOf] (never spending or
     * income), linked to the reconciliation. Repeating [idempotencyKey] returns the first result.
     */
    suspend fun reconcile(
        accountId: String,
        asOf: LocalDate,
        statementBalance: Money,
        createAdjustment: Boolean,
        idempotencyKey: String,
    ): DomainResult<ReconcileResult> =
        db.validatedWrite {
            val (account, _) = load(accountId)
            ensure(account.archivedAt == null, "accountId", "ACCOUNT_ARCHIVED")
            ensure(!asOf.isAfter(ctx.today()), "asOf", "DATE_IN_FUTURE")
            val c = CurrencyCode.of(checkNotNull(account.currency))
            ensure(statementBalance.currency == c, "statementBalance", "CURRENCY_MISMATCH")
            val signedStatement = if (account.accountClass == AccountClass.LIABILITY.name) -statementBalance else statementBalance
            val adjustmentKey = "$idempotencyKey:adjustment"
            val calculated = Money(ledgerDao.balance(accountId, asOf.toString()), c)
            ledgerDao.txnByIdempotencyKey(adjustmentKey)?.let { existing ->
                val row = dao.reconciliations(accountId).first { it.adjustmentTxnId == existing.id }
                return@validatedWrite ReconcileResult(
                    row.id,
                    Money(row.calculatedBalanceMinor, c),
                    Money(row.differenceMinor, c),
                    existing.id,
                )
            }
            val difference = signedStatement - calculated
            val adjustmentId =
                if (createAdjustment && !difference.isZero) {
                    ledger
                        .record(TxnHeader(asOf, adjustmentKey, description = "Reconciliation adjustment", source = "SYSTEM")) { t, _ ->
                            t.balanceAdjustment(accountId, difference)
                        }.bind()
                } else {
                    null
                }
            val id = ctx.ids.newId()
            val now = ctx.nowMillis()
            dao.insertReconciliation(
                ReconciliationEntity(
                    id = id,
                    accountId = accountId,
                    asOfDate = asOf.toString(),
                    statementBalanceMinor = signedStatement.minor,
                    calculatedBalanceMinor = calculated.minor,
                    differenceMinor = difference.minor,
                    adjustmentTxnId = adjustmentId,
                    source = "MANUAL",
                    importBatchId = null,
                    createdAt = now,
                    version = 1,
                    updatedAt = now,
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                ),
            )
            audit("ACCOUNT_RECONCILE", accountId, mapOf("asOf" to asOf.toString(), "differenceMinor" to difference.minor.toString()))
            ReconcileResult(id, calculated, difference, adjustmentId)
        }

    suspend fun reconciledBalance(accountId: String): ReconciledBalance? {
        val r = dao.latestReconciliation(accountId) ?: return null
        val account = checkNotNull(ledgerDao.ledgerAccount(accountId))
        val c = CurrencyCode.of(checkNotNull(account.currency))
        val userFacing = if (account.accountClass == AccountClass.LIABILITY.name) -r.statementBalanceMinor else r.statementBalanceMinor
        return ReconciledBalance(LocalDate.parse(r.asOfDate), Money(userFacing, c), r.adjustmentTxnId != null)
    }

    // ---- internals ----------------------------------------------------------------------------------------------

    /**
     * Checks a change of account type and returns the account's class afterwards. Same class: allowed, except leaving
     * SAVINGS while savings transfers use the account (`SAVINGS_TYPE_LOCKED`; those transfers require a savings account).
     * New class: allowed only while the account has no transactions besides its opening balance, no budget, goal or
     * reconciliation links and no debt record (`TYPE_CLASS_LOCKED`), because the class decides what every entry means.
     */
    private suspend fun ValidationScope.checkTypeChange(
        accountId: String,
        currentClass: String,
        currentType: String,
        newType: FinancialAccountType,
    ): AccountClass {
        if (newType.name == currentType) return AccountClass.valueOf(currentClass)
        if (currentType ==
            FinancialAccountType.SAVINGS.name
        ) {
            ensure(dao.savingsTransferCount(accountId) == 0, "type", "SAVINGS_TYPE_LOCKED")
        }
        if (newType.accountClass.name != currentClass) {
            val clean = dao.nonOpeningEntryCount(accountId) == 0 && dao.accountReferences(accountId) == 0 && dao.debtCount(accountId) == 0
            ensure(clean, "type", "TYPE_CLASS_LOCKED")
        }
        return newType.accountClass
    }

    private suspend fun ValidationScope.load(accountId: String) =
        (ledgerDao.ledgerAccount(accountId) ?: notFound("account", accountId)) to
            (ledgerDao.financialAccount(accountId) ?: notFound("account", accountId))

    /** Brings the opening-balance transaction in line with [edit]: create, edit, or delete it (all with revisions). */
    private suspend fun ValidationScope.updateOpening(
        accountId: String,
        cls: AccountClass,
        edit: AccountEdit,
    ) {
        val signed = if (cls == AccountClass.LIABILITY) -edit.openingAmount else edit.openingAmount
        val key = "opening:$accountId"
        // Found by account, not by key: a re-created opening has a different key (see below), and looking it up by
        // the original key would miss it and record a second opening balance.
        val existing = ledgerDao.liveOpeningOf(accountId)
        val header = TxnHeader(edit.openingDate, key, source = "SYSTEM")
        when {
            existing == null && !signed.isZero -> {
                // A deleted opening keeps its key, so a re-created one gets a fresh key.
                val fresh =
                    if (ledgerDao.txnByIdempotencyKey(key) ==
                        null
                    ) {
                        header
                    } else {
                        header.copy(idempotencyKey = "$key:${ctx.ids.newId()}")
                    }
                ledger.record(fresh) { t, _ -> t.openingBalance(accountId, signed) }.bind()
            }

            existing != null && signed.isZero -> {
                ledger.delete(existing.id, "opening balance set to zero").bind()
            }

            existing != null -> {
                val unchanged =
                    existing.txnDate == edit.openingDate.toString() &&
                        ledgerDao.entries(existing.id).any {
                            it.ledgerAccountId == accountId && it.amountMinor == signed.minor && it.currency == signed.currency.code
                        }
                if (!unchanged) {
                    ledger
                        .edit(existing.id, existing.version, header.copy(idempotencyKey = existing.idempotencyKey)) { t, _ ->
                            t.openingBalance(accountId, signed)
                        }.bind()
                }
            }

            else -> {} // no opening balance before or after
        }
    }

    private suspend fun audit(
        kind: String,
        entityId: String,
        detail: Map<String, String>,
    ) = dao.audit(ctx, kind, "account", entityId, detail)

    private companion object {
        const val MAX_NAME = 80
        const val MAX_INSTITUTION = 80
        const val MAX_DATE = "9999-12-31"
        val LAST4 = Regex("[0-9]{4}")
    }
}
