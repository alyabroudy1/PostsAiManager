package com.postsaimanager.core.domain.settings

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.gemma.GemmaPrompt
import com.postsaimanager.core.domain.extraction.gemma.QuestionPrompt
import com.postsaimanager.core.domain.memory.SessionNotesFormat
import org.junit.jupiter.api.Test

/** The app language is a prompt input: the texts the AI writes follow it, and nothing is decided by keywords. */
class AppLanguagePromptTest {

    @Test
    fun `the summary question names the app language when there is one`() {
        assertThat(GemmaPrompt.summaryAsk("ar")).contains("in the language with the code \"ar\"")
        assertThat(GemmaPrompt.summaryAsk("ar")).doesNotContain("the language the document is written in")
    }

    @Test
    fun `the summary question keeps the document's language without an app language`() {
        assertThat(GemmaPrompt.summaryAsk(null)).isEqualTo(GemmaPrompt.SUMMARY_ASK)
        assertThat(GemmaPrompt.summaryAsk("  ")).isEqualTo(GemmaPrompt.SUMMARY_ASK)
    }

    @Test
    fun `the questions reader asks its summary line in the app language`() {
        assertThat(QuestionPrompt.questions(withSummary = true, summaryLanguage = "de")).contains("in the language with the code \"de\"")
        assertThat(QuestionPrompt.questions(withSummary = true)).contains("in the language of the letter")
    }

    @Test
    fun `session notes are written in the app language, else in the language the user wrote in`() {
        assertThat(SessionNotesFormat.prompt(emptyList(), emptyList(), languageCode = "de")).contains("in the language with the code \"de\"")
        assertThat(SessionNotesFormat.prompt(emptyList(), emptyList())).contains("in the language the user wrote in")
    }
}
