package com.postsaimanager.core.domain.agent

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the agent loop asks of the on-device model: one standing session (the engine's KV cache is the conversation, so only
 * the newest turn is decoded each step), one constrained reply per step.
 */
interface AgentModel {

    /** Loads the active chat model; an error when none is installed or it fails to load. */
    suspend fun ensureLoaded(): PamResult<Unit>

    /**
     * Makes sure the session [conversationId] holds [system] and [history]; a no-op when it is already open. True when it was rebuilt.
     */
    suspend fun openSession(conversationId: String, system: String, history: List<AiChatMessage>): Boolean

    /** The complete reply to [message] under [request] (its grammar makes it a call). The reply is not part of the session until [commit]. */
    suspend fun step(message: String, request: AiRequest): PamResult<String>

    /** The reply of the last [step] was used: it becomes part of the session. */
    suspend fun commit(reply: String)

    /** The reply of the last [step] was not used (unreadable, or the run was stopped): its tokens leave the session. */
    suspend fun discard()

    /** Drops the session, so the next [openSession] rebuilds it from the compact history. */
    suspend fun resetSession()
}

/** [AgentModel] over the standing engine: the chat session of [AiEngine], with the call grammar and no thinking. */
class EngineAgentModel @Inject constructor(
    private val engine: AiEngine,
    private val activeModels: ActiveModelProvider,
) : AgentModel {

    override suspend fun ensureLoaded(): PamResult<Unit> {
        val path = activeModels.activeModelPath() ?: return PamResult.Error(PamError.ModelNotLoaded("chat"))
        return when (val loaded = engine.load(path, activeModels.activeModelConfig())) {
            is PamResult.Error -> loaded
            is PamResult.Success -> PamResult.Success(Unit)
        }
    }

    override suspend fun openSession(conversationId: String, system: String, history: List<AiChatMessage>): Boolean =
        engine.ensureChatSession(conversationId, system, history)

    override suspend fun step(message: String, request: AiRequest): PamResult<String> {
        val reply = StringBuilder()
        var finished = false
        return try {
            engine.sendChatMessage(message, request).collect { reply.append(it) }
            finished = true
            PamResult.Success(reply.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PamResult.Error(PamError.InferenceError(e.message ?: "generation failed", e))
        } finally {
            if (!finished) withContext(NonCancellable) { engine.discardPendingReply() }
        }
    }

    override suspend fun commit(reply: String) = engine.commitChatReply(reply)

    override suspend fun discard() = engine.discardPendingReply()

    override suspend fun resetSession() = engine.resetChatSession()
}
