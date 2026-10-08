package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.FollowUpRequest
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [ChatEngine] that follows the port's contract with no model behind it: what `ChatEngineContract` runs the contract against,
 * and what the router tests route to. A runtime's engine is reproduced here only as far as the contract goes (loading, the
 * session, the stream, commit and discard); nothing is generated.
 *
 * @param name tells two fakes apart in a router test.
 */
class FakeChatEngine(
    val name: String = "fake",
    override val supportsThinking: Boolean = true,
) : ChatEngine {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelLoadState>(ModelLoadState.Idle)
    override val state: StateFlow<ModelLoadState> = _state

    override val isBusy: Boolean get() = mutex.isLocked

    /** What a reply streams, in pieces. */
    var reply: String = "an answer"

    /** What [lastReplyHitLimit] says after the next reply. */
    var hitLimit: Boolean = false

    /** Every `(path, config)` that reached [load], in order. */
    val loads = mutableListOf<Pair<String, InferenceConfig>>()

    /** The history of every session that was opened or re-primed, in order. */
    val sessions = mutableListOf<List<AiChatMessage>>()

    /** Every user text that reached [sendChatMessage], in order. */
    val sent = mutableListOf<String>()
    val committed = mutableListOf<String>()
    var discards = 0
        private set

    private var loaded: Pair<String, InferenceConfig>? = null
    private var sessionId: String? = null

    /** When set, the next [load] fails with this message (once), as a model that could not start does. */
    var failNextLoadWith: String? = null

    override suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities> = mutex.withLock {
        loads += modelPath to config
        failNextLoadWith?.let { message ->
            failNextLoadWith = null
            _state.value = ModelLoadState.Failed(modelPath, message)
            return@withLock PamResult.Error(com.postsaimanager.core.common.result.PamError.ModelNotLoaded(message))
        }
        // The same model with the same config changes nothing, and a primed session survives it; anything else drops it.
        if (loaded != modelPath to config) sessionId = null
        loaded = modelPath to config
        _state.value = ModelLoadState.Ready(modelPath, config, loadDurationMs = 0L)
        PamResult.Success(AiCapabilities(supportsGrammar = false, contextTokens = config.contextTokens, modelName = name, canChat = canChat))
    }

    /** What the next [load] reports as [AiCapabilities.canChat]: false plays a model whose chat template does not render. */
    var canChat: Boolean = true

    override suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean = mutex.withLock {
        val wasPrimed = sessionId == conversationId
        // The history each (re)prime was given, so a test can read what a rebuilt conversation replays.
        if (!wasPrimed) sessions += history
        sessionId = conversationId
        !wasPrimed
    }

    override suspend fun isChatSessionPrimed(conversationId: String): Boolean = sessionId == conversationId

    /** Every request that reached [warmUpChat], in order. */
    val warmUps = mutableListOf<AiRequest>()

    override suspend fun warmUpChat(request: AiRequest) {
        warmUps += request
    }

    /** Every request that reached [sendChatMessage], in order: the sampling, the reply cap and the thinking switch it carried. */
    val requests = mutableListOf<AiRequest>()

    override fun sendChatMessage(userText: String, request: AiRequest): Flow<String> = flow {
        mutex.withLock {
            requests += request
            sent += userText
            reply.chunked(4).forEach { emit(it) }
        }
    }

    override suspend fun lastReplyHitLimit(): Boolean = hitLimit

    /** What [generateStructured] answers (null: skipped because the model is busy, or a failed generation), and every request that reached it. */
    var structuredAnswer: String? = null
    val structuredRequests = mutableListOf<StructuredRequest>()

    /** What the first turn of a two-turn request answers (handed to the request's listener before the structured answer); null: nothing. */
    var leadAnswer: String? = null

    /** When set, answers [generateStructured] in place of [structuredAnswer], by the request (a null answer is "busy" or "failed"). */
    var structuredResponder: ((StructuredRequest) -> String?)? = null

    /** The key of the conversation a [generateStructured] kept open ([StructuredRequest.keepOpenAs]), or null when none is open. */
    var keptOpenKey: String? = null
        private set

    override suspend fun generateStructured(request: StructuredRequest): String? {
        structuredRequests += request
        if (request.leadPrompt != null) leadAnswer?.let { request.onLead?.invoke(it) }
        val answer = structuredResponder?.invoke(request) ?: structuredAnswer
        keptOpenKey = if (answer != null) request.keepOpenAs else null
        return answer
    }

    /** What [continueStructured] answers, by the request (null: the model is busy, or the generation failed). */
    var followUpResponder: (FollowUpRequest) -> String? = { null }

    /** Every follow-up that reached [continueStructured], in order, whether or not a conversation was open for it. */
    val followUpRequests = mutableListOf<FollowUpRequest>()

    /** The keys [closeStructured] was called for, in order. */
    val closedKeys = mutableListOf<String>()

    /** Another caller used the model: the kept conversation is gone, as the real engine's identity check finds it. */
    fun dropKeptConversation() {
        keptOpenKey = null
    }

    override suspend fun continueStructured(request: FollowUpRequest): String? {
        followUpRequests += request
        if (keptOpenKey != request.key) return null
        return followUpResponder(request).also { if (it == null) keptOpenKey = null }
    }

    override suspend fun closeStructured(key: String) {
        closedKeys += key
        if (keptOpenKey == key) keptOpenKey = null
    }

    override suspend fun commitChatReply(answer: String) {
        committed += answer
    }

    override suspend fun discardPendingReply() {
        discards++
    }

    override suspend fun resetChatSession() {
        sessionId = null
    }

    override suspend fun unload() {
        loaded = null
        sessionId = null
        _state.value = ModelLoadState.Idle
    }
}
