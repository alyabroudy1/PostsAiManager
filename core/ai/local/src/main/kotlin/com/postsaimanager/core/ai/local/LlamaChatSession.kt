package com.postsaimanager.core.ai.local

import android.util.Log
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.model.InferenceConfig

/**
 * Which conversation's chat session is primed in the `:inference` process's KV cache, and what it takes to prime it again.
 *
 * Reading (a prompt session, a one-shot generation) takes the KV cache over, and a model load replaces it. The engine lock is
 * released between [primeLocked] (the end of `ensureChatSession`) and the send, so a read can take the cache in that gap. The
 * session therefore remembers the conversation it was primed for (the system prompt, the history, the model it was primed on),
 * and [ensureStillOursLocked], called by the send under the same lock it streams under, primes it again when it was taken over.
 * That repairs the turn instead of failing it.
 *
 * Every function marked "Locked" is called with the engine lock held: this class takes no lock of its own.
 *
 * @param remote the connected service, or null when there is none.
 * @param residentModel the model the engine holds right now (its file and the config it was loaded with), or null.
 * @param loadModel loads a model through the engine's single-flight load; used to bring the chat model back when a read replaced it.
 */
internal class LlamaChatSession(
    private val remote: suspend () -> IInferenceService?,
    private val residentModel: () -> Pair<String, InferenceConfig>?,
    private val loadModel: suspend (String, InferenceConfig) -> PamResult<*>,
) {

    private class Plan(
        val conversationId: String,
        val systemPrompt: String,
        /** What the cache holds: the history it was primed with, then every committed turn of it. */
        val history: List<AiChatMessage>,
        val model: Pair<String, InferenceConfig>?,
    )

    @Volatile
    private var primedConversationId: String? = null

    @Volatile
    private var plan: Plan? = null

    /** The user text of the turn in flight: it joins the history once its reply is committed, and is dropped when the reply is discarded. */
    private var pendingUserText: String? = null

    /** True when the cache holds [conversationId]'s session. A pure read. */
    fun isPrimed(conversationId: String): Boolean = primedConversationId == conversationId

    /** The cache no longer holds the session (a read, a one-shot generation or a load took it); the plan to prime it again stays. */
    fun invalidate() {
        primedConversationId = null
    }

    /** The conversation is over (reset, unload, crash): nothing is primed and nothing is to be primed again. */
    fun clear() {
        primedConversationId = null
        plan = null
        pendingUserText = null
    }

    /**
     * Opens and primes the session for [conversationId] with [history] (the history is authoritative: it replaces anything
     * remembered). Returns true when it was just primed, false when it already was or could not be.
     */
    suspend fun primeLocked(conversationId: String, systemPrompt: String, history: List<AiChatMessage>): Boolean {
        if (primedConversationId == conversationId) return false
        // A failed prime must not leave the previous conversation's plan behind to be restored into this one's send.
        if (plan?.conversationId != conversationId) plan = null
        pendingUserText = null
        val service = remote() ?: return false
        val opened = runCatching { service.openChatSession(systemPrompt) }.getOrDefault(false)
        if (!opened) return false
        if (history.isNotEmpty()) {
            val primed = runCatching {
                service.primeChatSession(
                    history.map { it.role.wireName }.toTypedArray(),
                    history.map { it.content }.toTypedArray(),
                )
            }.getOrDefault(false)
            if (!primed) return false
        }
        plan = Plan(conversationId, systemPrompt, history, residentModel())
        primedConversationId = conversationId
        note("chat session primed with ${history.size} prior turns")
        return true
    }

    /** A log line that never fails (the JVM tests have no Android log). */
    private fun note(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    /**
     * Called by a send, under the lock it streams under, before it hands the user's text over: when the session was taken over
     * since [primeLocked] (a read opened, a one-shot ran, the model was replaced), brings the chat model back if it was replaced
     * and primes the session again from what it held. Without a session to restore, nothing happens and the send goes on as it
     * always did.
     *
     * @return false only when the session could not be restored; the send then fails as a turn that could not start.
     */
    suspend fun ensureStillOursLocked(userText: String): Boolean {
        val restored = restoreLocked()
        pendingUserText = userText
        return restored
    }

    private suspend fun restoreLocked(): Boolean {
        val current = plan ?: return true
        val modelBack = current.model == null || residentModel()?.first == current.model.first
        if (primedConversationId == current.conversationId && modelBack) return true
        note("chat session was taken over (modelBack=$modelBack): priming it again")
        if (!modelBack && current.model != null && loadModel(current.model.first, current.model.second) is PamResult.Error) return false
        primedConversationId = null
        return primeLocked(current.conversationId, current.systemPrompt, current.history)
    }

    /** The reply was kept: the turn is part of what the cache holds. */
    fun onCommitted(answer: String) {
        val current = plan
        val user = pendingUserText
        pendingUserText = null
        if (current == null || user == null) return
        plan = Plan(
            current.conversationId,
            current.systemPrompt,
            current.history + AiChatMessage(AiChatRole.USER, user) + AiChatMessage(AiChatRole.ASSISTANT, answer),
            current.model,
        )
    }

    /** The reply was dropped (stopped, failed, cut off): the turn is not part of what the cache holds. */
    fun onDiscarded() {
        pendingUserText = null
    }

    private companion object {
        const val TAG = "LlamaChatSession"
    }
}
