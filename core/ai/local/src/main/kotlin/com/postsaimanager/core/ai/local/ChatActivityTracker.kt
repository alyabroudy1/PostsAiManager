package com.postsaimanager.core.ai.local

import com.postsaimanager.core.domain.ai.ChatActivityGate
import com.postsaimanager.core.model.ModelRuntime
import javax.inject.Inject
import javax.inject.Singleton

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
 * [ChatActivityGate] over the shared [InferenceConnection]: the chat is active while the resident model is a LiteRT-LM chat model
 * and the chat used it within the idle window. With another model (or none) resident there is nothing to protect. This is what
 * "background reading waits for chat" (see [RemoteLiteRtChatEngine]) extends to the quiet jobs that would replace the chat model
 * between two messages.
 */
@Singleton
class InferenceChatActivityGate @Inject constructor(
    private val connection: InferenceConnection,
) : ChatActivityGate {

    override fun isChatActive(): Boolean = connection.resident == ModelRuntime.LITERT_LM && connection.chatActivity.isRecent()
}
