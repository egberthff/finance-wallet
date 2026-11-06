package ph.financewallet.data.ledger

import androidx.room.withTransaction
import ph.financewallet.data.account.CategoryService
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.CategoryEntity
import ph.financewallet.data.db.entity.EntryEntity
import ph.financewallet.data.db.entity.FinancialAccountEntity
import ph.financewallet.data.db.entity.LedgerAccountEntity
import ph.financewallet.data.db.entity.TxnEntity
import ph.financewallet.data.db.entity.TxnRevisionEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.ledger.AccountClass
import ph.financewallet.domain.ledger.AccountDirectory
import ph.financewallet.domain.ledger.EntryTemplates
import ph.financewallet.domain.ledger.FinancialAccountType
import ph.financewallet.domain.ledger.LedgerAccount
import ph.financewallet.domain.ledger.LedgerCalculator
import ph.financewallet.domain.ledger.LedgerTransaction
import ph.financewallet.domain.ledger.PeriodSummary
import ph.financewallet.domain.ledger.RefundOriginal
import ph.financewallet.domain.ledger.SystemAccount
import ph.financewallet.domain.ledger.TransactionDraft
import ph.financewallet.domain.ledger.TxnStatus
import ph.financewallet.domain.ledger.TxnType
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.text.TextNormalizer
import java.time.LocalDate

/** User-entered header fields of a transaction (TXN-02). */
data class TxnHeader(
    val txnDate: LocalDate,
    val idempotencyKey: String,
    val status: TxnStatus = TxnStatus.POSTED,
    val description: String = "",
    val merchant: String? = null,
    val notes: String? = null,
    val referenceNo: String? = null,
    val source: String = "MANUAL",
    /** Set only by statement import (UC-27): the batch this transaction belongs to, for rollback (UC-28). */
    val importBatchId: String? = null,
)

data class NewAccount(
    val name: String,
    val type: FinancialAccountType,
    val currency: CurrencyCode,
    val openingDate: LocalDate,
    /** Assets: amount held. Liabilities: amount owed, entered as a positive number. Zero = no opening entry. */
    val openingAmount: Money,
    val institution: String? = null,
)

/**
 * Ledger use cases UC-01, UC-05..UC-10, UC-14 (docs/12-use-cases.md). Every public write runs in exactly one
 * database transaction: either everything (rows, entries, revision) is stored, or nothing (NFR-10, AC-CORE-9).
 */
