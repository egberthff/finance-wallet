package ph.financewallet.data.db

import androidx.room.withTransaction
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.common.ValidationScope
import ph.financewallet.domain.common.validating

/** Carries a validation failure out of the Room transaction so that the transaction rolls back. */
private class RollbackSignal(
    val result: DomainResult.Err,
) : RuntimeException(result.error.code, null, false, false)

/**
 * Runs [block] as one database transaction with validation (docs/12-use-cases.md "single-transaction writes").
 *
 * If any check inside [block] fails, everything written so far is **rolled back** and the failure is returned as
 * [DomainResult.Err]. Wrapping `validating { }` directly in `withTransaction { }` would instead commit the rows written
 * before the failing check, because `validating` turns the failure into a normal return value.
 *
 * Nested use (one validated write calling another) is safe: an inner failure that the outer block binds rolls back
 * the outer transaction too, since Room nests transactions in one SQLite transaction.
 */
suspend fun <T> FinanceDatabase.validatedWrite(block: suspend ValidationScope.() -> T): DomainResult<T> =
    try {
        withTransaction {
            when (val result = validating { block() }) {
                is DomainResult.Ok -> result
                is DomainResult.Err -> throw RollbackSignal(result)
            }
        }
    } catch (signal: RollbackSignal) {
        signal.result
    }
