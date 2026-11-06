package ph.financewallet.app.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.financewallet.app.AppServices
import ph.financewallet.app.R
import ph.financewallet.app.ui.common.errorRes
import ph.financewallet.app.ui.transactions.Option
import ph.financewallet.data.imports.BatchSummary
import ph.financewallet.data.imports.CommitResult
import ph.financewallet.data.imports.ImportPreview
import ph.financewallet.data.imports.NewRule
import ph.financewallet.data.imports.ReadFile
import ph.financewallet.data.imports.RowDecision
import ph.financewallet.data.imports.RuleMode
import ph.financewallet.importers.AmountColumns
import ph.financewallet.importers.ColumnMapping
import ph.financewallet.importers.DatePatterns
import ph.financewallet.importers.FileError
import ph.financewallet.importers.ImportFileException
import ph.financewallet.importers.MappingSuggester
import ph.financewallet.importers.MappingSuggestion

enum class ImportStep { CHOOSE, MAP, PREVIEW, DONE }

enum class AmountMode { SIGNED, DEBIT_CREDIT }

/** The user's column choices while mapping (docs/13-import-pipeline.md §4.3). Indexes refer to the header row. */
data class MappingDraft(
    val headerRow: Int = 0,
    val date: Int? = null,
    val description: Int? = null,
    val mode: AmountMode = AmountMode.DEBIT_CREDIT,
    val amount: Int? = null,
    val debit: Int? = null,
    val credit: Int? = null,
    val balance: Int? = null,
    val datePattern: String? = null,
    val outflowNegative: Boolean = true,
) {
    fun toMapping(): ColumnMapping? {
        val d = date ?: return null
        val desc = description ?: return null
        val pattern = datePattern ?: return null
        val amountColumns =
            when (mode) {
                AmountMode.SIGNED -> amount?.let { AmountColumns.Signed(it, outflowNegative) }
                AmountMode.DEBIT_CREDIT -> if (debit != null && credit != null) AmountColumns.DebitCredit(debit, credit) else null
            } ?: return null
        return ColumnMapping(headerRow, d, desc, amountColumns, pattern, balance = balance)
    }

    companion object {
        fun of(
            m: ColumnMapping?,
            headerRow: Int,
            pattern: String?,
        ): MappingDraft {
            if (m == null) return MappingDraft(headerRow = headerRow, datePattern = pattern)
            val base = MappingDraft(m.headerRow, m.date, m.description, balance = m.balance, datePattern = m.datePattern)
            return when (val a = m.amount) {
                is AmountColumns.Signed -> base.copy(mode = AmountMode.SIGNED, amount = a.column, outflowNegative = a.outflowNegative)
                is AmountColumns.DebitCredit -> base.copy(mode = AmountMode.DEBIT_CREDIT, debit = a.debit, credit = a.credit)
                is AmountColumns.WithType -> base.copy(mode = AmountMode.SIGNED, amount = a.amount)
            }
        }
    }
}

data class ImportState(
    val step: ImportStep = ImportStep.CHOOSE,
    val accounts: List<Option> = emptyList(),
    val accountId: String? = null,
    val file: ReadFile? = null,
    val header: List<String> = emptyList(),
    val profileId: String? = null,
    val draft: MappingDraft = MappingDraft(),
    /** Date formats that fit every value of the chosen date column; more than one means the user must pick. */
    val dateCandidates: List<String> = emptyList(),
    val preview: ImportPreview? = null,
    val decisions: Map<Int, RowDecision> = emptyMap(),
    val categories: List<Option> = emptyList(),
    /** Income categories; the others are expense categories. Each preview row offers only the kind that fits its sign. */
    val incomeCategoryIds: Set<String> = emptySet(),
    val result: CommitResult? = null,
    val busy: Boolean = false,
    val error: Int? = null,
)

/**
 * Statement import flow (IMP-04): choose → map → preview → import. Nothing is written until [commit]; leaving the
 * screen discards the session (the preview lives only in this ViewModel).
 */
class ImportViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = mutable

    init {
        viewModelScope.launch { loadOptions() }
    }

    suspend fun loadOptions() {
        val accounts = s.accounts.accounts().map { Option(it.id, it.name) }
        val rows = s.categories.categories()
        val categories = rows.map { Option(it.id, it.name) }
        val income = rows.filter { it.accountClass == ph.financewallet.domain.ledger.AccountClass.INCOME.name }.map { it.id }.toSet()
        mutable.update {
            it.copy(
                accounts = accounts,
                accountId = it.accountId ?: accounts.firstOrNull()?.id,
                categories = categories,
                incomeCategoryIds = income,
            )
        }
    }

    fun chooseAccount(id: String) = mutable.update { it.copy(accountId = id) }

    /** Reads the picked file (stages 1–4). Refused files show a reason instead of a preview. */
    suspend fun load(
        name: String,
        bytes: ByteArray,
    ) {
        val file =
            try {
                s.imports.read(name, bytes)
            } catch (e: ImportFileException) {
                mutable.update { it.copy(error = fileErrorText(e.error)) }
                return
            }
        val (profileId, suggestion) = s.imports.suggest(file)
        mutable.update { it.copy(file = file, profileId = profileId, error = null) }
        applySuggestion(suggestion)
    }

    private fun applySuggestion(suggestion: MappingSuggestion) {
        // Only the date format is unknown (ambiguous day/month): keep the recognized columns, leave the format to the user.
        val onlyDateFormatMissing = suggestion.mapping == null && suggestion.missing == listOf("date_format")
        val table = mutable.value.file?.table
        val columns =
            if (onlyDateFormatMissing && table != null) {
                suggestion.dates
                    ?.candidates
                    ?.firstOrNull()
                    ?.let { MappingSuggester.suggest(table, it).mapping }
            } else {
                suggestion.mapping
            }
        val headerRow = columns?.headerRow ?: guessHeaderRow()
        val header = table?.rows?.getOrNull(headerRow).orEmpty()
        val candidates = suggestion.dates?.candidates ?: datesFor(headerRow, columns?.date)
        val draft = MappingDraft.of(columns, headerRow, null).copy(datePattern = suggestion.mapping?.datePattern)
        mutable.update { it.copy(step = ImportStep.MAP, header = header, draft = draft, dateCandidates = candidates) }
    }

    private fun guessHeaderRow(): Int =
        mutable.value.file
            ?.table
            ?.rows
            ?.indexOfFirst { r -> r.count { it.isNotBlank() } >= 2 }
            ?.coerceAtLeast(0) ?: 0

    private fun datesFor(
        headerRow: Int,
        column: Int?,
    ): List<String> {
        val rows =
            mutable.value.file
                ?.table
                ?.rows ?: return emptyList()
        val c = column ?: return emptyList()
        return DatePatterns.detect(rows.drop(headerRow + 1).take(DATE_SAMPLE).map { it.getOrElse(c) { "" } }).candidates
    }

    /** Edits the mapping; changing the date column recomputes the date formats that fit. */
    fun editDraft(change: (MappingDraft) -> MappingDraft) {
        mutable.update { st ->
            val next = change(st.draft)
            val candidates = if (next.date != st.draft.date) datesFor(next.headerRow, next.date) else st.dateCandidates
            val pattern = if (next.date != st.draft.date) candidates.singleOrNull() ?: unambiguous(next, candidates) else next.datePattern
            st.copy(draft = next.copy(datePattern = pattern), dateCandidates = candidates)
        }
    }

    private fun unambiguous(
        draft: MappingDraft,
        candidates: List<String>,
    ): String? {
        val rows =
            mutable.value.file
                ?.table
                ?.rows ?: return null
        val d = draft.date ?: return null
        val values = rows.drop(draft.headerRow + 1).take(DATE_SAMPLE).map { it.getOrElse(d) { "" } }
        return DatePatterns.detect(values).chosen?.takeIf { it in candidates }
    }

    /** Builds the preview (UC-26, no writes). [saveAsProfile] stores the mapping for next time. */
    suspend fun preview(saveAsProfile: String? = null): Int? {
        val st = mutable.value
        val file = st.file ?: return R.string.err_generic
        val account = st.accountId ?: return R.string.err_account_required
        val mapping = st.draft.toMapping() ?: return R.string.import_err_mapping_incomplete
        mutable.update { it.copy(busy = true) }
        var profileId = st.profileId
        if (!saveAsProfile.isNullOrBlank() && profileId == null) {
            when (val saved = s.imports.saveProfile(saveAsProfile, file, mapping)) {
                is ph.financewallet.domain.common.DomainResult.Ok -> {
                    profileId = saved.value
                }

                is ph.financewallet.domain.common.DomainResult.Err -> {
                    mutable.update { it.copy(busy = false) }
                    return ph.financewallet.app.ui.common
                        .errorText(saved.error)
                }
            }
        }
        val preview = s.imports.preview(account, file, mapping, profileId)
        mutable.update { it.copy(step = ImportStep.PREVIEW, preview = preview, decisions = preview.defaultDecisions(), busy = false) }
        return null
    }

    fun decide(
        rowNo: Int,
        decision: RowDecision,
    ) = mutable.update { it.copy(decisions = it.decisions + (rowNo to decision)) }

    /** UC-27. A stale preview (the account changed meanwhile) is rebuilt and shown again instead of committing. */
    suspend fun commit(): Int? {
        val st = mutable.value
        val preview = st.preview ?: return R.string.err_generic
        mutable.update { it.copy(busy = true) }
        val result = s.imports.commit(preview, st.decisions)
        val error = result.errorRes()
        if (error == null) {
            mutable.update {
                it.copy(
                    step = ImportStep.DONE,
                    result = (result as ph.financewallet.domain.common.DomainResult.Ok).value,
                    busy = false,
                )
            }
        } else {
            val code = (result as? ph.financewallet.domain.common.DomainResult.Err)?.error?.code
            if (code == "PREVIEW_STALE") preview()
            mutable.update { it.copy(busy = false) }
        }
        return error
    }

    /** IMP-10: compares the statement's closing balance with the account and optionally books the difference. */
    suspend fun reconcile(createAdjustment: Boolean): Int? {
        val st = mutable.value
        val preview = st.preview ?: return R.string.err_generic
        val closing = preview.closingBalance ?: return R.string.err_generic
        val asOf = preview.to ?: return R.string.err_generic
        return s.accounts.reconcile(preview.accountId, asOf, closing, createAdjustment, "import-recon:${st.result?.batchId}").errorRes()
    }

    fun restart() =
        mutable.update {
            ImportState(
                accounts = it.accounts,
                accountId = it.accountId,
                categories = it.categories,
                incomeCategoryIds = it.incomeCategoryIds,
            )
        }

    private companion object {
        const val DATE_SAMPLE = 200
    }
}