class LedgerService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
    private val faults: FaultInjector = FaultInjector.NONE,
) {
    private val dao get() = db.ledgerDao()

    /** Creates the system ledger accounts once; safe to call on every start. Ids are fixed (`sys:<key>`) across devices. */
    suspend fun ensureSystemAccounts() =
        db.withTransaction {
            SystemAccount.entries.forEach { s ->
                if (dao.bySystemKey(s.key) == null) {
                    dao.insertLedgerAccount(ledgerRow(SYSTEM_ID_PREFIX + s.key, s.accountClass, s.key, null, s.key))
                }
            }
        }

    /** UC-01: account plus optional opening balance. Returns the account id. */
    suspend fun createAccount(req: NewAccount): DomainResult<String> =
        db.validatedWrite {
            val directory = snapshot()
            run {
                checkName(req.name)
                ensure(!req.openingAmount.isNegative, "openingAmount", "AMOUNT_NEGATIVE")
                ensure(req.openingAmount.currency == req.currency, "openingAmount", "CURRENCY_MISMATCH")
                val id = ctx.ids.newId()
                dao.insertLedgerAccount(ledgerRow(id, req.type.accountClass, req.name.trim(), req.currency.code, null))
                dao.insertFinancialAccount(financialRow(id, req))
                if (!req.openingAmount.isZero) {
                    val signed = if (req.type.accountClass == AccountClass.LIABILITY) -req.openingAmount else req.openingAmount
                    val withNew = directory.plus(LedgerAccount(id, req.type.accountClass, req.currency, req.type))
                    val draft = EntryTemplates(withNew).openingBalance(id, signed).bind()
                    insertTransaction(TxnHeader(req.openingDate, "opening:$id", source = "SYSTEM"), draft)
                }
                id
            }
        }

    /** UC-14 create; same rules as [CategoryService.create] (unique among siblings, one level). */
    suspend fun createCategory(
        name: String,
        accountClass: AccountClass,
        parentId: String? = null,
        icon: String = CategoryService.DEFAULT_ICON,
        colorArgb: Long = CategoryService.DEFAULT_COLOR,
    ): DomainResult<String> = CategoryService(db, ctx).create(name, accountClass, parentId, icon, colorArgb)

    /**
     * UC-05..UC-08: records a transaction built by [build]. Repeating the same [TxnHeader.idempotencyKey] returns the
     * existing id without writing anything (TXN-09).
     */
    suspend fun record(
        header: TxnHeader,
        build: suspend (EntryTemplates, LedgerReads) -> DomainResult<TransactionDraft>,
    ): DomainResult<String> =
        db.validatedWrite {
            dao.txnByIdempotencyKey(header.idempotencyKey)?.let { return@validatedWrite it.id }
            val directory = directory()
            run {
                checkHeader(header)
                val draft = build(EntryTemplates(directory), reads).bind()
                insertTransaction(header, draft)
            }
        }

    /** UC-09: replaces header and entries, writes revision UPDATE. [expectedVersion] guards against stale edits. */
    suspend fun edit(
        txnId: String,
        expectedVersion: Long,
        header: TxnHeader,
        reason: String? = null,
        build: suspend (EntryTemplates, LedgerReads) -> DomainResult<TransactionDraft>,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val directory = directory()
            run {
                checkHeader(header)
                val current = dao.txn(txnId) ?: notFound("transaction", txnId)
                ensure(current.deletedAt == null, "txnId", "TXN_DELETED")
                ensure(current.version == expectedVersion, "txnId", "STALE_VERSION")
                ensure(current.type != TxnType.OPENING_BALANCE.name || header.source == "SYSTEM", "txnId", "USE_ACCOUNT_EDIT")
                val draft = build(EntryTemplates(directory), reads).bind()
                ensure(draft.type.name == current.type, "type", "TYPE_CHANGE_NOT_ALLOWED")
                val before = dao.entries(txnId)
                val beforeShape = before.map { it.ledgerAccountId to it.amountMinor }
                val entriesChanged = beforeShape != draft.entries.map { it.accountId to it.amount.minor }
                if (entriesChanged) ensure(!hasDependents(txnId), "txnId", "TXN_HAS_DEPENDENTS")
                val updated = applyHeader(current, header).copy(version = current.version + 1)
                dao.updateTxn(updated)
                dao.deleteEntries(txnId)
                dao.insertEntries(entryRows(txnId, draft))
                writeRevision(txnId, "UPDATE", RevisionJson.of(current, before), RevisionJson.of(updated, dao.entries(txnId)), reason)
            }
        }

    /** UC-10: soft delete with revision. Blocked while a live reversal or refund depends on the transaction. */
    suspend fun delete(
        txnId: String,
        reason: String? = null,
    ): DomainResult<Unit> = softDelete(txnId, reason, "DELETE")

    /** Import rollback (UC-28): same checks as [delete], recorded as an IMPORT_ROLLBACK revision. */
    internal suspend fun deleteForImportRollback(txnId: String): DomainResult<Unit> = softDelete(txnId, null, "IMPORT_ROLLBACK")

    private suspend fun softDelete(
        txnId: String,
        reason: String?,
        revisionKind: String,
    ): DomainResult<Unit> =
        db.validatedWrite {
            run {
                val current = dao.txn(txnId) ?: notFound("transaction", txnId)
                ensure(current.deletedAt == null, "txnId", "TXN_DELETED")
                ensure(dao.liveReversalOf(txnId) == null, "txnId", "TXN_REVERSED_DELETE_REVERSAL_FIRST")
                ensure(dao.liveRefundCount(txnId) == 0, "txnId", "TXN_HAS_REFUNDS")
                val entries = dao.entries(txnId)
                val now = ctx.nowMillis()
                val deleted = current.copy(deletedAt = now, version = current.version + 1, updatedAt = now, hlc = ctx.nextHlc())
                dao.updateTxn(deleted)
                writeRevision(txnId, revisionKind, RevisionJson.of(current, entries), null, reason)
            }
        }

    // ---- reads --------------------------------------------------------------------------------------------------

    /** Read helpers available to [record]/[edit] builders (e.g. refund and reversal context). */
    val reads: LedgerReads =
        object : LedgerReads {
            override suspend fun transaction(id: String): LedgerTransaction? = loadTransaction(id)

            override suspend fun refundOriginal(
                originalId: String,
                excludingRefundId: String,
            ): RefundOriginal? {
                val original = loadTransaction(originalId) ?: return null
                val currency =
                    original.entries
                        .first()
                        .amount.currency
                return RefundOriginal(original, Money(dao.refundedAgainst(originalId, excludingRefundId), currency))
            }

            override suspend fun isReversed(id: String): Boolean = dao.liveReversalOf(id) != null
        }

    suspend fun balance(
        accountId: String,
        asOf: LocalDate,
    ): Money = Money(dao.balance(accountId, asOf.toString()), currencyOf(accountId))

    suspend fun pendingBalance(accountId: String): Money = Money(dao.pendingBalance(accountId), currencyOf(accountId))

    /** SQL implementation of [LedgerCalculator.summary]; tests cross-check the two. */
    suspend fun summary(
        from: LocalDate,
        to: LocalDate,
    ): Map<CurrencyCode, PeriodSummary> {
        val rows = dao.bucketTotals(from.toString(), to.toString())
        return rows
            .groupBy { it.currency }
            .toSortedMap()
            .map { (code, list) ->
                val c = CurrencyCode.of(code)

                fun total(bucket: String) = list.filter { it.bucket == bucket }.sumOf { it.total }
                c to
                    PeriodSummary(
                        currency = c,
                        income = Money(-total("INCOME"), c),
                        spending = Money(total("SPENDING"), c),
                        adjustments = Money(-total("ADJUSTMENT"), c),
                        openings = Money(-total("OPENING"), c),
                        fx = Money(-total("FX"), c),
                    )
            }.toMap()
    }

    suspend fun revisionCount(txnId: String): Int = dao.lastRevisionNo(txnId)

    /** A live transaction with its header and version, for an edit form (UC-09). Null if missing or deleted. */
    suspend fun editable(txnId: String): EditableTxn? {
        val t = dao.txn(txnId)?.takeIf { it.deletedAt == null } ?: return null
        val header =
            TxnHeader(
                txnDate = LocalDate.parse(t.txnDate),
                idempotencyKey = t.idempotencyKey,
                status = TxnStatus.valueOf(t.status),
                description = t.description,
                merchant = t.merchant,
                notes = t.notes,
                referenceNo = t.referenceNo,
                source = t.source,
            )
        return EditableTxn(checkNotNull(loadTransaction(txnId)), header, t.version)
    }

    suspend fun loadTransaction(id: String): LedgerTransaction? {
        val t = dao.txn(id) ?: return null
        return LedgerTransaction(
            id = t.id,
            type = TxnType.valueOf(t.type),
            status = TxnStatus.valueOf(t.status),
            txnDate = LocalDate.parse(t.txnDate),
            entries = dao.entries(id).map { it.toDomain() },
            deleted = t.deletedAt != null,
            reversesTxnId = t.reversesTxnId,
            refundOfTxnId = t.refundOfTxnId,
        )
    }

    /** Snapshot of all ledger accounts, used by entry templates inside one transaction. */
    suspend fun directory(): AccountDirectory = snapshot()

    internal suspend fun snapshot(): SnapshotDirectory {
        val types = dao.allFinancialAccounts().associate { it.ledgerAccountId to FinancialAccountType.valueOf(it.type) }
        val accounts =
            dao.allLedgerAccounts().map { row ->
                LedgerAccount(
                    id = row.id,
                    accountClass = AccountClass.valueOf(row.accountClass),
                    currency = row.currency?.let(CurrencyCode::of),
                    type = types[row.id],
                    systemAccount = row.systemKey?.let { key -> SystemAccount.entries.first { it.key == key } },
                    archived = row.archivedAt != null,
                )
            }
        return SnapshotDirectory(accounts)
    }

    // ---- write helpers ------------------------------------------------------------------------------------------

    private suspend fun ValidationScope.insertTransaction(
        header: TxnHeader,
        draft: TransactionDraft,
    ): String {
        val id = ctx.ids.newId()
        val now = ctx.nowMillis()
        val row =
            TxnEntity(
                id = id,
                type = draft.type.name,
                status = header.status.name,
                txnDate = header.txnDate.toString(),
                occurredAt = null,
                postingDate = null,
                description = header.description.trim(),
                descriptionNorm = TextNormalizer.normalize(header.description),
                merchant = header.merchant?.trim()?.ifEmpty { null },
                merchantNorm = header.merchant?.let(TextNormalizer::normalize)?.ifEmpty { null },
                notes = header.notes,
                referenceNo = header.referenceNo?.trim()?.ifEmpty { null },
                source = header.source,
                importBatchId = header.importBatchId,
                idempotencyKey = header.idempotencyKey,
                reversesTxnId = draft.reversesTxnId,
                refundOfTxnId = draft.refundOfTxnId,
                recurringOccurrenceId = null,
                fxRateDisplay = null,
                conflictState = "NONE",
                createdAt = now,
                deletedAt = null,
                version = 1,
                updatedAt = now,
                updatedByDevice = ctx.deviceId,
                hlc = ctx.nextHlc(),
            )
        dao.insertTxn(row)
        faults.at("after_txn_row")
        dao.insertEntries(entryRows(id, draft))
        faults.at("after_entries")
        writeRevision(id, "CREATE", null, RevisionJson.of(row, dao.entries(id)), null)
        return id
    }

    private suspend fun writeRevision(
        txnId: String,
        kind: String,
        beforeJson: String?,
        afterJson: String?,
        reason: String?,
    ) {
        val revisionNo = dao.lastRevisionNo(txnId) + 1
        dao.insertRevision(
            TxnRevisionEntity(ctx.ids.newId(), txnId, revisionNo, kind, beforeJson, afterJson, reason, ctx.deviceId, ctx.nowMillis()),
        )
    }

    private suspend fun hasDependents(txnId: String): Boolean = dao.liveReversalOf(txnId) != null || dao.liveRefundCount(txnId) > 0

    private fun applyHeader(
        current: TxnEntity,
        header: TxnHeader,
    ): TxnEntity =
        current.copy(
            status = header.status.name,
            txnDate = header.txnDate.toString(),
            description = header.description.trim(),
            descriptionNorm = TextNormalizer.normalize(header.description),
            merchant = header.merchant?.trim()?.ifEmpty { null },
            merchantNorm = header.merchant?.let(TextNormalizer::normalize)?.ifEmpty { null },
            notes = header.notes,
            referenceNo = header.referenceNo?.trim()?.ifEmpty { null },
            updatedAt = ctx.nowMillis(),
            updatedByDevice = ctx.deviceId,
            hlc = ctx.nextHlc(),
        )

    private fun entryRows(
        txnId: String,
        draft: TransactionDraft,
    ) = draft.entries.mapIndexed {
        i,
        e,
        ->
        EntryEntity(ctx.ids.newId(), txnId, i, e.accountId, e.amount.minor, e.amount.currency.code, null)
    }

    private fun ledgerRow(
        id: String,
        cls: AccountClass,
        name: String,
        currency: String?,
        systemKey: String?,
    ) = LedgerAccountEntity(id, cls.name, name, currency, systemKey, null, ctx.nowMillis(), 1, ctx.nowMillis(), ctx.deviceId, ctx.nextHlc())

    private fun financialRow(
        id: String,
        req: NewAccount,
    ) = FinancialAccountEntity(
        ledgerAccountId = id,
        type = req.type.name,
        institution = req.institution?.trim()?.ifEmpty { null },
        accountNumberLast4 = null,
        openingDate = req.openingDate.toString(),
        includeInNetWorth = true,
        spendable = req.type.accountClass == AccountClass.ASSET,
        creditLimitMinor = null,
        defaultImportProfileId = null,
        lastImportedAt = null,
        reconcileReminderDays = null,
        sortOrder = 0,
        version = 1,
        updatedAt = ctx.nowMillis(),
        updatedByDevice = ctx.deviceId,
        hlc = ctx.nextHlc(),
    )

    private suspend fun currencyOf(accountId: String): CurrencyCode =
        CurrencyCode.of(requireNotNull(dao.ledgerAccount(accountId)?.currency) { "$accountId is not a real account" })

    private fun ValidationScope.checkName(name: String) = ensure(name.trim().length in 1..MAX_NAME, "name", "NAME_LENGTH")

    private fun ValidationScope.checkHeader(h: TxnHeader) {
        ensure(h.idempotencyKey.isNotBlank(), "idempotencyKey", "IDEMPOTENCY_KEY_REQUIRED")
        ensure(h.description.length <= MAX_DESCRIPTION, "description", "TOO_LONG")
        ensure((h.merchant?.length ?: 0) <= MAX_MERCHANT, "merchant", "TOO_LONG")
        ensure((h.notes?.length ?: 0) <= MAX_NOTES, "notes", "TOO_LONG")
        ensure((h.referenceNo?.length ?: 0) <= MAX_REFERENCE, "referenceNo", "TOO_LONG")
    }

    private companion object {
        const val SYSTEM_ID_PREFIX = "sys:"
        const val MAX_NAME = 80
        const val MAX_DESCRIPTION = 200
        const val MAX_MERCHANT = 120
        const val MAX_NOTES = 2000
        const val MAX_REFERENCE = 64
    }
}

data class EditableTxn(
    val transaction: LedgerTransaction,
    val header: TxnHeader,
    val version: Long,
)

/** Read access offered to transaction builders. */
interface LedgerReads {
    suspend fun transaction(id: String): LedgerTransaction?

    /** Original expense plus the total already refunded against it, excluding [excludingRefundId] (for edits). */
    suspend fun refundOriginal(
        originalId: String,
        excludingRefundId: String = "",
    ): RefundOriginal?

    suspend fun isReversed(id: String): Boolean
}

internal class SnapshotDirectory(
    accounts: List<LedgerAccount>,
) : AccountDirectory {
    private val byId = accounts.associateBy { it.id }
    private val bySystem = accounts.mapNotNull { a -> a.systemAccount?.let { it to a } }.toMap()

    override fun find(id: String): LedgerAccount? = byId[id]

    override fun system(account: SystemAccount): LedgerAccount =
        bySystem[account] ?: error("system account ${account.key} missing: call ensureSystemAccounts()")

    fun plus(account: LedgerAccount) = SnapshotDirectory(byId.values + account)
}

private fun EntryEntity.toDomain() =
    ph.financewallet.domain.ledger
        .Entry(ledgerAccountId, Money(amountMinor, CurrencyCode.of(currency)))
