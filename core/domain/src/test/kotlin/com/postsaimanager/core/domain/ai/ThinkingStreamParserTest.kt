package com.postsaimanager.core.domain.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [ThinkingStreamParser] is the one place a reasoning model's `<think>…</think>` block is
 * separated from its answer — every other layer (persistence, prompt history, UI) trusts
 * this split rather than re-deriving it, so it is exercised chunk-by-chunk the way a real
 * token stream actually arrives: in small, tag-splitting pieces, not one string at a time.
 */
class ThinkingStreamParserTest {

    private fun consumeAll(parser: ThinkingStreamParser, chunks: List<String>): List<StreamSegment> =
        chunks.flatMap { parser.consume(it) } + parser.finish()

    private fun thinkingText(segments: List<StreamSegment>) =
        segments.filterIsInstance<StreamSegment.Thinking>().joinToString("") { it.delta }

    private fun answerText(segments: List<StreamSegment>) =
        segments.filterIsInstance<StreamSegment.Answer>().joinToString("") { it.delta }

    @Test
    @DisplayName("a model with no think tags produces only an answer")
    fun `no tags means everything is answer`() {
        val parser = ThinkingStreamParser()
        val segments = consumeAll(parser, listOf("The sky ", "is blue because of Rayleigh scattering."))

        assertThat(thinkingText(segments)).isEmpty()
        assertThat(answerText(segments)).isEqualTo("The sky is blue because of Rayleigh scattering.")
    }

    @Test
    @DisplayName("a whole think block in one chunk splits cleanly")
    fun `single chunk with a full think block`() {
        val parser = ThinkingStreamParser()
        val segments = consumeAll(
            parser,
            listOf("<think>Let me reason about this.</think>\n\nThe answer is 42."),
        )

        assertThat(thinkingText(segments)).isEqualTo("Let me reason about this.")
        assertThat(answerText(segments)).isEqualTo("The answer is 42.")
    }

    @Test
    @DisplayName("the opening tag split across many chunk boundaries is still recognised")
    fun `open tag split across chunks`() {
        val parser = ThinkingStreamParser()
        val segments = consumeAll(
            parser,
            listOf("<", "th", "in", "k", ">", "reasoning", "</think>", "answer"),
        )

        assertThat(thinkingText(segments)).isEqualTo("reasoning")
        assertThat(answerText(segments)).isEqualTo("answer")
    }

    @Test
    @DisplayName("the closing tag split across many chunk boundaries is still recognised")
    fun `close tag split across chunks`() {
        val parser = ThinkingStreamParser()
        val segments = consumeAll(
            parser,
            listOf("<think>", "reasoning", "<", "/", "th", "ink", ">", "answer"),
        )

        assertThat(thinkingText(segments)).isEqualTo("reasoning")
        assertThat(answerText(segments)).isEqualTo("answer")
    }

    @Test
    @DisplayName("tag text split at every single character never leaks a partial tag as content")
    fun `tag split one character at a time`() {
        val parser = ThinkingStreamParser()
        val full = "<think>abc</think>xyz"
        val segments = consumeAll(parser, full.map { it.toString() })

        assertThat(thinkingText(segments)).isEqualTo("abc")
        assertThat(answerText(segments)).isEqualTo("xyz")
    }

    @Test
    @DisplayName("a stream that ends inside an unclosed think block is entirely thinking")
    fun `unclosed think block at end of stream`() {
        val parser = ThinkingStreamParser()
        val segments = consumeAll(parser, listOf("<think>still reasoning when the stream stopped"))

        assertThat(thinkingText(segments)).isEqualTo("still reasoning when the stream stopped")
        assertThat(answerText(segments)).isEmpty()
    }

    @Test
    @DisplayName("a second open tag before the first close is treated as ordinary thinking text")
    fun `nested open tag is not special`() {
        val parser = ThinkingStreamParser()
        val segments = consumeAll(
            parser,
            listOf("<think>first <think> nested thought</think>answer"),
        )

        assertThat(thinkingText(segments)).isEqualTo("first <think> nested thought")
        assertThat(answerText(segments)).isEqualTo("answer")
    }

