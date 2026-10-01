package com.postsaimanager.feature.chat

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import org.junit.jupiter.api.Test

class FormChatTextsContextTest {

    private fun question(args: List<String>, fieldId: String? = "f1") =
        FormMessage(FormMessageKind.QUESTION, FormText.ASK_TEXT, args, fieldId = fieldId)

    @Test
    fun `a question names its section and page`() {
        assertThat(FormChatTexts.questionContext(question(listOf("Vorname", "Erziehungsberechtigte/r", "1"))))
            .isEqualTo("Erziehungsberechtigte/r · p.1")
    }

    @Test
    fun `a question without a section names only the page`() {
        assertThat(FormChatTexts.questionContext(question(listOf("Vorname", "", "2")))).isEqualTo("p.2")
    }

    @Test
    fun `older questions and other messages carry no context`() {
        assertThat(FormChatTexts.questionContext(question(listOf("Vorname")))).isNull()
        assertThat(FormChatTexts.questionContext(question(listOf("Vorname", "Kind", "1"), fieldId = null))).isNull()
        assertThat(FormChatTexts.questionContext(FormMessage(FormMessageKind.STATUS, FormText.UNDERSTANDING, listOf("a", "b", "c")))).isNull()
    }
}
