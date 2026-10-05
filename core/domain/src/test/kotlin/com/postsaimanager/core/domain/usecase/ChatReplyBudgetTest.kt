package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ThinkingEffort
import org.junit.jupiter.api.Test

class ChatReplyBudgetTest {

    @Test
    fun `the answer keeps at least 512 tokens at every effort and context size`() {
        for (context in listOf(1024, 2048, 4096, 8192, 32768)) {
            for (effort in ThinkingEffort.entries) {
                val budget = ChatReplyBudget.forEffort(effort, context)
                assertThat(budget.answerTokens).isAtLeast(ChatReplyBudget.MIN_ANSWER_TOKENS)
            }
        }
    }

    @Test
    fun `cap is thinking budget plus 768 answer tokens on a roomy window`() {
        assertThat(ChatReplyBudget.forEffort(ThinkingEffort.OFF, 4096)).isEqualTo(ChatReplyBudget.Budget(1024, 0))
        assertThat(ChatReplyBudget.forEffort(ThinkingEffort.LOW, 4096)).isEqualTo(ChatReplyBudget.Budget(1024, 256))
        assertThat(ChatReplyBudget.forEffort(ThinkingEffort.HIGH, 4096)).isEqualTo(ChatReplyBudget.Budget(1792, 1024))
    }

    @Test
    fun `cap is bounded by the context window and the thinking budget shrinks to fit`() {
        val budget = ChatReplyBudget.forEffort(ThinkingEffort.HIGH, 2048)
        assertThat(budget.maxTokens).isAtMost(1024)
        assertThat(budget.thinkingBudgetTokens).isEqualTo(budget.maxTokens - ChatReplyBudget.MIN_ANSWER_TOKENS)
    }

    @Test
    fun `thinking off requests no thinking budget`() {
        assertThat(ChatReplyBudget.forEffort(ThinkingEffort.OFF, 4096).thinkingBudgetTokens).isEqualTo(0)
        assertThat(ChatReplyBudget.request(ThinkingEffort.OFF, 4096).thinkingEnabled).isFalse()
    }

    @Test
    fun `sampling follows the Qwen3_5 card per mode`() {
        val off = ChatReplyBudget.sampling(ThinkingEffort.OFF)
        assertThat(off).isEqualTo(ChatReplyBudget.Sampling(1.0f, 1.0f, 20, 2.0f))
        val thinking = ChatReplyBudget.sampling(ThinkingEffort.LOW)
        assertThat(thinking).isEqualTo(ChatReplyBudget.Sampling(1.0f, 0.95f, 20, 1.5f))
        assertThat(ChatReplyBudget.sampling(ThinkingEffort.HIGH)).isEqualTo(thinking)
    }
}
