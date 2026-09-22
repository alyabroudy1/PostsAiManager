package com.postsaimanager.core.domain.ai

/**
 * One piece of a model's streamed output, classified as reasoning or answer.
 *
 * [Thinking] is whatever a model wrote inside `<think>…</think>` — Qwen3/Qwen3.5 and
 * DeepSeek's reasoning models both use this convention. [Answer] is everything else: the
 * reply a user actually reads, and the only half that [AiChatMessage] history ever carries
 * forward (see `SendChatMessageUseCase`'s KDoc on "what is sent to the model").
 */
sealed interface StreamSegment {
    data class Thinking(val delta: String) : StreamSegment
    data class Answer(val delta: String) : StreamSegment
}

/**
 * Splits a raw token stream into [StreamSegment.Thinking] and [StreamSegment.Answer]
 * pieces, one chunk at a time.
 *
 * ### Why this exists
 *
 * A reasoning model writes its chain of thought and its final answer into the *same* token
 * stream, delimited by `<think>`/`</think>`. Left unparsed, that reasoning trace ends up:
 * rendered inline as if it were the answer, and — worse — sent back to the model on every
 * later turn once it is folded into persisted message content, ballooning the prompt with
 * text nobody asked to keep and that was never meant to be re-read. This type is the one
 * place that boundary is drawn; every other layer (persistence, prompt-building, UI) just
 * consumes its output.
 *
 * ### Streaming, not buffering
 *
 * Tokens do not respect tag boundaries — `<thi` and `nk>` can arrive as two separate
 * chunks from [com.postsaimanager.core.domain.ai.AiEngine.generate]. [consume] therefore
 * keeps an internal buffer holding only the *unresolved* suffix of what it has seen (at
 * most `tag.length - 1` characters) and emits everything else immediately, so a caller gets
 * segments as soon as they are unambiguous rather than only at the end of generation.
 *
 * ### Handled cases
 *  - `<think>`/`</think>` split across any number of chunk boundaries.
 *  - A stream that ends while still inside `<think>` (never closed): [finish] flushes the
 *    remainder as [StreamSegment.Thinking] — callers surface this as "no answer was
 *    produced" rather than showing an empty bubble.
 *  - A model that never emits the tags at all: everything is [StreamSegment.Answer].
 *  - A second, nested `<think>` before the first is closed: treated as ordinary thinking
 *    text — only the *next* `</think>` ends the block, matching how no model in practice
 *    nests these tags on purpose.
 *  - Whitespace immediately after `</think>` (the newline(s) models conventionally emit
 *    before the answer) is trimmed once, so the answer does not start with a blank line.
 *
 * One instance per generation — it is stateful and not thread-safe.
 */
class ThinkingStreamParser {

    private enum class Mode { BEFORE_THINK, IN_THINK, AFTER_THINK }

    private var mode = Mode.BEFORE_THINK
    private val pending = StringBuilder()
    private var trimAnswerLeadingWhitespace = false

    /** Feeds one chunk of raw model output; returns the segments it resolves, if any. */
    fun consume(chunk: String): List<StreamSegment> {
        if (chunk.isEmpty()) return emptyList()
        pending.append(chunk)
        val out = mutableListOf<StreamSegment>()
        process(out)
        return out
    }

    /**
     * Call once generation has ended. Flushes whatever is still held back as a partial tag
     * match — there is no more input that could complete it, so it was never a tag.
     */
    fun finish(): List<StreamSegment> {
        if (pending.isEmpty()) return emptyList()
        val text = pending.toString()
        pending.setLength(0)
        val segment = when (mode) {
            Mode.BEFORE_THINK, Mode.AFTER_THINK -> StreamSegment.Answer(applyTrim(text))
            Mode.IN_THINK -> StreamSegment.Thinking(text)
        }
        return listOf(segment)
    }

    private fun process(out: MutableList<StreamSegment>) {
        while (true) {
            when (mode) {
                Mode.BEFORE_THINK -> if (!consumeUntilTag(OPEN_TAG, Mode.IN_THINK, ::asAnswer, out)) return
                Mode.IN_THINK -> if (!consumeUntilTag(CLOSE_TAG, Mode.AFTER_THINK, ::asThinking, out)) return
                Mode.AFTER_THINK -> {
                    if (pending.isEmpty()) return
                    val text = applyTrim(pending.toString())
                    pending.setLength(0)
                    if (text.isNotEmpty()) out += StreamSegment.Answer(text)
                    return
                }
            }
        }
    }

    /**
     * Looks for [tag] in [pending]. If found: everything before it is emitted via [wrap],
     * the tag is consumed, and [nextMode] is entered (returns true so the caller loops and
     * processes whatever remains in the new mode). If not found: emits everything except a
     * trailing suffix that could still become the start of [tag], and returns false — there
     * is nothing more this call can resolve until the next chunk arrives.
     */
    private fun consumeUntilTag(
        tag: String,
        nextMode: Mode,
        wrap: (String) -> StreamSegment,
        out: MutableList<StreamSegment>,
    ): Boolean {
        val text = pending.toString()
        val idx = text.indexOf(tag)
        if (idx >= 0) {
            if (idx > 0) out += wrap(text.substring(0, idx))
            pending.setLength(0)
            pending.append(text.substring(idx + tag.length))
            mode = nextMode
            if (nextMode == Mode.AFTER_THINK) trimAnswerLeadingWhitespace = true
            return true
        }
        val safe = safeEmitLength(text, tag)
        if (safe > 0) {
            out += wrap(text.substring(0, safe))
            pending.setLength(0)
            pending.append(text.substring(safe))
        }
        return false
    }

    /** The longest prefix of [text] that cannot be the start of a still-arriving [tag]. */
    private fun safeEmitLength(text: String, tag: String): Int {
        val maxOverlap = minOf(tag.length - 1, text.length)
        for (overlap in maxOverlap downTo 1) {
            if (tag.startsWith(text.substring(text.length - overlap))) {
                return text.length - overlap
            }
        }
        return text.length
    }

    private fun applyTrim(text: String): String {
        if (!trimAnswerLeadingWhitespace) return text
        val trimmed = text.trimStart('\n', '\r', ' ', '\t')
        if (trimmed.isNotEmpty()) trimAnswerLeadingWhitespace = false
        return trimmed
    }

    private fun asAnswer(text: String) = StreamSegment.Answer(text)
    private fun asThinking(text: String) = StreamSegment.Thinking(text)

    private companion object {
        const val OPEN_TAG = "<think>"
        const val CLOSE_TAG = "</think>"
    }
}
