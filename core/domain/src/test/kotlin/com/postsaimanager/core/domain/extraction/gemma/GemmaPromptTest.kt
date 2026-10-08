package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocCategory
import com.postsaimanager.core.domain.timeline.EventKinds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** What the reader is told: every list word comes with the registry's own sentence, so the model never has to guess what a word means. */
class GemmaPromptTest {

    private val mini = MiniLetter()
    private val prompt = GemmaPrompt.user(mini.letter)

    @Test
    @DisplayName("every category has a one-line description in the data, and the prompt shows it next to the category's id")
    fun `category descriptions`() {
        DocCategory.DEFAULT.forEach { assertThat(it.description).isNotEmpty() }

        assertThat(prompt).contains("- receipt: proof of a payment already made")
        assertThat(prompt).contains("- appointment: a reminder or confirmation of a date to attend")
        assertThat(prompt).contains("- bill: a request to pay for goods or services")
        assertThat(prompt).contains("- notice: an official decision or notice")
        DocCategory.DEFAULT.forEach { assertThat(prompt).contains("- ${it.id}: ${it.description}") }
        assertThat(prompt).contains("- document: none of these")
    }

    @Test
    @DisplayName("a category without a description of its own falls back to its phrase")
    fun `fallback to the phrase`() {
        val bare = DocCategory("odd", "an odd thing", listOf("official_letter"))

        assertThat(bare.promptLine).isEqualTo("an odd thing")
    }

    @Test
    @DisplayName("the question whether the document asks its reader to do anything comes first among the fields, before the category")
    fun `asks reader is the first field`() {
        val fields = prompt.substringAfter("FIELDS")

        assertThat(fields.indexOf("- asksReader:")).isLessThan(fields.indexOf("- category:"))
        assertThat(fields.indexOf("- category:")).isLessThan(fields.indexOf("- sender, addressee"))
        assertThat(fields).contains("asks nothing")
    }

    @Test
    @DisplayName("the event kinds come from the timeline registry with their sentences, and information is the answer for none")
    fun `event kinds`() {
        EventKinds.DEFAULT.scored.forEach { assertThat(prompt).contains("- ${it.id}: ${it.description}") }
        assertThat(prompt).contains("- information: none of these")
    }

    @Test
    @DisplayName("an amount the letter does not describe is other, and only one amount is the amount to pay")
    fun `amount meanings are the letter's`() {
        assertThat(prompt).contains("a unit price")
        assertThat(prompt).contains("Only one value can be the amount to pay")
    }
}
