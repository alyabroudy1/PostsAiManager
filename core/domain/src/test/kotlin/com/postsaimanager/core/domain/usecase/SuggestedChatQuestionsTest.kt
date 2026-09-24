package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SuggestedChatQuestionsTest {

    private fun field(name: String, type: ExtractedFieldType = ExtractedFieldType.TEXT) = ExtractedData(
        id = "f-$name",
        documentId = "d1",
        fieldName = name,
        fieldValue = "value",
        fieldType = type,
        confidence = 0.9f,
    )

    @Test
    @DisplayName("standalone chat suggests exactly three cross-document questions")
    fun `standalone suggestions`() {
        val suggestions = SuggestedChatQuestions.forStandaloneChat()

        assertThat(suggestions).hasSize(3)
        assertThat(suggestions).containsExactly(
            "Which letters have a deadline coming up?",
            "What do I owe and to whom?",
            "Summarise my latest letter",
        )
    }

    @Test
    @DisplayName("a deadline field adds the deadline question")
    fun `deadline field adds deadline question`() {
        val suggestions = SuggestedChatQuestions.forDocument(
            listOf(field(UnderstandingToFields.DEADLINE, ExtractedFieldType.DEADLINE)),
        )

        assertThat(suggestions).contains(SuggestedChatQuestions.DEADLINE_QUESTION)
        assertThat(suggestions).contains(SuggestedChatQuestions.SUMMARISE_QUESTION)
        assertThat(suggestions).contains(SuggestedChatQuestions.NEXT_STEPS_QUESTION)
    }

    @Test
    @DisplayName("an amount field adds the amount question")
    fun `amount field adds amount question`() {
        val suggestions = SuggestedChatQuestions.forDocument(listOf(field(UnderstandingToFields.AMOUNT)))

        assertThat(suggestions).contains(SuggestedChatQuestions.AMOUNT_QUESTION)
    }

    @Test
    @DisplayName("both deadline and amount present yields all four questions, nothing dropped")
    fun `both fields present yields four questions`() {
        val suggestions = SuggestedChatQuestions.forDocument(
            listOf(
                field(UnderstandingToFields.DEADLINE, ExtractedFieldType.DEADLINE),
                field(UnderstandingToFields.AMOUNT),
            ),
        )

        assertThat(suggestions).hasSize(4)
        assertThat(suggestions).containsExactly(
            SuggestedChatQuestions.DEADLINE_QUESTION,
            SuggestedChatQuestions.AMOUNT_QUESTION,
            SuggestedChatQuestions.SUMMARISE_QUESTION,
            SuggestedChatQuestions.NEXT_STEPS_QUESTION,
        )
    }

    @Test
    @DisplayName("no special fields still yields at least three questions, not a sparse two")
    fun `no special fields falls back to a third question`() {
        val suggestions = SuggestedChatQuestions.forDocument(listOf(field("Sender Name", ExtractedFieldType.PERSON_NAME)))

        assertThat(suggestions.size).isAtLeast(3)
        assertThat(suggestions).containsAtLeast(
            SuggestedChatQuestions.SUMMARISE_QUESTION,
            SuggestedChatQuestions.NEXT_STEPS_QUESTION,
            SuggestedChatQuestions.SENDER_QUESTION,
        )
    }

    @Test
    @DisplayName("no fields at all (fresh scan) still yields starter questions")
    fun `empty fields still yields starter questions`() {
        val suggestions = SuggestedChatQuestions.forDocument(emptyList())

        assertThat(suggestions.size).isAtLeast(3)
        assertThat(suggestions).doesNotContain(SuggestedChatQuestions.DEADLINE_QUESTION)
        assertThat(suggestions).doesNotContain(SuggestedChatQuestions.AMOUNT_QUESTION)
    }
}
