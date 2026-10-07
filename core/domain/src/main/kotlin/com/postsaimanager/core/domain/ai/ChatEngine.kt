package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The port through which the app holds a conversation with a model: load it, open a standing session over the grounding and the
 * history, stream a reply, cancel it, roll it back.
 *
 * Chat is the part of an engine that every runtime can offer. [AiEngine] (llama.cpp) adds what only it has (one-shot grammar
 * generation, token scoring through `PromptSession`); the LiteRT-LM engine implements this port alone. A feature depends on
 * [ChatEngine] and never branches on the runtime: the router (`RoutingChatEngine`) picks the engine from
 * [InferenceConfig.runtime], which the catalogue's model descriptor decides.
 *
 * ### Contract (every implementation, pinned by `ChatEngineContractTest`)
 *
 * 1. [load] is cheap when nothing changed, and replaces the resident model when something did. A successful load leaves [state]
 *    at [ModelLoadState.Ready] with exactly the requested config.
 * 2. [ensureChatSession] opens (or re-primes) the session for a conversation and tells [isChatSessionPrimed] true for it; a [load]
 *    that changed anything makes it false again.
 * 3. [sendChatMessage] streams the reply to [userText] inside that session. Cancelling the collector stops generation.
 * 4. Exactly one of [commitChatReply] (the finished reply) or [discardPendingReply] (a stopped, failed or cut-off one) follows
 *    every [sendChatMessage]: a discarded reply never becomes something the model said.
 * 5. Calls that touch the model are serialised: a caller's whole call, including the whole token stream, ends before the next
 *    caller's starts. [isBusy] says whether another caller holds it right now.
 */
interface ChatEngine {

    /** Where the model is in its load lifecycle. */
    val state: StateFlow<ModelLoadState>

    /**
     * True while another caller currently holds the model: a generation already in flight (see [AiEngine]'s "One native context,
     * two callers"). A cheap read of local state, only used to decide what to *say*, never what to do.
     */
    val isBusy: Boolean

    /**
     * Whether this engine can run a reasoning trace and hand it back separately. False for an engine that cannot; the chat
     * use case then asks for no thinking, whatever effort the user set. Read after [load], which picks the engine.
     */
    val supportsThinking: Boolean get() = true

    /**
     * The `run_intent` calls the model makes while it replies, when [AiRequest.tools] was set: hot, no replay, one item per call,
     * emitted before the reply's stream ends. An engine without tools never emits. A tool never executes anything: this is only the
     * proposal, which the app checks and shows on a card (`ObserveToolActionsUseCase`).
     */
    val toolActions: Flow<ToolActionCall> get() = emptyFlow()

    /**
     * Loads a model, replacing any currently loaded one.
     *
     * @param config everything the runtime needs to load the model and sample from it; [InferenceConfig.runtime] says which
     *   engine takes it. Callers get one from [ActiveModelProvider].
     */
    suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities>

    /**
     * Opens or reuses a standing chat session for [conversationId] — the engine's state *is* the conversation from here on.
     * A no-op when this conversation's session is already open and valid.
     *
     * @param history prior turns, oldest first, replayed once when (re)priming is needed. Assistant turns must already be
     *   thinking-stripped.
     * @return true if the session was just (re)primed, false if an open one was reused. Informational only.
     */
    suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean

    /** True when [ensureChatSession] for [conversationId] would be a no-op right now. A pure read of local bookkeeping. */
    suspend fun isChatSessionPrimed(conversationId: String): Boolean

    /**
     * Streams a reply to [userText] within the session opened by [ensureChatSession]. [request] carries the sampling, the reply
     * budget and the thinking switch; its `prompt` and `grammar` are not used by chat.
     *
     * The caller must call [commitChatReply] or [discardPendingReply] once the outcome is known.
     */
    fun sendChatMessage(userText: String, request: AiRequest): Flow<String>

    /**
     * True when the most recent [sendChatMessage] stopped because it reached its token cap rather than an end-of-generation
     * token — the reply is cut off mid-thought. Read once, right after the stream completes.
     */
    suspend fun lastReplyHitLimit(): Boolean = false

    /** Appends [answer] (thinking-stripped) to the open session's history. */
    suspend fun commitChatReply(answer: String)

    /**
     * Rolls back an interrupted reply: the user's turn stays, no assistant turn is appended. A no-op when no session is open.
     */
    suspend fun discardPendingReply()

    /** Drops the standing chat session. E.g. on conversation switch. */
    suspend fun resetChatSession()

    suspend fun unload()
}
