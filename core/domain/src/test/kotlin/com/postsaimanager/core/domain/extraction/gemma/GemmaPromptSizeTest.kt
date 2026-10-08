package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.CandidateTable
import com.postsaimanager.core.domain.extraction.v2.ExtractorCandidateSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What the reader's input costs: the characters of the prompt (the two messages and the system text) and of the answer's schema for the
 * Jobcenter letter, printed so a change of the prompt's size is seen (the phone prefills 60 to 85 tokens a second and decodes about one
 * token per character of the answer), and held under a budget so it does not grow back.
 */
class GemmaPromptSizeTest {

    @Test
    @DisplayName("the Jobcenter letter's prompt stays small: no position column, one zone per group, no repeated candidate text, one field for the amount to pay")
    fun `prompt size`() {
        val pages = listOf(DeviceLetters.jobcenterBlocks)
        val layout = LetterLayoutAnalyzer.analyze(pages)
        val offered = CandidateTable.build(ExtractorCandidateSource().find(pages, layout))
        val letter = GemmaLetterBuilder.build(layout, offered)

        val turns = GemmaPrompt.turns(letter)
        val system = GemmaPrompt.system(imageOnly = false, summaryFirst = true)
        val schema = GemmaSchema.build(letter)
        val letterPart = turns.first.length
        val guide = turns.second.length

        println("PROMPT-SIZE lines=${letter.lines.size} candidates=${letter.candidates.size} system=${system.length} letterTurn=$letterPart guideTurn=$guide schema=${schema.length} total=${system.length + letterPart + guide}")
        assertThat(system.length + letterPart + guide).isLessThan(BUDGET_CHARS)
        // The letter's own part (the lines and the candidates) is where the position column and the repeated texts were.
        assertThat(letterPart).isLessThan(LETTER_TURN_BUDGET_CHARS)
    }

    private companion object {
        /** Before the diet this letter's prompt was 9206 characters (system 806, letter turn 2526, guide turn 5874); now 7.4k. */
        const val BUDGET_CHARS = 8_600

        /** The letter turn was 2526 characters (the zone word of each candidate's line is the one column added since). */
        const val LETTER_TURN_BUDGET_CHARS = 2_200
    }
}
