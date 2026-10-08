package com.postsaimanager.core.ai.local

import com.postsaimanager.core.domain.ai.FollowUpRequest
import com.postsaimanager.core.domain.ai.StructuredRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

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

    suspend operator fun invoke(request: StructuredRequest): String? {
        if (!mutex.tryLock()) return null
        try {
            return withContext(ioDispatcher) {
                val remote = service() ?: return@withContext null
                // The first turn's text arrives on a binder thread while the call below still blocks: handed on as it comes.
                val onLead = request.onLead
                val lead = if (request.leadPrompt != null && onLead != null) newLead { text -> runBlocking { onLead(text) } } else null
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
        } finally {
            mutex.unlock()
        }
    }

    /** A follow-up question in the conversation kept open under [FollowUpRequest.key]: skipped like [invoke]; null when it is gone. */
    suspend fun continueWith(request: FollowUpRequest): String? {
        if (!mutex.tryLock()) return null
        try {
            return withContext(ioDispatcher) {
                val remote = service() ?: return@withContext null
                try {
                    remote.continueLiteRtStructured(request.key, request.prompt, request.schema, request.timeoutMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Closes the conversation kept open under [key]; skipped when another caller holds the model (its own conversation replaces it). */
    suspend fun close(key: String) {
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
}
