package ph.financewallet.domain.common

/**
 * Expected business failures (docs/10-domain-model.md §11). Codes are stable identifiers that the UI maps to
 * string resources; they are never shown raw to users.
 */
public sealed interface DomainError {
    public val code: String

    /** Invalid user input or request: [field] identifies what to highlight. */
    public data class Validation(
        public val field: String,
        override val code: String,
    ) : DomainError

    /** A financial invariant would be violated (indicates a programming error if it reaches the UI). */
    public data class Invariant(
        override val code: String,
        public val detail: String,
    ) : DomainError

    public data class NotFound(
        public val what: String,
        public val id: String,
    ) : DomainError {
        override val code: String get() = "NOT_FOUND"
    }
}

public sealed interface DomainResult<out T> {
    public data class Ok<out T>(
        public val value: T,
    ) : DomainResult<T>

    public data class Err(
        public val error: DomainError,
    ) : DomainResult<Nothing>
}

public inline fun <T, R> DomainResult<T>.map(transform: (T) -> R): DomainResult<R> =
    when (this) {
        is DomainResult.Ok -> DomainResult.Ok(transform(value))
        is DomainResult.Err -> this
    }

public inline fun <T, R> DomainResult<T>.flatMap(transform: (T) -> DomainResult<R>): DomainResult<R> =
    when (this) {
        is DomainResult.Ok -> transform(value)
        is DomainResult.Err -> this
    }

/** For tests and for callers that have already validated: throws [IllegalStateException] on [DomainResult.Err]. */
public fun <T> DomainResult<T>.getOrThrow(): T =
    when (this) {
        is DomainResult.Ok -> value
        is DomainResult.Err -> throw IllegalStateException("Domain error: $error")
    }

public fun <T> DomainResult<T>.errorOrNull(): DomainError? = (this as? DomainResult.Err)?.error

/** Internal carrier used by [validating]; never escapes a [validating] block. */
@PublishedApi
internal class DomainFailure(
    val error: DomainError,
) : RuntimeException(error.code, null, false, false)

/** Scope for sequential validation: the first failed check ends the block with [DomainResult.Err]. */
public class ValidationScope internal constructor() {
    public fun fail(
        field: String,
        code: String,
    ): Nothing = throw DomainFailure(DomainError.Validation(field, code))

    public fun ensure(
        condition: Boolean,
        field: String,
        code: String,
    ) {
        if (!condition) fail(field, code)
    }

    public fun invariant(
        condition: Boolean,
        code: String,
        detail: () -> String,
    ) {
        if (!condition) throw DomainFailure(DomainError.Invariant(code, detail()))
    }

    public fun notFound(
        what: String,
        id: String,
    ): Nothing = throw DomainFailure(DomainError.NotFound(what, id))

    /** Unwraps a nested result, propagating its error. */
    public fun <T> DomainResult<T>.bind(): T =
        when (this) {
            is DomainResult.Ok -> value
            is DomainResult.Err -> throw DomainFailure(error)
        }
}

/** Runs [block]; any check that fails inside it produces [DomainResult.Err] instead of throwing. */
public inline fun <T> validating(block: ValidationScope.() -> T): DomainResult<T> =
    try {
        DomainResult.Ok(newValidationScope().block())
    } catch (e: DomainFailure) {
        DomainResult.Err(e.error)
    }

@PublishedApi
internal fun newValidationScope(): ValidationScope = ValidationScope()
