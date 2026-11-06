package ph.financewallet.data.imports

import org.xmlpull.v1.XmlPullParser
import ph.financewallet.data.db.FinanceDatabase
import ph.financewallet.data.db.entity.AuditEventEntity
import ph.financewallet.data.db.entity.ImportBatchEntity
import ph.financewallet.data.db.entity.ImportProfileEntity
import ph.financewallet.data.db.entity.ImportRowEntity
import ph.financewallet.data.db.entity.TxnFingerprintEntity
import ph.financewallet.data.db.validatedWrite
import ph.financewallet.data.ledger.LedgerService
import ph.financewallet.data.ledger.TxnHeader
import ph.financewallet.data.ledger.WriteContext
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.ledger.Split
import ph.financewallet.domain.ledger.TxnStatus
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.importers.ColumnMapping
import ph.financewallet.importers.CsvReader
import ph.financewallet.importers.FileFormat
import ph.financewallet.importers.FileGuard
import ph.financewallet.importers.FileLimits
import ph.financewallet.importers.MappingSuggester
import ph.financewallet.importers.MappingSuggestion
import ph.financewallet.importers.NormalizedRow
import ph.financewallet.importers.Normalizer
import ph.financewallet.importers.PdfSupport
import ph.financewallet.importers.RowOutcome
import ph.financewallet.importers.XlsxReader
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Statement import, UC-26 (preview, no writes), UC-27 (commit) and UC-28 (rollback), docs/13-import-pipeline.md.
 * The only path from a file to the ledger is preview → user decisions → commit, and commit is one database
 * transaction: everything or nothing (NFR-10). Files never leave the device (IMP-07).
 */
