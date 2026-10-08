package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.model.Accelerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which accelerators the device offers, asked of the inference process without ever waiting on it for long.
 *
 * The inference process answers on the same single thread that reads a letter or writes a reply, so while it is busy (a reading takes
 * a minute or more, and queued ones follow) the question waits behind them, and every screen that needs the answer (the On-device AI
 * settings, the model sheet) stayed empty with it. What a device offers does not change while the app runs, so the last real answer is
 * kept and reused; a probe that does not answer within [timeoutMs] gives that answer, or CPU alone when there has been none yet. The
 * question runs on [scope] (one at a time), so giving up on the answer never cancels the call itself. A CPU-only answer may mean "could
 * not reach the service", so it never replaces a richer one learned before.
 */
class AcceleratorProbe(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val ask: suspend () -> Set<Accelerator>,
) {
    @Volatile
    private var known: Set<Accelerator>? = null
    private var inFlight: Deferred<Set<Accelerator>?>? = null

    suspend fun current(): Set<Accelerator> {
        val call = synchronized(this) {
            inFlight?.takeIf { it.isActive } ?: scope.async { runCatching { ask() }.getOrNull() }.also { inFlight = it }
        }
        val answer = withTimeoutOrNull(timeoutMs) { call.await() }?.takeIf { it.isNotEmpty() }
        if (answer != null && answer.size > 1) known = answer
        return known ?: answer ?: setOf(Accelerator.CPU)
    }

    companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
