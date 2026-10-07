package com.postsaimanager.core.ai.litert

/**
 * Hands the model's reasoning trace to the app inside the one token stream the chat port has, as `<think>…</think>`.
 *
 * LiteRT-LM streams the reasoning on its own channel (`THOUGHT_CHANNEL`, the Gallery's `partialThinkingResult`), apart from the
 * answer. The app already splits a reasoning trace out of a stream that carries it between think tags (`ThinkingStreamParser`),
 * for the llama.cpp models, and starts in "thinking" for a turn that asked for it. This writes the thought channel in that form,
 * so the same parser, the same stored trace and the same collapsed "Thought for N s" body serve both engines:
 *
 *  - the thought text goes out as it comes;
 *  - before the first answer text, once, `</think>` closes it (a turn that thought nothing is closed the same way, because the
 *    parser starts inside the trace for every turn that asked for thinking).
 *
 * With [enabled] false (a turn that did not ask for thinking) nothing is added and the thought channel is ignored, exactly as before.
 */
internal class ThoughtStream(private val enabled: Boolean) {

    private var closed = false

    /** The thought text of one chunk, as it goes into the stream (empty when thinking was not asked for). */
    fun thought(chunk: String?): String = if (enabled) chunk.orEmpty() else ""

    /** What must precede [answer] in the stream: the closing tag, once, before the first answer text. */
    fun closeBefore(answer: String): String {
        if (!enabled || closed || answer.isEmpty()) return ""
        closed = true
        return CLOSE_TAG
    }

    companion object {
        const val CLOSE_TAG = "</think>"
    }
}