fun fileErrorText(e: FileError): Int =
    when (e) {
        FileError.FILE_EMPTY -> R.string.import_file_empty

        FileError.FILE_TOO_LARGE, FileError.TOO_MANY_ROWS -> R.string.import_file_too_large

        FileError.ZIP_TOO_MANY_ENTRIES,
        FileError.ZIP_TOO_LARGE,
        FileError.ZIP_RATIO,
        FileError.XML_DTD_FORBIDDEN,
        -> R.string.import_file_unsafe

        FileError.ZIP_INVALID, FileError.XML_INVALID, FileError.XLSX_NO_SHEETS, FileError.NO_HEADER_ROW -> R.string.import_file_unreadable

        FileError.XLS_UNSUPPORTED -> R.string.import_file_xls

        FileError.PDF_LAYOUT_UNSUPPORTED -> R.string.import_file_pdf

        FileError.CSV_UNTERMINATED_QUOTE -> R.string.import_file_csv_quote
    }

data class HistoryState(
    val loading: Boolean = true,
    val batches: List<BatchSummary> = emptyList(),
    val accountNames: Map<String, String> = emptyMap(),
)

/** Import history with whole-batch rollback (IMP-05, UC-28). */
class ImportHistoryViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = mutable

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    suspend fun load() =
        HistoryState(false, s.imports.history(), s.accounts.accounts(includeArchived = true).associate { it.id to it.name })

    suspend fun editedCount(batchId: String): Int = s.imports.editedAfterImport(batchId).size

    suspend fun rollback(
        batchId: String,
        acknowledgeEdited: Boolean,
    ): Int? =
        s.imports
            .rollback(batchId, acknowledgeEdited)
            .errorRes()
            .also { if (it == null) mutable.value = load() }
}

data class RulesState(
    val rules: List<ph.financewallet.data.db.entity.CategoryRuleEntity> = emptyList(),
    val categories: List<Option> = emptyList(),
)

/** Categorization rules (UC-15). They suggest categories during import and never change past transactions. */
class RulesViewModel(
    private val s: AppServices,
) : ViewModel() {
    private val mutable = MutableStateFlow(RulesState())
    val state: StateFlow<RulesState> = mutable

    fun refresh() {
        viewModelScope.launch { mutable.value = load() }
    }

    suspend fun load() = RulesState(s.rules.rules(), s.categories.categories().map { Option(it.id, it.name) })

    suspend fun create(
        pattern: String,
        categoryId: String?,
        auto: Boolean,
    ): Int? {
        val category = categoryId ?: return R.string.err_category_required
        return s.rules.create(NewRule(pattern, category, mode = if (auto) RuleMode.AUTO else RuleMode.SUGGEST)).errorRes().also {
            if (it ==
                null
            ) {
                mutable.value = load()
            }
        }
    }

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Int? =
        s.rules
            .setEnabled(id, enabled)
            .errorRes()
            .also { if (it == null) mutable.value = load() }

    suspend fun delete(id: String): Int? =
        s.rules
            .delete(id)
            .errorRes()
            .also { if (it == null) mutable.value = load() }
}