class ImportService(
    private val db: FinanceDatabase,
    private val ctx: WriteContext,
    private val ledger: LedgerService,
    private val newXmlParser: () -> XmlPullParser,
    private val limits: FileLimits = FileLimits(),
) {
    private val dao get() = db.importDao()
    private val ledgerDao get() = db.ledgerDao()

    // ---- stages 1–4: read ---------------------------------------------------------------------------------------

    /** Guards and parses a file. Throws [ph.financewallet.importers.ImportFileException] for files that must be refused. */
    fun read(
        name: String,
        bytes: ByteArray,
        sheetIndex: Int? = null,
    ): ReadFile {
        val format = FileGuard.check(bytes, limits)
        val sha = FileGuard.sha256(bytes)
        return when (format) {
            FileFormat.PDF -> {
                PdfSupport.reject()
            }

            FileFormat.CSV -> {
                ReadFile(name, bytes, format, sha, CsvReader.read(bytes, limits))
            }

            FileFormat.XLSX -> {
                val reader = XlsxReader(newXmlParser, limits)
                ReadFile(name, bytes, format, sha, reader.read(bytes, sheetIndex), reader.sheets(bytes).map { it.name })
            }
        }
    }

    /**
     * A saved profile whose header signature matches the file exactly (detection fails closed, §3), otherwise the
     * generic suggestion. [datePattern] is the user's choice for ambiguous dates.
     */
    suspend fun suggest(
        file: ReadFile,
        datePattern: String? = null,
    ): Pair<String?, MappingSuggestion> {
        dao.profiles().filter { it.format == file.format.name }.forEach { p ->
            val (mapping, header) = MappingJson.decode(p.mappingJson) ?: return@forEach
            val fileHeader =
                file.table.rows
                    .getOrNull(mapping.headerRow)
                    ?.map(MappingSuggester::norm)
            if (fileHeader == header.map(MappingSuggester::norm)) {
                return p.id to MappingSuggestion(mapping, file.table.rows[mapping.headerRow], emptyList(), null)
            }
        }
        return null to MappingSuggester.suggest(file.table, datePattern)
    }

    /** Saves a confirmed mapping as a user profile (builtin = 0) so the next file of the same layout maps itself. */
    suspend fun saveProfile(
        name: String,
        file: ReadFile,
        mapping: ColumnMapping,
        institution: String? = null,
    ): DomainResult<String> =
        db.validatedWrite {
            ensure(name.trim().length in 1..MAX_NAME, "name", "NAME_LENGTH")
            val header = file.table.rows.getOrNull(mapping.headerRow) ?: fail("mapping", "HEADER_ROW_MISSING")
            val id = ctx.ids.newId()
            val now = ctx.nowMillis()
            dao.insertProfile(
                ImportProfileEntity(
                    id,
                    name.trim(),
                    institution,
                    file.format.name,
                    1,
                    false,
                    null,
                    MappingJson.encode(mapping, header),
                    now,
                    1,
                    now,
                    ctx.deviceId,
                    ctx.nextHlc(),
                ),
            )
            id
        }

    suspend fun profiles() = dao.profiles()

    // ---- stages 5–8: preview (UC-26, no writes) ------------------------------------------------------------------

    suspend fun preview(
        accountId: String,
        file: ReadFile,
        mapping: ColumnMapping,
        profileId: String? = null,
    ): ImportPreview {
        val account = checkNotNull(ledgerDao.ledgerAccount(accountId)) { "account $accountId" }
        val financial = checkNotNull(ledgerDao.financialAccount(accountId)) { "not a real account: $accountId" }
        val currency = CurrencyCode.of(checkNotNull(account.currency))
        val normalized = Normalizer.normalize(file.table, mapping, currency, accountId, ctx.today())
        val valid = normalized.valid
        val exact = existingFingerprints(accountId, valid.map { it.fingerprint })
        val probable = probableMatches(accountId, valid.filter { it.fingerprint !in exact })
        val suggestions = suggestions(accountId, valid.filter { it.fingerprint !in exact && it.rowNo !in probable })
        val rows =
            normalized.outcomes.map { o ->
                when (o) {
                    is RowOutcome.Rejected -> {
                        PreviewRow(o.rowNo, RowClass.REJECTED, null, o.error, o.cell)
                    }

                    is RowOutcome.Valid -> {
                        val r = o.row
                        when {
                            r.fingerprint in exact -> {
                                PreviewRow(r.rowNo, RowClass.EXACT_DUPLICATE, r, matchTxnId = exact[r.fingerprint])
                            }

                            r.rowNo in probable -> {
                                PreviewRow(r.rowNo, RowClass.PROBABLE_DUPLICATE, r, matchTxnId = probable[r.rowNo])
                            }

                            else -> {
                                suggestions.getValue(r.rowNo).let { (cat, src) ->
                                    PreviewRow(r.rowNo, RowClass.NEW, r, suggestedCategoryId = cat, suggestion = src)
                                }
                            }
                        }
                    }
                }
            }
        return ImportPreview(
            accountId = accountId,
            accountVersion = financial.version,
            file = file,
            mapping = mapping,
            profileId = profileId,
            rows = rows,
            balanceBreaks = normalized.balanceBreaks,
            alreadyImportedAt = dao.committedWithHash(accountId, file.sha256)?.committedAt,
            from = valid.minOfOrNull { it.txnDate },
            to = valid.maxOfOrNull { it.txnDate },
            totalIn = Money(valid.filter { it.amount.isPositive }.sumOf { it.amount.minor }, currency),
            totalOut = Money(-valid.filter { it.amount.isNegative }.sumOf { it.amount.minor }, currency),
            closingBalance = closingBalance(valid),
        )
    }

    private suspend fun existingFingerprints(
        accountId: String,
        fingerprints: List<String>,
    ): Map<String, String> =
        fingerprints.chunked(SQL_CHUNK).flatMap { dao.fingerprints(accountId, it) }.associate { it.fingerprint to it.txnId }

    /**
     * Probable duplicates (§7): same amount on the account, dates at most 3 days apart, and similar description (token
     * Jaccard ≥ 0.6) or the same reference number. Each existing transaction matches at most one row: closest date
     * first, then highest similarity, then file order.
     */
    private suspend fun probableMatches(
        accountId: String,
        rows: List<NormalizedRow>,
    ): Map<Int, String> {
        if (rows.isEmpty()) return emptyMap()
        val from = rows.minOf { it.txnDate }.minusDays(WINDOW_DAYS)
        val to = rows.maxOf { it.txnDate }.plusDays(WINDOW_DAYS)
        val candidates = dao.candidates(accountId, from.toString(), to.toString())

        data class Pairing(
            val row: NormalizedRow,
            val txnId: String,
            val days: Long,
            val similarityBp: Int,
        )
        val pairs =
            rows.flatMap { r ->
                candidates.mapNotNull { c ->
                    val days = kotlin.math.abs(ChronoUnit.DAYS.between(LocalDate.parse(c.txnDate), r.txnDate))
                    val sim = maxOf(jaccardBp(r.descriptionNorm, c.descriptionNorm), jaccardBp(r.descriptionNorm, c.merchantNorm.orEmpty()))
                    val sameRef = r.referenceNo != null && r.referenceNo == c.referenceNo
                    val similar = sim >= MIN_SIMILARITY_BP || sameRef
                    val sameMoney = c.amountMinor == r.amount.minor && days <= WINDOW_DAYS
                    if (sameMoney && similar) {
                        Pairing(r, c.txnId, days, sim)
                    } else {
                        null
                    }
                }
            }
        val usedRows = HashSet<Int>()
        val usedTxns = HashSet<String>()
        val result = HashMap<Int, String>()
        pairs.sortedWith(compareBy<Pairing>({ it.days }, { -it.similarityBp }, { it.row.rowNo })).forEach { p ->
            if (p.row.rowNo !in usedRows && p.txnId !in usedTxns) {
                usedRows += p.row.rowNo
                usedTxns += p.txnId
                result[p.row.rowNo] = p.txnId
            }
        }
        return result
    }

    /** Categorization (§8): rules by priority, then merchant history of the last 12 months, then none. */
    private suspend fun suggestions(
        accountId: String,
        rows: List<NormalizedRow>,
    ): Map<Int, Pair<String?, SuggestionSource>> {
        val rules = dao.rules()
        val classes = HashMap<String, String?>()

        suspend fun classOf(id: String) = classes.getOrPut(id) { ledgerDao.ledgerAccount(id)?.accountClass }
        rules.forEach { classOf(it.categoryId) }
        val since = ctx.today().minusMonths(HISTORY_MONTHS).toString()
        return rows.associate { r ->
            val cls = if (r.amount.isPositive) "INCOME" else "EXPENSE"
            val merchantNorm = r.merchant?.let(ph.financewallet.domain.text.TextNormalizer::normalize)
            val rule =
                CategoryRuleService.match(rules, {
                    classes[it]
                }, cls, accountId, r.descriptionNorm, merchantNorm, kotlin.math.abs(r.amount.minor))
            r.rowNo to
                when {
                    rule != null -> {
                        rule.categoryId to
                            if (rule.mode == RuleMode.AUTO.name) SuggestionSource.RULE_AUTO else SuggestionSource.RULE_SUGGEST
                    }

                    else -> {
                        dao.historyCategory(accountId, r.descriptionNorm, cls, since)?.let { it to SuggestionSource.HISTORY }
                            ?: (null to SuggestionSource.NONE)
                    }
                }
        }
    }

    private fun closingBalance(rows: List<NormalizedRow>): Money? {
        val withBalance = rows.filter { it.runningBalance != null }
        if (withBalance.isEmpty()) return null
        val last = withBalance.maxOf { it.txnDate }
        val onLast = withBalance.filter { it.txnDate == last }
        val oldestFirst = withBalance.first().txnDate <= withBalance.last().txnDate
        return (if (oldestFirst) onLast.last() else onLast.first()).runningBalance
    }

    // ---- stage 10: commit (UC-27) --------------------------------------------------------------------------------

    /**
     * Writes the batch exactly as previewed with [decisions] (rows without a decision use [ImportPreview.defaultDecisions]).
     * Fails with PREVIEW_STALE if the account changed since the preview; the UI then previews again.
     */
    suspend fun commit(
        preview: ImportPreview,
        decisions: Map<Int, RowDecision> = emptyMap(),
    ): DomainResult<CommitResult> =
        db.validatedWrite {
            val financial = ledgerDao.financialAccount(preview.accountId) ?: notFound("account", preview.accountId)
            ensure(financial.version == preview.accountVersion, "preview", "PREVIEW_STALE")
            val chosen = preview.defaultDecisions() + decisions
            val plans = preview.rows.map { plan(it, chosen[it.rowNo], preview.accountId) }
            val batchId = ctx.ids.newId()
            val now = ctx.nowMillis()
            val counts = plans.groupingBy { it.outcome }.eachCount()
            dao.insertBatch(
                ImportBatchEntity(
                    id = batchId,
                    accountId = preview.accountId,
                    profileId = preview.profileId,
                    fileName = preview.file.name.take(MAX_FILE_NAME),
                    fileSha256 = preview.file.sha256,
                    fileSizeBytes =
                        preview.file.bytes.size
                            .toLong(),
                    format = preview.file.format.name,
                    status = "COMMITTED",
                    statementStartDate = preview.from?.toString(),
                    statementEndDate = preview.to?.toString(),
                    statementClosingMinor = preview.closingBalance?.minor,
                    rowsTotal = plans.size,
                    rowsImported = counts[OUTCOME_IMPORTED] ?: 0,
                    rowsDuplicate = counts[OUTCOME_DUPLICATE] ?: 0,
                    rowsRejected = counts[OUTCOME_REJECTED] ?: 0,
                    rowsSkippedByUser = counts[OUTCOME_SKIPPED] ?: 0,
                    committedAt = now,
                    rolledBackAt = null,
                    version = 1,
                    updatedAt = now,
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                ),
            )
            val rows = plans.map { execute(it, preview.accountId, batchId) }
            dao.insertRows(rows)
            db.accountDao().updateFinancialAccount(
                financial.copy(
                    lastImportedAt = now,
                    version = financial.version + 1,
                    updatedAt = now,
                    updatedByDevice = ctx.deviceId,
                    hlc = ctx.nextHlc(),
                ),
            )
            audit("IMPORT_COMMITTED", batchId, "{\"imported\":\"${counts[OUTCOME_IMPORTED] ?: 0}\",\"rows\":\"${plans.size}\"}")
            CommitResult(
                batchId,
                counts[OUTCOME_IMPORTED] ?: 0,
                counts[OUTCOME_DUPLICATE] ?: 0,
                counts[OUTCOME_REJECTED] ?: 0,
                counts[OUTCOME_SKIPPED] ?: 0,
            )
        }

    /** What will happen to one row; fingerprints are re-checked here so a repeated commit can never import twice. */
    private class Plan(
        val preview: PreviewRow,
        val outcome: String,
        val decision: RowDecision?,
        val fingerprint: String?,
        val matchedTxnId: String? = null,
        val note: String? = null,
    )

    private suspend fun ValidationScope.plan(
        r: PreviewRow,
        decision: RowDecision?,
        accountId: String,
    ): Plan {
        val row = r.row ?: return Plan(r, OUTCOME_REJECTED, null, null, note = r.error?.name)
        val taken = existingFingerprints(accountId, listOf(row.fingerprint))[row.fingerprint]
        return when (decision) {
            null, RowDecision.Skip -> {
                if (taken !=
                    null
                ) {
                    Plan(r, OUTCOME_DUPLICATE, null, row.fingerprint, taken, NOTE_EXACT)
                } else {
                    Plan(r, OUTCOME_SKIPPED, null, row.fingerprint)
                }
            }

            is RowDecision.Link -> {
                ensure(taken == null, "decision", "ROW_ALREADY_IMPORTED")
                val txn = ledgerDao.txn(decision.txnId) ?: notFound("transaction", decision.txnId)
                ensure(txn.deletedAt == null, "decision", "TXN_DELETED")
                ensure(ledgerDao.entries(txn.id).any { it.ledgerAccountId == accountId }, "decision", "TXN_NOT_ON_ACCOUNT")
                Plan(r, OUTCOME_DUPLICATE, decision, row.fingerprint, txn.id, NOTE_LINKED)
            }

            is RowDecision.Import, is RowDecision.Transfer -> {
                // An exact duplicate is only imported when the user explicitly chose to ("import anyway"): it gets a new ordinal.
                val fp = if (taken != null) freeFingerprint(accountId, row.fingerprint) else row.fingerprint
                Plan(r, OUTCOME_IMPORTED, decision, fp)
            }
        }
    }

    private suspend fun freeFingerprint(
        accountId: String,
        base: String,
    ): String {
        var n = 1
        while (true) {
            val candidate = sha256("$base|again|$n")
            if (existingFingerprints(accountId, listOf(candidate)).isEmpty()) return candidate
            n++
        }
    }

    private suspend fun ValidationScope.execute(
        p: Plan,
        accountId: String,
        batchId: String,
    ): ImportRowEntity {
        val base = ImportRowEntity(batchId, p.preview.rowNo, p.outcome, null, p.matchedTxnId, p.fingerprint, p.note)
        val row = p.preview.row
        return when {
            p.outcome == OUTCOME_DUPLICATE && p.note == NOTE_LINKED -> {
                dao.insertFingerprints(listOf(TxnFingerprintEntity(accountId, checkNotNull(p.fingerprint), checkNotNull(p.matchedTxnId))))
                base
            }

            p.outcome == OUTCOME_IMPORTED && row != null -> {
                val txnId = record(row, checkNotNull(p.decision), accountId, batchId)
                dao.insertFingerprints(listOf(TxnFingerprintEntity(accountId, checkNotNull(p.fingerprint), txnId)))
                base.copy(txnId = txnId)
            }

            else -> {
                base
            }
        }
    }

    private suspend fun ValidationScope.record(
        row: NormalizedRow,
        decision: RowDecision,
        accountId: String,
        batchId: String,
    ): String {
        val header =
            TxnHeader(
                txnDate = row.txnDate,
                idempotencyKey = "import:$batchId:${row.rowNo}",
                status = if (row.pending) TxnStatus.PENDING else TxnStatus.POSTED,
                description = row.description.take(MAX_DESCRIPTION),
                merchant = row.merchant?.take(MAX_MERCHANT),
                referenceNo = row.referenceNo?.take(MAX_REFERENCE),
                source = "STATEMENT_IMPORT",
                importBatchId = batchId,
            )
        val magnitude = if (row.amount.isNegative) -row.amount else row.amount
        return ledger
            .record(header) { t, _ ->
                when (decision) {
                    is RowDecision.Transfer -> {
                        if (row.amount.isNegative) {
                            t.transfer(
                                accountId,
                                decision.counterAccountId,
                                magnitude,
                            )
                        } else {
                            t.transfer(decision.counterAccountId, accountId, magnitude)
                        }
                    }

                    is RowDecision.Import -> {
                        if (row.amount.isPositive) {
                            t.income(accountId, listOf(Split(decision.categoryId ?: UNCATEGORIZED_INCOME, magnitude)))
                        } else {
                            t.expense(accountId, listOf(Split(decision.categoryId ?: UNCATEGORIZED_EXPENSE, magnitude)))
                        }
                    }

                    else -> {
                        error("not an import decision: $decision")
                    }
                }
            }.bind()
    }

    // ---- rollback (UC-28) and history ----------------------------------------------------------------------------

    /** Transactions of the batch edited after import; the UI lists them and asks before rolling back (§11). */
    suspend fun editedAfterImport(batchId: String): List<String> = dao.editedAfterImport(batchId)

    /**
     * Soft-deletes every transaction of the batch (revision IMPORT_ROLLBACK) and removes its fingerprints, including links
     * to pre-existing transactions, so the file can be imported again. Balances return to their pre-import values.
     */
    suspend fun rollback(
        batchId: String,
        acknowledgeEdited: Boolean = false,
    ): DomainResult<Unit> =
        db.validatedWrite {
            val batch = dao.batch(batchId) ?: notFound("import batch", batchId)
            ensure(batch.status == "COMMITTED", "batchId", "ALREADY_ROLLED_BACK")
            ensure(acknowledgeEdited || dao.editedAfterImport(batchId).isEmpty(), "batchId", "EDITED_AFTER_IMPORT")
            dao.liveTxnsOf(batchId).forEach { ledger.deleteForImportRollback(it).bind() }
            val fingerprints =
                dao.rows(batchId).filter { it.outcome == OUTCOME_IMPORTED || it.errorCode == NOTE_LINKED }.mapNotNull { it.fingerprint }
            fingerprints.chunked(SQL_CHUNK).forEach { dao.deleteFingerprints(batch.accountId, it) }
            val now = ctx.nowMillis()
            dao.updateBatch(
                batch.copy(status = "ROLLED_BACK", rolledBackAt = now, version = batch.version + 1, updatedAt = now, hlc = ctx.nextHlc()),
            )
            audit("IMPORT_ROLLED_BACK", batchId, "{\"fingerprints\":\"${fingerprints.size}\"}")
        }

    suspend fun history(accountId: String? = null): List<BatchSummary> =
        dao.batches(accountId).map { b ->
            BatchSummary(
                id = b.id,
                accountId = b.accountId,
                fileName = b.fileName,
                format = FileFormat.valueOf(b.format),
                committedAt = b.committedAt,
                rolledBack = b.status == "ROLLED_BACK",
                imported = b.rowsImported,
                duplicates = b.rowsDuplicate,
                rejected = b.rowsRejected,
                skipped = b.rowsSkippedByUser,
                from = b.statementStartDate?.let(LocalDate::parse),
                to = b.statementEndDate?.let(LocalDate::parse),
            )
        }

    suspend fun rows(batchId: String) = dao.rows(batchId)

    private suspend fun audit(
        kind: String,
        batchId: String,
        detail: String,
    ) = db.accountDao().insertAudit(AuditEventEntity(ctx.ids.newId(), kind, "import_batch", batchId, detail, ctx.deviceId, ctx.nowMillis()))

    companion object {
        private const val SQL_CHUNK = 500
        private const val WINDOW_DAYS = 3L

        /** 0.6 token similarity (docs/13-import-pipeline.md §7), in basis points. */
        private const val MIN_SIMILARITY_BP = 6_000
        private const val FULL_BP = 10_000
        private const val HISTORY_MONTHS = 12L
        private const val MAX_NAME = 80
        private const val MAX_FILE_NAME = 200
        private const val MAX_DESCRIPTION = 200
        private const val MAX_MERCHANT = 120
        private const val MAX_REFERENCE = 64
        private const val OUTCOME_IMPORTED = "IMPORTED"
        private const val OUTCOME_DUPLICATE = "DUPLICATE"
        private const val OUTCOME_REJECTED = "REJECTED"
        private const val OUTCOME_SKIPPED = "SKIPPED"
        private const val NOTE_EXACT = "EXACT"
        private const val NOTE_LINKED = "LINKED"
        private const val UNCATEGORIZED_INCOME = "sys:income:uncategorized"
        private const val UNCATEGORIZED_EXPENSE = "sys:expense:uncategorized"

        /** Token Jaccard similarity of two normalized texts (§7) in basis points (integer arithmetic only). */
        fun jaccardBp(
            a: String,
            b: String,
        ): Int {
            val x = a.split(' ').filter { it.isNotEmpty() }.toSet()
            val y = b.split(' ').filter { it.isNotEmpty() }.toSet()
            if (x.isEmpty() || y.isEmpty()) return 0
            return (x intersect y).size * FULL_BP / (x union y).size
        }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
    }
}
