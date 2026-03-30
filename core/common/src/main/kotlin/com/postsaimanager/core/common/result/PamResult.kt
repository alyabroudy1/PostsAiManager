package com.postsaimanager.core.common.result

/**
 * A sealed interface representing the result of an operation.
 * Every operation in the app returns PamResult to ensure consistent error handling.
 *
 * Usage:
 * ```kotlin
 * when (val result = someUseCase()) {
 *     is PamResult.Success -> handleData(result.data)
 *     is PamResult.Error -> showError(result.error)
 * }
 * ```
 */
sealed interface PamResult<out T> {
    data class Success<T>(val data: T) : PamResult<T>
    data class Error(val error: PamError) : PamResult<Nothing>
}

/**
 * Extension to map a successful result while preserving errors.
 */
inline fun <T, R> PamResult<T>.map(transform: (T) -> R): PamResult<R> = when (this) {
    is PamResult.Success -> PamResult.Success(transform(data))
    is PamResult.Error -> this
}

/**
 * Extension to perform an action on success.
 */
inline fun <T> PamResult<T>.onSuccess(action: (T) -> Unit): PamResult<T> {
    if (this is PamResult.Success) action(data)
    return this
}

/**
 * Extension to perform an action on error.
 */
inline fun <T> PamResult<T>.onError(action: (PamError) -> Unit): PamResult<T> {
    if (this is PamResult.Error) action(error)
    return this
}

/**
 * Get data or null.
 */
fun <T> PamResult<T>.getOrNull(): T? = when (this) {
    is PamResult.Success -> data
    is PamResult.Error -> null
}

/**
 * Get data or throw.
 */
fun <T> PamResult<T>.getOrThrow(): T = when (this) {
    is PamResult.Success -> data
    is PamResult.Error -> throw error.cause ?: IllegalStateException(error.userMessage)
}
