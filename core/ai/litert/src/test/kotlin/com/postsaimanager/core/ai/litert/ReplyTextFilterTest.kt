package com.postsaimanager.core.ai.litert

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** No tool protocol reaches the chat bubble: the answer is the model's natural text, the action is on the card. */
class ReplyTextFilterTest {

    /** Streams [chunks] through one filter, as a reply does, and returns what the bubble would show. */
    private fun bubble(vararg chunks: String): String {
        val filter = ReplyTextFilter()
        return chunks.joinToString("") { filter.accept(it) } + filter.finish()
    }

    @Test
    fun `plain text passes through untouched, chunk by chunk`() {
        assertThat(bubble("Ich habe ", "den Entwurf ", "vorbereitet.")).isEqualTo("Ich habe den Entwurf vorbereitet.")
    }

    @Test
    fun `a control token chunk is dropped`() {
        assertThat(bubble("Hello", "<ctrl99>", " world")).isEqualTo("Hello world")
    }

    @Test
    fun `a whole tool call in one chunk is dropped and the text around it kept`() {
        val text = bubble("Sure. <|tool_call>call:run_intent{intent:<|\"|>send_email<|\"|>}<tool_call|>Done.")

        assertThat(text).isEqualTo("Sure. Done.")
    }

    @Test
    @DisplayName("a tool call split over many chunks, even inside its markers, never shows")
    fun `a tool call split across chunks is dropped`() {
        val text = bubble("Okay <|to", "ol_ca", "ll>call:load_skill{skill_name:<|\"|>send-email<|\"|>}<tool", "_call|> next")

        assertThat(text).isEqualTo("Okay  next")
    }

    @Test
    fun `a tool result echoed back is dropped`() {
        assertThat(bubble("<|tool_response>{\"status\":\"proposed\"}<tool_response|>The draft is ready.")).isEqualTo("The draft is ready.")
    }

    @Test
    fun `a closing marker left alone is dropped`() {
        assertThat(bubble("The draft is ready.<tool_call|>")).isEqualTo("The draft is ready.")
    }

    @Test
    @DisplayName("text that only looks like the start of a marker is shown once the stream shows it is ordinary")
    fun `a lookalike is released`() {
        assertThat(bubble("3 <", " 5")).isEqualTo("3 < 5")
        assertThat(bubble("a <|t", "able> b")).isEqualTo("a <|table> b")
    }

    @Test
    @DisplayName("the chunks recorded on the phone for a date answer (one character each) come out as the date, unchanged")
    fun `a date streamed one character at a time is not altered`() {
        val recorded = listOf("3", "0", ".", "1", "1", ".", "2", "0", "2", "6")

        assertThat(bubble(*recorded.toTypedArray())).isEqualTo("30.11.2026")
        // Words around it, as in "I have tried to add the deadline ... for the Nordstern letter."
        assertThat(bubble("I", " have", " tried", "()", " to", " add", " the", " deadline", " Nord", "stern", " letter", "."))
            .isEqualTo("I have tried() to add the deadline Nordstern letter.")
    }

    @Test
    fun `a held lookalike at the very end is flushed`() {
        assertThat(bubble("x <|tool")).isEqualTo("x <|tool")
    }

    @Test
    fun `a tool call cut off by the end of the stream leaves nothing behind`() {
        assertThat(bubble("Hi <|tool_call>call:run_intent{intent:")).isEqualTo("Hi ")
    }

    @Test
    fun `a held prefix is not shown before the next chunk decides`() {
        val filter = ReplyTextFilter()

        assertThat(filter.accept("Hello <|tool")).isEqualTo("Hello ")
        assertThat(filter.accept("_call>x<tool_call|>!")).isEqualTo("!")
    }

    @Test
    fun `Arabic and German text pass through`() {
        assertThat(bubble("جهزت لك ", "مسودة البريد. ", "Grüße")).isEqualTo("جهزت لك مسودة البريد. Grüße")
    }
}
