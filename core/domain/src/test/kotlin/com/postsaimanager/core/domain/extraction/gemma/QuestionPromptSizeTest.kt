package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The questions are part of every reading's prefill: their length is watched so that a wording change that grows them is seen. */
class QuestionPromptSizeTest {

    @Test
    @DisplayName("the questions with a summary stay lean")
    fun `the questions size`() {
        val text = QuestionPrompt.questions(withSummary = true)
        println("QUESTIONS_CHARS=${text.length}")
        assertThat(text.length).isLessThan(BUDGET)
    }

    private companion object {
        const val BUDGET = 10_000
    }
}
