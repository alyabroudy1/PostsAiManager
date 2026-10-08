package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocCategory
import com.postsaimanager.core.domain.timeline.EventKinds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** What the reader is told: every code comes with the registry's own sentence, so the model never has to guess what a code means. */
class GemmaPromptTest {

    private val vocab = GemmaVocabulary.DEFAULT
    private val mini = MiniLetter()
    private val prompt = GemmaPrompt.user(mini.letter)

    @Test
    @DisplayName("the two turns split the same text: the letter and the summary question, then the field guide")
    fun `turns`() {
        val turns = GemmaPrompt.turns(mini.letter)

        assertThat(turns.first).contains("LINES (id")
        assertThat(turns.first).endsWith(GemmaPrompt.SUMMARY_ASK)
        assertThat(turns.first).doesNotContain("ANSWER: one JSON object")
        assertThat(turns.second).startsWith("Now the details")
        assertThat(turns.second).contains("ANSWER: one JSON object")
        assertThat(turns.second).doesNotContain("LINES (id")
        assertThat(GemmaPrompt.SUMMARY_ASK).contains("at most 160 characters")
        assertThat(GemmaPrompt.system(imageOnly = false, summaryFirst = true)).contains("plain text")
        assertThat(GemmaPrompt.system(imageOnly = false)).doesNotContain("plain text")
    }

    @Test
    @DisplayName("asking the reader anything includes attending an appointment, so an appointment reminder is answered yes")
    fun `asks reader explanation`() {
        assertThat(prompt).contains("attend or be present at an appointment, bring something, pay, reply, send or sign")
        assertThat(prompt).contains("A reminder of an appointment the reader must attend is yes")
    }

    @Test
    @DisplayName("every category has a one-line description in the data, and the prompt shows it next to the category's code")
    fun `category descriptions`() {
        DocCategory.DEFAULT.forEach { assertThat(it.description).isNotEmpty() }

        DocCategory.DEFAULT.forEach {
            assertThat(prompt).contains("- ${vocab.categoryCodes.codeOf(it.id)} = ${it.id}: ${it.description}")
        }
        assertThat(prompt).contains("= receipt: a receipt, a till slip or a confirmation of a payment that was already paid")
        assertThat(prompt).contains("= appointment: a reminder or confirmation of a date to attend")
        assertThat(prompt).contains("= bill: a request to pay for goods or services")
        assertThat(prompt).contains("= notice: an official decision or notice")
        assertThat(prompt).contains("- ${vocab.categoryCodes.codeOf("document")}: none of these")
    }

    @Test
    @DisplayName("a category without a description of its own falls back to its phrase")
    fun `fallback to the phrase`() {
        val bare = DocCategory("odd", "an odd thing", listOf("official_letter"))

        assertThat(bare.promptLine).isEqualTo("an odd thing")
    }

    @Test
    @DisplayName("the question whether the document asks its reader to do anything comes first, then whether it was paid, then the category")
    fun `asks reader and paid come before the category`() {
        val fields = prompt.substringAfter("ANSWER:")

        assertThat(fields.indexOf("- a:")).isLessThan(fields.indexOf("- p:"))
        assertThat(fields.indexOf("- p:")).isLessThan(fields.indexOf("- c:"))
        assertThat(fields.indexOf("- c:")).isLessThan(fields.indexOf("- r:"))
        assertThat(fields).contains("asks nothing")
    }

    @Test
    @DisplayName("the paid question names its three answers with their sentences, and a till slip or a confirmation is already paid")
    fun `paid answers`() {
        PaidState.entries.forEach { assertThat(prompt).contains("${it.id}: ${it.sentence}") }
        assertThat(prompt).contains("a till slip")
        assertThat(prompt).contains("nothing to pay")
    }

    @Test
    @DisplayName("the keys and codes are explained once: who, kinds of party, and every list's codes with their words")
    fun `codes are explained`() {
        assertThat(prompt).contains("WHO")
        GemmaSchema.PARTIES.forEach { assertThat(prompt).contains("- ${vocab.partyRoleCodes.codeOf(it)}: $it") }
        vocab.partyKinds.forEach { assertThat(prompt).contains("- ${vocab.partyKindCodes.codeOf(it)} = $it") }
        vocab.actionKinds.forEach { assertThat(prompt).contains("- ${vocab.actionKindCodes.codeOf(it.id)} = ${it.id}: ${it.task}") }
        vocab.dateMeanings.forEach { assertThat(prompt).contains("- ${vocab.dateMeaningCodes.codeOf(it.id)} = ${it.id}: ${it.description}") }
    }

    @Test
    @DisplayName("the summary and the key facts are not asked for a letter with text: a second step writes them")
    fun `no summary asked`() {
        assertThat(prompt).doesNotContain("- s:")
        assertThat(prompt).doesNotContain("- y:")
        // A picture-only reading has no text for a second step, so it is asked for them.
        val image = GemmaPrompt.user(GemmaLetter(emptyList(), emptyList()))
        assertThat(image).contains("- s:")
        assertThat(image).contains("- y:")
    }

    @Test
    @DisplayName("the event kinds come from the timeline registry with their sentences, and information is the answer for none")
    fun `event kinds`() {
        EventKinds.DEFAULT.scored.forEach { assertThat(prompt).contains("- ${vocab.eventKindCodes.codeOf(it.id)} = ${it.id}: ${it.description}") }
        assertThat(prompt).contains("- ${vocab.eventKindCodes.codeOf("information")}: none of these")
    }

    @Test
    @DisplayName("an amount the letter does not describe is other, and only one amount is the amount to pay")
    fun `amount meanings are the letter's`() {
        assertThat(prompt).contains("a unit price")
        assertThat(prompt).contains("Only one value can be the amount to pay")
    }
}
