package com.postsaimanager.core.ai.litert

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The thought channel as think-tagged text for the app's parser. */
class ThoughtStreamTest {

    @Test
    @DisplayName("with thinking asked for, the thought text goes first and one closing tag precedes the first answer text")
    fun `thought then close then answer`() {
        val stream = ThoughtStream(enabled = true)
        val out = StringBuilder()

        out.append(stream.thought("Let me "))
        out.append(stream.thought("think."))
        stream.closeBefore("").also { out.append(it) } // no answer yet: nothing
        stream.closeBefore("The answer").also { out.append(it) }
        out.append("The answer")
        stream.closeBefore(" goes on").also { out.append(it) } // once only
        out.append(" goes on")

        assertThat(out.toString()).isEqualTo("Let me think.</think>The answer goes on")
    }

    @Test
    fun `a reply that thought nothing is still closed, because the parser starts inside the trace`() {
        val stream = ThoughtStream(enabled = true)
        assertThat(stream.thought(null)).isEmpty()
        assertThat(stream.closeBefore("Hello")).isEqualTo("</think>")
    }

    @Test
    fun `with thinking off nothing is added and the thought channel is ignored`() {
        val stream = ThoughtStream(enabled = false)
        assertThat(stream.thought("secret reasoning")).isEmpty()
        assertThat(stream.closeBefore("Hello")).isEmpty()
    }
}
