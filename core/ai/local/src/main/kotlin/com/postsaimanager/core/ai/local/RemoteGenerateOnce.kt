package com.postsaimanager.core.ai.local

import com.postsaimanager.core.domain.ai.AiRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * The app-process side of [com.postsaimanager.core.domain.ai.ChatEngine.generateOnce] over AIDL
 * ([IInferenceService.generateLiteRtOnce]): quiet work that is skipped, never queued.
 *
 * It takes the shared call [mutex] only when nothing holds it ([Mutex.tryLock]): a reply streaming, a warm-up or a document being
 * read makes the answer null at once. Holding it for the whole call keeps a read from replacing the model meanwhile, as for every
 * other call that touches the model. A missing service, a service that died or one that could not generate also answer null.
 */
internal class RemoteGenerateOnce(
    private val mutex: Mutex,
    private val service: () -> IInferenceService?,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(system: String, request: AiRequest): String? {
        if (!mutex.tryLock()) return null
        try {
            return withContext(ioDispatcher) {
                val remote = service() ?: return@withContext null
                try {
                    remote.generateLiteRtOnce(system, request.prompt, request.maxTokens, request.temperature, request.topK)
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
}
