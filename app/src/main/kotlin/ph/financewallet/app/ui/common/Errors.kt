package ph.financewallet.app.ui.common

import androidx.annotation.StringRes
import ph.financewallet.app.R
import ph.financewallet.domain.common.DomainError
import ph.financewallet.domain.common.DomainResult
import ph.financewallet.domain.money.AmountError
import ph.financewallet.domain.money.AmountParseResult
import ph.financewallet.domain.money.CurrencyCode
import ph.financewallet.domain.money.Money
import ph.financewallet.domain.money.MoneyParser

/** Parses an amount field; returns the money or a string resource for the error. */
fun parseAmount(
    text: String,
    currency: CurrencyCode = CurrencyCode.PHP,
): Pair<Money?, Int?> =
    when (val r = MoneyParser.parse(text, currency)) {
        is AmountParseResult.Ok -> r.money to null
        is AmountParseResult.Invalid -> null to amountErrorText(r.error)
    }

@StringRes
fun amountErrorText(e: AmountError): Int =
    when (e) {
        AmountError.EMPTY -> R.string.err_amount_empty
        AmountError.INVALID_FORMAT -> R.string.err_amount_format
        AmountError.NEGATIVE_NOT_ALLOWED -> R.string.err_amount_negative
        AmountError.TOO_MANY_DECIMALS -> R.string.err_amount_decimals
        AmountError.TOO_LARGE -> R.string.err_amount_too_large
    }

/** User-facing message for a domain error code (codes are never shown raw, domain §11). */
@StringRes
fun errorText(error: DomainError): Int = ERROR_TEXT[error.code] ?: R.string.err_generic

/** Message resource for a failed result, or null on success. */
fun DomainResult<*>.errorRes(): Int? = (this as? DomainResult.Err)?.error?.let(::errorText)

private val ERROR_TEXT: Map<String, Int> =
    mapOf(
        "NAME_LENGTH" to R.string.err_name_length,
        "AMOUNT_NOT_POSITIVE" to R.string.err_amount_positive,
        "AMOUNT_ZERO" to R.string.err_amount_positive,
        "AMOUNT_NEGATIVE" to R.string.err_amount_negative,
        "CURRENCY_MISMATCH" to R.string.err_currency_mismatch,
        "SAME_ACCOUNT" to R.string.err_same_account,
        "ACCOUNT_ARCHIVED" to R.string.err_account_archived,
        "CATEGORY_ARCHIVED" to R.string.err_category_archived,
        "CATEGORY_WRONG_CLASS" to R.string.err_category_wrong_class,
        "CATEGORY_NAME_TAKEN" to R.string.err_category_name_taken,
        "CATEGORY_TOO_DEEP" to R.string.err_category_too_deep,
        "CATEGORY_HAS_CHILDREN" to R.string.err_category_has_children,
        "BALANCE_NOT_ZERO" to R.string.err_balance_not_zero,
        "ACCOUNT_HAS_HISTORY" to R.string.err_account_has_history,
        "ACCOUNT_HAS_LINKS" to R.string.err_account_has_links,
        "DATE_IN_FUTURE" to R.string.err_date_future,
        "STALE_VERSION" to R.string.err_stale,
        "TXN_HAS_REFUNDS" to R.string.err_txn_has_dependents,
        "TXN_HAS_DEPENDENTS" to R.string.err_txn_has_dependents,
        "TXN_REVERSED_DELETE_REVERSAL_FIRST" to R.string.err_txn_has_dependents,
        "BUDGET_CATEGORIES_EMPTY" to R.string.err_budget_categories_empty,
        "BUDGET_CATEGORY_OVERLAP" to R.string.err_budget_overlap,
        "PERIOD_CLOSED" to R.string.err_period_closed,
        "ALLOCATION_INSUFFICIENT" to R.string.err_allocation_insufficient,
        "CONFIRMATION_REQUIRED" to R.string.err_confirmation_required,
        "GOAL_EXCEEDS_LINKED_BALANCE" to R.string.err_goal_cap,
        "GOAL_NOT_ACTIVE" to R.string.err_goal_not_active,
        "FUND_REQUIRES_MANUAL_MODE" to R.string.err_fund_manual_only,
        "NO_PERIOD_FOR_DATE" to R.string.err_no_period,
        "SPLITS_EMPTY" to R.string.err_category_required,
        "NOT_FOUND" to R.string.err_not_found,
    )
