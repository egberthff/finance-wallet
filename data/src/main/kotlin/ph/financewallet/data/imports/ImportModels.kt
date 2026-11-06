package ph.financewallet.data.imports

import ph.financewallet.domain.money.Money
import ph.financewallet.importers.ColumnMapping
import ph.financewallet.importers.FileFormat
import ph.financewallet.importers.NormalizedRow
import ph.financewallet.importers.RowError
import ph.financewallet.importers.Table
import java.time.LocalDate

/** A statement file read into a table (stages 1–4); nothing is stored. */
data class ReadFile(
    val name: String,
    val bytes: ByteArray,
    val format: FileFormat,
    val sha256: String,
    val table: Table,
    /** Sheet names for XLSX (the user may choose another sheet). */
    val sheets: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = other is ReadFile && other.sha256 == sha256 && other.table == table

    override fun hashCode(): Int = sha256.hashCode()
}

/** Classification of a statement row (docs/13-import-pipeline.md §7). */
enum class RowClass { NEW, EXACT_DUPLICATE, PROBABLE_DUPLICATE, REJECTED }

/** Where a suggested category came from (§8), shown in the preview. */
enum class SuggestionSource { RULE_AUTO, RULE_SUGGEST, HISTORY, NONE }

data class PreviewRow(
    val rowNo: Int,
    val cls: RowClass,
    /** Null for rejected rows. */
    val row: NormalizedRow?,
    val error: RowError? = null,
    val badCell: String? = null,
    /** EXACT: the transaction that already carries this fingerprint. PROBABLE: the existing transaction it may duplicate. */
    val matchTxnId: String? = null,
    val suggestedCategoryId: String? = null,
    val suggestion: SuggestionSource = SuggestionSource.NONE,
)

/** What the user decided for one row before committing (§9). */
sealed interface RowDecision {
    /** Record a new income/expense transaction in [categoryId] (null = uncategorized). */
    data class Import(
        val categoryId: String? = null,
    ) : RowDecision

    /** Record the row as a transfer between this account and [counterAccountId] (e.g. a top-up from the bank). */
    data class Transfer(
        val counterAccountId: String,
    ) : RowDecision

    /** Mark the existing transaction as this row: no new transaction, the row's fingerprint is attached to it. */
    data class Link(
        val txnId: String,
    ) : RowDecision

    data object Skip : RowDecision
}

/**
 * The full preview of one file for one account (UC-26). It is the only input to commit: the user sees exactly what will
 * be written. [accountVersion] detects changes to the account between preview and commit.
 */
data class ImportPreview(
    val accountId: String,
    val accountVersion: Long,
    val file: ReadFile,
    val mapping: ColumnMapping,
    val profileId: String?,
    val rows: List<PreviewRow>,
    val balanceBreaks: List<Int>,
    /** When a committed batch for this account already has the same file hash. */
    val alreadyImportedAt: Long?,
    val from: LocalDate?,
    val to: LocalDate?,
    val totalIn: Money,
    val totalOut: Money,
    /** Running balance of the last row by date, if the file has a balance column (IMP-10 reconciliation hand-off). */
    val closingBalance: Money?,
) {
    fun count(cls: RowClass): Int = rows.count { it.cls == cls }

    /** Defaults (§7): new rows are imported with their suggestion, duplicates and probable duplicates are skipped. */
    fun defaultDecisions(): Map<Int, RowDecision> =
        rows.filter { it.cls != RowClass.REJECTED }.associate { r ->
            r.rowNo to if (r.cls == RowClass.NEW) RowDecision.Import(r.suggestedCategoryId) else RowDecision.Skip
        }
}

data class CommitResult(
    val batchId: String,
    val imported: Int,
    val duplicates: Int,
    val rejected: Int,
    val skipped: Int,
)

data class BatchSummary(
    val id: String,
    val accountId: String,
    val fileName: String,
    val format: FileFormat,
    val committedAt: Long,
    val rolledBack: Boolean,
    val imported: Int,
    val duplicates: Int,
    val rejected: Int,
    val skipped: Int,
    val from: LocalDate?,
    val to: LocalDate?,
)