    @Test
    @DisplayName("whitespace right after the closing tag is trimmed once, not from later content")
    fun `whitespace after close tag is trimmed exactly once`() {
        val parser = ThinkingStreamParser()
        val segments = consumeAll(
            parser,
            listOf("<think>x</think>", "\n\n  ", "Answer with   inner space kept."),
        )

        assertThat(answerText(segments)).isEqualTo("Answer with   inner space kept.")
    }

    @Test
    @DisplayName("segments arrive incrementally rather than only at finish")
    fun `segments are emitted as soon as they are unambiguous`() {
        val parser = ThinkingStreamParser()

        // Nothing to say yet — "<thi" could still become "<think>".
        assertThat(parser.consume("<thi")).isEmpty()

        // Now it's unambiguously the open tag; still nothing to emit as content.
        assertThat(parser.consume("nk>reasoning")).containsExactly(StreamSegment.Thinking("reasoning"))

        // Mid-block content is emitted immediately, not held until the close tag.
        assertThat(parser.consume(" more")).containsExactly(StreamSegment.Thinking(" more"))

        assertThat(parser.consume("</think>done")).containsExactly(StreamSegment.Answer("done"))
    }

    @Test
    @DisplayName("empty chunks are a no-op")
    fun `empty chunk produces no segments`() {
        val parser = ThinkingStreamParser()
        assertThat(parser.consume("")).isEmpty()
    }

    // ── streams that begin inside an already-open think block ────────────────────────────

    @Test
    @DisplayName("startInThinking: text before </think> is thinking even without an opening tag")
    fun `start in thinking without an open tag`() {
        val parser = ThinkingStreamParser(startInThinking = true)
        val segments = consumeAll(parser, listOf("The user asks", " about tax.", "\n</think>", "\n\nIt is 1.48."))

        assertThat(thinkingText(segments)).isEqualTo("The user asks about tax.\n")
        assertThat(answerText(segments)).isEqualTo("It is 1.48.")
    }

    @Test
    @DisplayName("startInThinking: a closing tag split across chunks still ends the thinking")
    fun `start in thinking with split close tag`() {
        val parser = ThinkingStreamParser(startInThinking = true)
        val segments = consumeAll(parser, listOf("reasoning</", "thi", "nk", ">", "answer"))

        assertThat(thinkingText(segments)).isEqualTo("reasoning")
        assertThat(answerText(segments)).isEqualTo("answer")
    }

    @Test
    @DisplayName("startInThinking: a repeated <think> at the very start is swallowed, split or not")
    fun `start in thinking swallows a redundant open tag`() {
        val whole = consumeAll(ThinkingStreamParser(startInThinking = true), listOf("<think>\nhmm</think>ok"))
        assertThat(thinkingText(whole)).isEqualTo("\nhmm")
        assertThat(answerText(whole)).isEqualTo("ok")

        val split = consumeAll(
            ThinkingStreamParser(startInThinking = true),
            listOf("\n", "<", "thi", "nk>", "hmm", "</think>", "ok"),
        )
        assertThat(thinkingText(split)).isEqualTo("hmm")
        assertThat(answerText(split)).isEqualTo("ok")
    }

    @Test
    @DisplayName("startInThinking: text that merely starts with '<' is not mistaken for the tag")
    fun `start in thinking keeps a lookalike prefix`() {
        val segments = consumeAll(
            ThinkingStreamParser(startInThinking = true),
            listOf("<", "b>bold</b> so", "</think>", "done"),
        )

        assertThat(thinkingText(segments)).isEqualTo("<b>bold</b> so")
        assertThat(answerText(segments)).isEqualTo("done")
    }

    @Test
    @DisplayName("startInThinking: an immediate </think> yields an empty thought and a clean answer")
    fun `start in thinking with an empty block`() {
        val segments = consumeAll(ThinkingStreamParser(startInThinking = true), listOf("\n\n</think>\n\n", "Answer."))

        assertThat(thinkingText(segments).isBlank()).isTrue()
        assertThat(answerText(segments)).isEqualTo("Answer.")
    }

    @Test
    @DisplayName("startInThinking: a stream that never closes is all thinking")
    fun `start in thinking never closed`() {
        val segments = consumeAll(ThinkingStreamParser(startInThinking = true), listOf("still ", "thinking"))

        assertThat(thinkingText(segments)).isEqualTo("still thinking")
        assertThat(answerText(segments)).isEmpty()
    }
}
