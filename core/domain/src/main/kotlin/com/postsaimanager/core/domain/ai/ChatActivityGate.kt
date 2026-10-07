package com.postsaimanager.core.domain.ai

import kotlinx.coroutines.delay

/**
 * Whether a chat is in progress on the resident chat model, so that other model work does not take the model over while the
 * user is talking to it. Only one model is resident at a time, and a chat model that is replaced must be loaded again and must
 * read the whole conversation again (tens of seconds on a phone CPU), so quiet background work waits for the chat to be idle
 * instead of evicting it. This is scheduling only: it never looks at what anything says.
 */
interface ChatActivityGate {

    /** True while a chat model is resident and the chat was used within the idle window. */
    fun isChatActive(): Boolean

    /**
     * Waits until the chat is idle, for at most [maxWaitMs]. Quiet work calls it before it starts and, when it returns false,
     * asks to be run again later (nothing is dropped).
     *
     * @return true when the chat is idle (the work may use the model), false when it was still active after [maxWaitMs].
     */
    suspend fun awaitIdle(maxWaitMs: Long = DEFAULT_MAX_WAIT_MS, pollMs: Long = POLL_MS): Boolean {
        var waited = 0L
        while (isChatActive()) {
            if (waited >= maxWaitMs) return false
            delay(pollMs)
            waited += pollMs
        }
        return true
    }

    /** No chat: everything may run at once (the default where nothing tracks chats, and in tests). */
    object Idle : ChatActivityGate {
        override fun isChatActive(): Boolean = false
    }

    companion object {
        /** How long quiet work waits in one run before it asks to be scheduled again. */
        const val DEFAULT_MAX_WAIT_MS = 5 * 60_000L
        const val POLL_MS = 5_000L
    }
}
