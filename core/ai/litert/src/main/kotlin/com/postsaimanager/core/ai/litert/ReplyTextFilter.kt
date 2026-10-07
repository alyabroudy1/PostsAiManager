package com.postsaimanager.core.ai.litert

/**
 * Keeps the model's protocol out of the chat bubble: what is left of a streamed reply is the model's natural text.
 *
 * Three kinds of protocol text can reach the stream of a tool-calling Gemma:
 * - a control token (`<ctrl...>`), which the Gallery's executor drops (a chunk that starts with it);
 * - a tool call (`<|tool_call>call:run_intent{...}<tool_call|>`), which LiteRT-LM normally parses and runs itself (automatic tool
 *   calling) but which would show as text if one slipped through unparsed, a partly generated one included;
 * - a tool result echoed back (`<|tool_response>...<tool_response|>`).
 *
 * The span between a marker pair is dropped whole, even across chunks, and so is a closing marker left on its own. Text that only
 * might be the start of a marker is held back until the next chunk says which it is, so the bubble never flashes `<|tool`.
 * The markers are the model family's own protocol (data in [SPANS]), not words of any language.
 *
 * One instance per reply; not thread-safe (a reply streams on one thread).
 */
internal class ReplyTextFilter {

    private val held = StringBuilder()

    /** The marker whose closing we are waiting for, while inside a dropped span. */
    private var closing: String? = null

    /** The part of [chunk] (after anything held back) that belongs in the bubble. */
    fun accept(chunk: String): String {
        if (chunk.startsWith(CONTROL_TOKEN_PREFIX)) return ""
        held.append(chunk)
        return drain(final = false)
    }

    /** The end of the reply: what was held back as a possible marker was ordinary text after all, unless a span is still open. */
    fun finish(): String = drain(final = true).also {
        held.clear()
        closing = null
    }

    private fun drain(final: Boolean): String {
        val out = StringBuilder()
        while (held.isNotEmpty()) {
            val end = closing
            if (end != null) {
                val at = held.indexOf(end)
                if (at < 0) {
                    // Still inside the span: keep only a tail that could be the start of its closing marker.
                    val keep = if (final) 0 else partialSuffix(held, listOf(end))
                    held.delete(0, held.length - keep)
                    return out.toString()
                }
                held.delete(0, at + end.length)
                closing = null
                continue
            }
            val open = nextMarker(held)
            if (open == null) {
                val keep = if (final) 0 else partialSuffix(held, ALL_MARKERS)
                out.append(held, 0, held.length - keep)
                held.delete(0, held.length - keep)
                return out.toString()
            }
            out.append(held, 0, open.index)
            held.delete(0, open.index + open.marker.length)
            closing = open.span?.closing
        }
        return out.toString()
    }

    private class Found(val index: Int, val marker: String, val span: Span?)

    /** The earliest marker in [text]: the opening of a span, or a closing marker left on its own (dropped, no span). */
    private fun nextMarker(text: CharSequence): Found? {
        var best: Found? = null
        for (span in SPANS) {
            val at = text.indexOf(span.opening)
            if (at >= 0 && (best == null || at < best.index)) best = Found(at, span.opening, span)
            val stray = text.indexOf(span.closing)
            if (stray >= 0 && (best == null || stray < best.index)) best = Found(stray, span.closing, null)
        }
        return best
    }

    /** How many characters at the end of [text] are a proper prefix of one of [markers] (they may become one with the next chunk). */
    private fun partialSuffix(text: CharSequence, markers: List<String>): Int {
        var longest = 0
        for (marker in markers) {
            val max = minOf(marker.length - 1, text.length)
            for (len in max downTo longest + 1) {
                if (marker.regionMatches(0, text.toString(), text.length - len, len)) {
                    longest = len
                    break
                }
            }
        }
        return longest
    }

    private class Span(val opening: String, val closing: String)

    private companion object {
        const val CONTROL_TOKEN_PREFIX = "<ctrl"

        /** The tool-call and tool-result delimiters of Gemma's chat template. */
        val SPANS = listOf(
            Span("<|tool_call>", "<tool_call|>"),
            Span("<|tool_response>", "<tool_response|>"),
        )

        val ALL_MARKERS = SPANS.flatMap { listOf(it.opening, it.closing) }
    }
}
