package com.postsaimanager.core.ai.local

import com.postsaimanager.core.domain.ai.FollowUpRequest
import com.postsaimanager.core.domain.ai.StructuredRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The app-process side of [com.postsaimanager.core.domain.ai.ChatEngine.generateStructured] over AIDL
 * ([IInferenceService.generateLiteRtStructured]): "Gemma reads the letter", quiet work that is skipped, never queued, exactly like
 * [RemoteGenerateOnce] (which it mirrors): it takes the shared call [mutex] only when nothing holds it, so a reply streaming, a
 * warm-up or another reading makes the answer null at once. A missing service, a service that died, a generation that failed or ran
 * past the request's timeout also answer null, and the caller falls back to the reading it had before.
 */
internal class RemoteGenerateStructured(
    private val mutex: Mutex,
    private val service: () -> IInferenceService?,
    private val ioDispatcher: CoroutineDispatcher,
    /** The binder that carries the first turn's text back (a seam, so a JVM test needs no Android binder). */
    private val newLead: (onText: (String) -> Unit) -> ILeadCallback = { onText ->
        object : ILeadCallback.Stub() {
            override fun onLead(text: String?) {
                if (text != null) onText(text)
            }
        }
    },
) {

    /** The conversations kept open ([StructuredRequest.keepOpenAs]) and when each was last used, so a chat's warm-up does not take the model from them. */
    private val kept = ConcurrentHashMap<String, Long>()

    /**
     * True while a conversation kept open for follow-up questions is still live: it counts as the engine being busy, so the chat's
     * warm-up waits for it. An entry nobody closed (the caller died before [close]) expires after [KEPT_EXPIRY_MS] instead of
     * blocking the chat for good.
     */
    fun hasKept(now: Long = System.currentTimeMillis()): Boolean {
        kept.entries.removeIf { now - it.value > KEPT_EXPIRY_MS }
        return kept.isNotEmpty()
    }

    suspend operator fun invoke(request: StructuredRequest): String? {
        if (!mutex.tryLock()) return null
        try {
            val answer = readOnService(request)
            val key = request.keepOpenAs
            if (key != null) if (answer != null) kept[key] = System.currentTimeMillis() else kept.remove(key)
            return answer
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun readOnService(request: StructuredRequest): String? = withContext(ioDispatcher) {
        val remote = service() ?: return@withContext null
        // The first turn's text arrives on a binder thread while the call below still blocks: handed on as it comes.
        // The same callback carries the streamed answer of a one-turn request ([StructuredRequest.onPartial]: the answer so far, per line).
        val onLead = if (request.leadPrompt != null) request.onLead else request.onPartial
        val lead = if (onLead != null) newLead { text -> runBlocking { onLead(text) } } else null
        try {
            remote.generateLiteRtStructured(
                request.system, request.prompt, request.schema, request.imagePaths.toTypedArray(),
                request.maxTokens, request.temperature, request.topK, request.timeoutMs, request.leadPrompt, lead, request.keepOpenAs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** A follow-up question in the conversation kept open under [FollowUpRequest.key]: skipped like [invoke]; null when it is gone. */
    suspend fun continueWith(request: FollowUpRequest): String? {
        if (!mutex.tryLock()) return null
        try {
            val answer = withContext(ioDispatcher) {
                val remote = service() ?: return@withContext null
                try {
                    remote.continueLiteRtStructured(request.key, request.prompt, request.schema, request.timeoutMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            if (answer != null) kept[request.key] = System.currentTimeMillis() else kept.remove(request.key)
            return answer
        } finally {
            mutex.unlock()
        }
    }

    /** Closes the conversation kept open under [key]; skipped when another caller holds the model (its own conversation replaces it). */
    suspend fun close(key: String) {
        // Closed or not, it is no longer ours to guard: a caller that holds the model replaces the conversation.
        kept.remove(key)
        if (!mutex.tryLock()) return
        try {
            withContext(ioDispatcher) {
                val remote = service() ?: return@withContext
                try {
                    remote.closeLiteRtStructured(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A service that died took its conversation with it.
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        /** A kept conversation nobody closed stops counting as busy after this long without a question. */
        const val KEPT_EXPIRY_MS = 120_000L
    }
}
