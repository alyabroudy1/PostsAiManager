package com.postsaimanager.core.common.dispatcher

import javax.inject.Qualifier

/**
 * Qualifier annotations for dispatchers to support proper dependency injection
 * and testability. Use these instead of hardcoding Dispatchers.IO etc.
 *
 * Usage:
 * ```kotlin
 * class MyRepository @Inject constructor(
 *     @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher
 * )
 * ```
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val pamDispatcher: PamDispatcher)

enum class PamDispatcher {
    DEFAULT,
    IO,
    MAIN,
}
