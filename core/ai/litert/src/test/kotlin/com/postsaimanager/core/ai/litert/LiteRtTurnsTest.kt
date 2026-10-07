package com.postsaimanager.core.ai.litert

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.model.ToolExchange
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

    private val loadSkill = ToolExchange("load_skill", """{"skill_name":"schedule-reminder"}""", """{"skill_instructions":"1. Do it."}""")
    private val runIntent = ToolExchange("run_intent", """{"intent":"schedule_notification"}""", """{"status":"proposed"}""")

    @Test
    @DisplayName("a model turn keeps the tool calls it made, in order, so the replay shows the call before the words")
    fun `tool calls stay with their turn`() {
        val turns = LiteRtTurns.from(
            listOf(
                user("remind me"),
                AiChatMessage(AiChatRole.ASSISTANT, "Prepared, check the card.", listOf(loadSkill, runIntent)),
                user("thanks"),
                model("Welcome."),
            ),
        )

        assertThat(turns[1]).isEqualTo(LiteRtTurn(false, "Prepared, check the card.", listOf(loadSkill, runIntent)))
        assertThat(turns[3].tools).isEmpty()
    }

    @Test
    fun `joined model turns keep the calls of both`() {
        val turns = LiteRtTurns.from(
            listOf(
                user("a"),
                AiChatMessage(AiChatRole.ASSISTANT, "x", listOf(loadSkill)),
                AiChatMessage(AiChatRole.ASSISTANT, "y", listOf(runIntent)),
            ),
        )

        assertThat(turns.single { !it.fromUser }.tools).containsExactly(loadSkill, runIntent).inOrder()
    }

    @Test
    @DisplayName("a turn's size for the budget counts its tool calls, not only its words")
    fun `prompt chars include the tool calls`() {
        val plain = LiteRtTurn(false, "ok")
        val withTools = LiteRtTurn(false, "ok", listOf(loadSkill))

        assertThat(withTools.promptChars).isEqualTo(plain.promptChars + loadSkill.promptChars)
    }

    @Test
    @DisplayName("overflow: the oldest whole turns go first and what stays still opens with the user")
    fun `compaction drops the oldest turns`() {
        val turns = listOf(
            LiteRtTurn(true, "q1".repeat(100)), LiteRtTurn(false, "a1".repeat(100), listOf(loadSkill)),
            LiteRtTurn(true, "q2".repeat(100)), LiteRtTurn(false, "a2".repeat(100)),
            LiteRtTurn(true, "q3"), LiteRtTurn(false, "a3"),
        )

        val kept = LiteRtTurns.compact(turns, budgetChars = 450)

        assertThat(kept.map { it.text.take(2) }).containsExactly("q2", "a2", "q3", "a3").inOrder()
        assertThat(kept.first().fromUser).isTrue()
    }

    @Test
    fun `compaction under the budget changes nothing`() {
        val turns = listOf(LiteRtTurn(true, "q"), LiteRtTurn(false, "a"))

        assertThat(LiteRtTurns.compact(turns, budgetChars = 10_000)).isEqualTo(turns)
    }

    @Test
    @DisplayName("the newest exchange stays even when it alone is over the budget")
    fun `compaction keeps the newest exchange`() {
        val turns = listOf(
            LiteRtTurn(true, "q1"), LiteRtTurn(false, "a1"),
            LiteRtTurn(true, "q2"), LiteRtTurn(false, "a2".repeat(5_000)),
        )

        val kept = LiteRtTurns.compact(turns, budgetChars = 100)

        assertThat(kept.map { it.fromUser }).containsExactly(true, false).inOrder()
        assertThat(kept.first().text).isEqualTo("q2")
    }

    @Test
    fun `the rebuild budget is a share of the window in characters`() {
        assertThat(LiteRtTurns.rebuildBudgetChars(8192)).isEqualTo((8192 * 0.4).toInt() * 3)
    }

    @Test
    @DisplayName("German and Arabic text goes through untouched")
    fun `text is not altered`() {
        val turns = LiteRtTurns.from(listOf(user("Wie hoch ist die Gebühr, Straße?"), model("الرسوم ٢٥ يورو")))

        assertThat(turns.map { it.text }).containsExactly("Wie hoch ist die Gebühr, Straße?", "الرسوم ٢٥ يورو").inOrder()
    }
}
