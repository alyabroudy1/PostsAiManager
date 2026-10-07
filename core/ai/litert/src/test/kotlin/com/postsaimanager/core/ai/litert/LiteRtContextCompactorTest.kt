package com.postsaimanager.core.ai.litert

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ToolExchange
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The trigger, the summary request and the reset shape of the Gallery's compactor. The summary text comes from the model and is not asserted. */
class LiteRtContextCompactorTest {

    private val compactor = LiteRtContextCompactor()
    private val window = 8192

    @Test
    @DisplayName("the trigger is the Gallery's: strictly past 75% of the window")
    fun `threshold is 75 percent`() {
        assertThat(compactor.isOverThreshold(6144, window)).isFalse()
        assertThat(compactor.isOverThreshold(6145, window)).isTrue()
        assertThat(compactor.isOverThreshold(0, window)).isFalse()
        assertThat(compactor.isOverThreshold(5000, 0)).isFalse()
    }

    @Test
    fun `no compaction under the threshold`() {
        assertThat(compactor.shouldCompact(3000, window)).isFalse()
    }

    @Test
    fun `compaction over the threshold`() {
        assertThat(compactor.shouldCompact(7000, window)).isTrue()
    }

    @Test
    @DisplayName("after a failed summary the next three checks back off, then it tries again")
    fun `failure backs off for three checks`() {
        compactor.onFailure()

        assertThat(compactor.shouldCompact(7000, window)).isFalse()
        assertThat(compactor.shouldCompact(7000, window)).isFalse()
        assertThat(compactor.shouldCompact(7000, window)).isFalse()
        assertThat(compactor.shouldCompact(7000, window)).isTrue()
    }

    @Test
    fun `dropping under the threshold clears the back-off`() {
        compactor.onFailure()
        assertThat(compactor.shouldCompact(1000, window)).isFalse()

        assertThat(compactor.shouldCompact(7000, window)).isTrue()
    }

    @Test
    fun `a success clears the back-off`() {
        compactor.onFailure()
        compactor.onSuccess()

        assertThat(compactor.shouldCompact(7000, window)).isTrue()
    }

    @Test
    @DisplayName("the summary's word limit is what is left of the window in words, within 50 and 1000")
    fun `word limit is bounded`() {
        assertThat(compactor.wordLimit(usedTokens = 7000, windowTokens = window)).isEqualTo(((window - 7000) * 0.75).toInt())
        assertThat(compactor.wordLimit(usedTokens = 8190, windowTokens = window)).isEqualTo(50)
        assertThat(compactor.wordLimit(usedTokens = 100, windowTokens = window)).isEqualTo(1000)
    }

    @Test
    fun `the summary request is the Gallery's wording`() {
        assertThat(compactor.summaryPrompt(300)).isEqualTo(
            "Summarize the core points of our conversation so far in less than 300 words, ensuring no important context is lost.",
        )
    }

    private val call = ToolExchange("run_intent", """{"intent":"schedule_notification"}""", """{"status":"proposed"}""")

    @Test
    @DisplayName("the reset starts with the summary pair, then keeps the newest exchange with its tool calls")
    fun `reset shape`() {
        val committed = listOf(
            LiteRtTurn(true, "q1"), LiteRtTurn(false, "a1"),
            LiteRtTurn(true, "remind me"), LiteRtTurn(false, "prepared", listOf(call)),
        )

        val turns = compactor.turnsAfterSummary("SUMMARY", committed)

        assertThat(turns).containsExactly(
            LiteRtTurn(true, LiteRtContextCompactor.SUMMARY_INTRO),
            LiteRtTurn(false, "SUMMARY"),
            LiteRtTurn(true, "remind me"),
            LiteRtTurn(false, "prepared", listOf(call)),
        ).inOrder()
        assertThat(turns.map { it.fromUser }).containsExactly(true, false, true, false).inOrder()
    }

    @Test
    fun `reset with no committed exchange is the summary pair alone`() {
        assertThat(compactor.turnsAfterSummary("S", emptyList())).hasSize(2)
    }
}
