package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
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
        assertThat(GemmaPrompt.SUMMARY_ASK).contains("at most ${com.postsaimanager.core.domain.extraction.text.SummaryLimits.MAX_CHARS} characters")
        assertThat(GemmaPrompt.system(imageOnly = false, summaryFirst = true)).contains("plain text")
        assertThat(GemmaPrompt.system(imageOnly = false)).doesNotContain("plain text")
    }

    @Test
    @DisplayName("asking the reader anything includes attending an appointment, so an appointment reminder is answered yes")
    fun `asks reader explanation`() {
        assertThat(prompt).contains("attend or be present at an appointment, bring something, pay, reply, send or sign")
        assertThat(prompt).contains("a reminder of an appointment to attend too")
    }

    @Test
    @DisplayName("every category has a one-line description in the data, and the prompt shows it next to the category's code")
    fun `category descriptions`() {
        DocCategory.DEFAULT.forEach { assertThat(it.description).isNotEmpty() }

        DocCategory.DEFAULT.forEach {
            assertThat(prompt).contains("- ${vocab.categoryCodes.codeOf(it.id)}: ${it.description}")
        }
        assertThat(prompt).contains(": a receipt, a till slip or a confirmation of a payment that was already paid")
        assertThat(prompt).contains(": a reminder or confirmation of a date to attend")
        assertThat(prompt).contains(": a request to pay for goods or services")
        assertThat(prompt).contains(": an official decision or notice")
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
        assertThat(fields.indexOf("- c:")).isLessThan(fields.indexOf("- sender:"))
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
        GemmaSchema.PARTIES.forEach { assertThat(prompt).contains("${vocab.partyRoleCodes.codeOf(it)} = $it") }
        vocab.partyKinds.forEach { assertThat(prompt).contains("${vocab.partyKindCodes.codeOf(it)} = $it") }
        vocab.referenceKinds.forEach { assertThat(prompt).contains("${vocab.referenceKindCodes.codeOf(it)} = $it") }
        vocab.actionKinds.forEach { assertThat(prompt).contains("- ${vocab.actionKindCodes.codeOf(it.id)}: ${it.task}") }
        vocab.dateMeanings.forEach { assertThat(prompt).contains("- ${vocab.dateMeaningCodes.codeOf(it.id)}: ${it.description}") }
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
        EventKinds.DEFAULT.scored.forEach { assertThat(prompt).contains("- ${vocab.eventKindCodes.codeOf(it.id)}: ${it.description}") }
        assertThat(prompt).contains("- ${vocab.eventKindCodes.codeOf("information")}: none of these")
    }

    @Test
    @DisplayName("an amount the letter does not describe is other, and the amount to pay is one field of its own, not a meaning of every amount")
    fun `amount meanings are the letter's`() {
        assertThat(prompt).contains("a unit price")
        assertThat(prompt).contains("- t: the one amount candidate the reader has to pay")
        val meanings = prompt.substringAfter("AMOUNT MEANINGS:").substringBefore("KIND OF REFERENCE")
        assertThat(meanings).doesNotContain("the amount the reader has to pay")
        vocab.listedAmountMeanings.forEach { assertThat(meanings).contains("- ${vocab.amountMeaningCodes.codeOf(it.id)}: ${it.description}") }
        // A picture-only reading has no amount field to point at, so it keeps the meaning in the list.
        assertThat(GemmaPrompt.user(GemmaLetter(emptyList(), emptyList()))).contains("the amount the reader has to pay")
    }

    @Test
    @DisplayName("a line carries no position: its zone is written once for the lines that share it, and a page starts with its own marker")
    fun `lines have no position column`() {
        val letter = GemmaLetter(
            lines = listOf(
                GemmaLine("L1", 1, "letterhead", 0.12f, 0.05f, "Jobcenter Musterstadt"),
                GemmaLine("L2", 1, "letterhead", 0.12f, 0.07f, "Musterstraße 1"),
                GemmaLine("L3", 1, "body", 0.12f, 0.40f, "wir bestätigen"),
                GemmaLine("L4", 2, "body", 0.12f, 0.10f, "Seite zwei"),
            ),
            candidates = emptyList(),
        )

        val text = GemmaPrompt.user(letter)

        assertThat(text).doesNotContain("x0.12")
        assertThat(text).doesNotContain("p1 ")
        assertThat(text).contains("[letterhead]\nL1 Jobcenter Musterstadt\nL2 Musterstraße 1\n[body]\nL3 wir bestätigen\n[page 2]\n[body]\nL4 Seite zwei\n")
        assertThat(text.split("[letterhead]").size - 1).isEqualTo(1)
    }

    @Test
    @DisplayName("a candidate's printed text is not repeated when it is the whole line it sits on, or the same as an earlier candidate")
    fun `candidate text is deduplicated`() {
        val letter = GemmaLetter(
            lines = listOf(
                GemmaLine("L1", 1, "letterhead", 0f, 0f, "Jobcenter Musterstadt"),
                GemmaLine("L2", 1, "body", 0f, 0f, "Antrag eingegangen am 01.09.2026"),
            ),
            candidates = listOf(
                GemmaCandidate("M1", CandidateKind.NAME, "Jobcenter Musterstadt", "Jobcenter Musterstadt", "", "L1"),
                GemmaCandidate("D1", CandidateKind.DATE, "01.09.2026", "2026-09-01", "eingegangen", "L2"),
                GemmaCandidate("D2", CandidateKind.DATE, "01.09.2026", "2026-09-01", "", "L2"),
            ),
        )

        val text = GemmaPrompt.user(letter)

        assertThat(text).contains("M1 | name | = |  | L1")
        assertThat(text).contains("D1 | date | 01.09.2026 | eingegangen | L2")
        assertThat(text).contains("D2 | date | =D1 |  | L2")
    }
}
