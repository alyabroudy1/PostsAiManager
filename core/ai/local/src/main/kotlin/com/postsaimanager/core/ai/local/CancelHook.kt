package com.postsaimanager.core.ai.local

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block], which may block its thread on a native call, and calls [onCancel] (from another
 * thread) if the calling coroutine is cancelled meanwhile, so the native call can stop early instead of
 * running to its end. [onCancel] is not called when [block] finishes by itself.
 */
internal suspend fun <T> withCancelHook(onCancel: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            if (!finished.get()) onCancel()
            throw e
        }
    }
    try {
        block()
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}
