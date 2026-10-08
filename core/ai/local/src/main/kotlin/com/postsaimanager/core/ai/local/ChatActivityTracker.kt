package com.postsaimanager.core.ai.local

import com.postsaimanager.core.domain.ai.ChatActivityGate
import javax.inject.Inject

/**
 * When the chat last used the model: a chat opens or primes its session, sends a message, or finishes a reply. The chat counts as
 * active for [idleWindowMs] after that, which is how long a person reads an answer and types the next message. Pure and clocked
 * from outside, so the window is unit-testable.
 */
class ChatActivityTracker(
    private val idleWindowMs: Long = IDLE_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var lastTouchMs: Long? = null

    /** The chat just used (or is about to use) the model. */
    fun touch() {
        lastTouchMs = clock()
    }

    /** True when the chat used the model within the idle window. */
    fun isRecent(): Boolean = lastTouchMs?.let { clock() - it < idleWindowMs } ?: false

    companion object {
        const val IDLE_WINDOW_MS = 2 * 60_000L
    }
}

/**
 * [ChatActivityGate] over the shared chat activity: the chat is active while any chat session, whatever the runtime of its model
 * (LiteRT-LM or llama.cpp), used the model within the idle window. This is what "background reading waits for chat" (see
 * [RemoteLiteRtChatEngine], [RemoteAiEngine]) extends to the quiet jobs that would replace the chat model between two messages.
 * Both engines touch the same [ChatActivityTracker] (it lives on the [InferenceConnection] they share).
 */
class InferenceChatActivityGate(
    private val tracker: ChatActivityTracker,
) : ChatActivityGate {

    @Inject
    constructor(connection: InferenceConnection) : this(connection.chatActivity)

    override fun isChatActive(): Boolean = tracker.isRecent()
}
