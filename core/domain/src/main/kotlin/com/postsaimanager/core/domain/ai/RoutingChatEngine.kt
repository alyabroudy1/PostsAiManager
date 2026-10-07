package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.skills.JsSkillRequest
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ModelRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The [ChatEngine] features see: it forwards every call to the engine of the runtime the chat model asked for.
 *
 * The choice is made once, in [load]: the model's [InferenceConfig.runtime] (catalogue data) picks the engine, and every call
 * after it goes to that engine. So a feature never branches on the runtime, and switching the chat model from a llama.cpp model
 * to a LiteRT-LM one (or back) is just the next [load] naming the other runtime.
 *
 * Only one engine holds a model at a time; that is enforced where the models live (the `:inference` process frees the other
 * runtime's model when one loads), not here. This class owns no model and no state of its own beyond which engine is current.
 *
 * @param engines the engine of each runtime; a runtime without one fails its [load] with a typed error rather than crashing.
 * @param initial the runtime answered before any [load].
 */
class RoutingChatEngine(
    private val engines: Map<ModelRuntime, ChatEngine>,
    initial: ModelRuntime = ModelRuntime.LLAMA_CPP,
) : ChatEngine {

    private val current = MutableStateFlow(initial)

    private fun active(): ChatEngine = engines.getValue(current.value)

    /** The runtime the chat engine currently answers for. */
    val activeRuntime: ModelRuntime get() = current.value

    /**
     * The current engine's state, readable synchronously right after [load] (a [StateFlow] backed by a hot [stateIn] would lag
     * the load by a dispatch, and the chat use case reads `state.value` straight after it).
     */
    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    override val state: StateFlow<ModelLoadState> = object : StateFlow<ModelLoadState> {
        override val value: ModelLoadState get() = active().state.value
        override val replayCache: List<ModelLoadState> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<ModelLoadState>): Nothing {
            current.flatMapLatest { runtime -> engines.getValue(runtime).state }.collect(collector)
            awaitCancellation()
        }
    }

    override val isBusy: Boolean get() = engines.values.any { it.isBusy }

    override val supportsThinking: Boolean get() = active().supportsThinking

    /** The tool calls of whichever engine is current (a runtime switch moves the collection with it). */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val toolActions: Flow<ToolActionCall> = current.flatMapLatest { runtime -> engines.getValue(runtime).toolActions }

    /** The `run_js` calls of whichever engine is current. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val jsRequests: Flow<JsSkillRequest> = current.flatMapLatest { runtime -> engines.getValue(runtime).jsRequests }

    override suspend fun deliverJsResult(requestId: String, result: String) = active().deliverJsResult(requestId, result)

    override suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities> {
        val target = engines[config.runtime]
            ?: return PamResult.Error(PamError.ModelNotLoaded("No engine can run ${config.runtime} models."))
        current.value = config.runtime
        return target.load(modelPath, config)
    }

    override suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean = active().ensureChatSession(conversationId, systemPrompt, history)

    override suspend fun isChatSessionPrimed(conversationId: String): Boolean = active().isChatSessionPrimed(conversationId)

    override fun sendChatMessage(userText: String, request: AiRequest): Flow<String> = active().sendChatMessage(userText, request)

    override suspend fun lastReplyHitLimit(): Boolean = active().lastReplyHitLimit()

    override suspend fun lastReplyToolExchanges() = active().lastReplyToolExchanges()

    override suspend fun commitChatReply(answer: String) = active().commitChatReply(answer)

    override suspend fun discardPendingReply() = active().discardPendingReply()

    override suspend fun resetChatSession() = active().resetChatSession()

    override suspend fun unload() = engines.values.forEach { it.unload() }
}
