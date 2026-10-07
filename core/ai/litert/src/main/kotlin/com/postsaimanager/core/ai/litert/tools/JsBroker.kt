package com.postsaimanager.core.ai.litert.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a `run_js` call waits for its script. The tool runs in the `:inference` process and the script runs in the app process
 * (the WebView lives there), so the call publishes a request, registers here, and blocks until the app process delivers the
 * answer over AIDL, or the time is up.
 *
 * The only state is the calls waiting; it is a plain class so it is tested without the LiteRT-LM library.
 */
internal class JsBroker {

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Registers the call [id] before its request is published, so an answer can never arrive ahead of the wait. */
    fun expect(id: String): CompletableDeferred<String> = CompletableDeferred<String>().also { waiting[id] = it }

    /** The answer of call [id]. Ignored when nothing waits for it (a late answer after a timeout or a stopped reply). */
    fun deliver(id: String, result: String) {
        waiting.remove(id)?.complete(result)
    }

    /** Waits for the answer of call [id] for at most [timeoutMs]; null when none came. The call is forgotten either way. */
    suspend fun await(id: String, answer: CompletableDeferred<String>, timeoutMs: Long): String? =
        try {
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            waiting.remove(id)
        }

    /** A reply ended or was stopped: nothing waits for the calls still open. */
    fun cancelAll() {
        waiting.values.forEach { it.cancel() }
        waiting.clear()
    }
}
