package cc.jaxy.bob.core.common.result

/**
 * Unified error model for all layers.
 *
 * @property code one of NETWORK / HTTP_{code} / AUTH / TRPC_{code} / INVALID_INPUT / UNKNOWN.
 */
data class AnError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
)

sealed interface AnResult<out T> {
    data class Ok<T>(val value: T) : AnResult<T>

    data class Err<T>(val error: AnError) : AnResult<T>
}

inline fun <T, R> AnResult<T>.map(transform: (T) -> R): AnResult<R> =
    when (this) {
        is AnResult.Ok -> AnResult.Ok(transform(value))
        is AnResult.Err -> AnResult.Err(error)
    }

inline fun <T, R> AnResult<T>.flatMap(transform: (T) -> AnResult<R>): AnResult<R> =
    when (this) {
        is AnResult.Ok -> transform(value)
        is AnResult.Err -> AnResult.Err(error)
    }

fun <T> AnResult<T>.getOrNull(): T? =
    when (this) {
        is AnResult.Ok -> value
        is AnResult.Err -> null
    }

fun <T> AnResult<T>.exceptionOrNull(): Throwable? =
    when (this) {
        is AnResult.Ok -> null
        is AnResult.Err -> error.cause ?: IllegalStateException("${error.code}: ${error.message}")
    }

inline fun <T> runCatchingResult(
    code: String = "UNKNOWN",
    block: () -> T,
): AnResult<T> =
    try {
        AnResult.Ok(block())
    } catch (t: Throwable) {
        AnResult.Err(AnError(code = code, message = t.message ?: t.toString(), cause = t))
    }
