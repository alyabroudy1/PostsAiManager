package com.postsaimanager.core.ai.litert

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * How the app's history becomes the turns a LiteRT-LM conversation starts from. The model file's chat template applies the
 * format and refuses a conversation that does not alternate, so the sequence is all this decides.
 */
class LiteRtTurnsTest {

    private fun user(text: String) = AiChatMessage(AiChatRole.USER, text)
    private fun model(text: String) = AiChatMessage(AiChatRole.ASSISTANT, text)

    @Test
    fun `an alternating history is replayed as it is`() {
        val turns = LiteRtTurns.from(listOf(user("a"), model("b"), user("c"), model("d")))

        assertThat(turns).containsExactly(
            LiteRtTurn(true, "a"),
            LiteRtTurn(false, "b"),
            LiteRtTurn(true, "c"),
            LiteRtTurn(false, "d"),
        ).inOrder()
    }

    @Test
    @DisplayName("a question that was never answered (stopped, failed, cut off) is joined with the next one, not left as two user turns")
    fun `consecutive user turns are joined`() {
        val turns = LiteRtTurns.from(listOf(user("a"), model("b"), user("c"), user("d"), model("e")))

        assertThat(turns.map { it.fromUser }).containsExactly(true, false, true, false).inOrder()
        assertThat(turns[2].text).isEqualTo("c\n\nd")
    }

    @Test
    @DisplayName("a history that opens with the model (an earlier turn was deleted) starts at the first user turn")
    fun `leading model turns are dropped`() {
        val turns = LiteRtTurns.from(listOf(model("x"), model("y"), user("a"), model("b")))

        assertThat(turns).containsExactly(LiteRtTurn(true, "a"), LiteRtTurn(false, "b")).inOrder()
    }

    @Test
    @DisplayName("an unanswered last question is not replayed: the message about to be sent is the user's next turn")
    fun `trailing user turn is dropped`() {
        val turns = LiteRtTurns.from(listOf(user("a"), model("b"), user("c")))

        assertThat(turns).containsExactly(LiteRtTurn(true, "a"), LiteRtTurn(false, "b")).inOrder()
    }

    @Test
    fun `system turns and blank turns are not part of the replay`() {
        val turns = LiteRtTurns.from(
            listOf(AiChatMessage(AiChatRole.SYSTEM, "rules"), user("a"), model("  "), model("b")),
        )

        assertThat(turns).containsExactly(LiteRtTurn(true, "a"), LiteRtTurn(false, "b")).inOrder()
    }

    @Test
    fun `an empty history is an empty replay`() {
        assertThat(LiteRtTurns.from(emptyList())).isEmpty()
    }

    @Test
    @DisplayName("German and Arabic text goes through untouched")
    fun `text is not altered`() {
        val turns = LiteRtTurns.from(listOf(user("Wie hoch ist die Gebühr, Straße?"), model("الرسوم ٢٥ يورو")))

        assertThat(turns.map { it.text }).containsExactly("Wie hoch ist die Gebühr, Straße?", "الرسوم ٢٥ يورو").inOrder()
    }
}
