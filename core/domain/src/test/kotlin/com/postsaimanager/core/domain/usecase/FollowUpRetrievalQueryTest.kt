package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FollowUpRetrievalQueryTest {

    @Test
    fun `first message has no previous turn so the query is unchanged`() {
        val query = FollowUpRetrievalQuery.build("and when is it due?", previousUserText = null)
        assertThat(query).isEqualTo("and when is it due?")
    }

    @Test
    fun `a short follow-up is prefixed with the previous user message`() {
        val query = FollowUpRetrievalQuery.build(
            text = "and when is it due?",
            previousUserText = "What does the letter from the Finanzamt say about my appeal?",
        )
        assertThat(query).isEqualTo(
            "What does the letter from the Finanzamt say about my appeal? and when is it due?",
        )
    }

    @Test
    fun `a long, self-contained question is left alone`() {
        val longQuestion = "What is the exact deadline mentioned in the letter about my tax appeal?"
        val query = FollowUpRetrievalQuery.build(longQuestion, previousUserText = "Hi there")
        assertThat(query).isEqualTo(longQuestion)
    }

    @Test
    fun `word count alone can trigger the follow-up even under the character threshold`() {
        // Fewer than 6 words but not under 40 chars either — still a follow-up shape.
        val query = FollowUpRetrievalQuery.build(
            text = "what about the second one?",
            previousUserText = "Tell me about the invoices",
        )
        assertThat(query).isEqualTo("Tell me about the invoices what about the second one?")
    }

    @Test
    fun `a blank previous message is treated as no previous message`() {
        val query = FollowUpRetrievalQuery.build("and when is it due?", previousUserText = "  ")
        assertThat(query).isEqualTo("and when is it due?")
    }
}
