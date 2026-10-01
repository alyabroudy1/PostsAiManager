package com.postsaimanager.core.domain.agent

import com.postsaimanager.core.domain.ai.AiRequest

/**
 * What one model needs to run as an agent, as data of its [com.postsaimanager.core.domain.extraction.zones.ModelProfile]: the
 * tool-calling format its template speaks and the limits that keep a small model on track.
 *
 * Sampling is low on purpose: a call must be exactly right, and the grammar already guarantees its shape. Thinking is always
 * off for an agent step (a reasoning trace would eat the step's budget and add nothing to a constrained call).
 */
data class AgentProfile(
    val format: ToolFormatId = ToolFormatId.QWEN,
    /** At most this many model steps per user turn; a turn that reaches it without handing back to the user stops. */
    val maxStepsPerTurn: Int = 6,
    /** The token budget of one call. */
    val maxStepTokens: Int = 256,
    val temperature: Float = 0.2f,
    val topK: Int = 20,
    val topP: Float = 0.9f,
    /** A reply that is not a call is discarded and asked for again at most this many times in a row, then the run fails. */
    val maxInvalidRetries: Int = 2,
    /** History sent when a session is (re)built: at most this many characters (about a third as many tokens), whole turns only. */
    val historyChars: Int = 5_000,
    /** The model's window; the system prompt (the tools), the conversation and one reply must fit in it. */
    val contextTokens: Int = 4_096,
    /** How many characters one token is, pessimistically (tool JSON, tags and German text are token-heavy). */
    val charsPerToken: Int = 3,
) {
    /**
     * How many characters the conversation may use beyond the system prompt of [systemChars] before the standing session is rebuilt
     * from the compact history: the window (with a margin) less one reply and the system prompt, never less than [MIN_ROOM_CHARS].
     */
    fun conversationRoom(systemChars: Int): Int =
        ((contextTokens * charsPerToken * WINDOW_USE).toInt() - maxStepTokens * charsPerToken - systemChars).coerceAtLeast(MIN_ROOM_CHARS)

    fun request(grammar: String): AiRequest = AiRequest(
        prompt = "",
        maxTokens = maxStepTokens,
        temperature = temperature,
        topK = topK,
        topP = topP,
        presencePenalty = 0f,
        grammar = grammar,
        thinkingEnabled = false,
        thinkingBudgetTokens = 0,
    )

    private companion object {
        const val WINDOW_USE = 0.85
        const val MIN_ROOM_CHARS = 1_500
    }
}
