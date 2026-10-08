package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.LabelValuePairs
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.BlockZones
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The Jobcenter letter's party choices (passes 30 and 31: the sender and the addressee vanished from the choices). */
class GemmaPartyChoicesTest {

    private val pages = listOf(DeviceLetters.jobcenterBlocks)
    private val layout = LetterLayoutAnalyzer.analyze(pages)
    private val pairs = LabelValuePairs.of(pages, BlockZones.of(pages, layout))
    private val letter = GemmaLetterBuilder.build(layout, OfferedCandidates(emptyList()), labelPairs = pairs)

    @Test
    @DisplayName("the sender and the addressee stay offered, only the label is not")
    fun `parties offered`() {
        val offered = GemmaSchema.partyIds(letter).mapNotNull { id -> letter.line(id)?.text }
        // Only reference-block lines are ever labels, never a letterhead, return line, address window or signature line.
        assertThat(letter.lines.filter { it.isLabel }.map { it.zone }.toSet()).containsExactly("info-block")

        assertThat(offered).contains("Jobcenter Musterstadt")
        assertThat(offered).contains("Maria Mustermann")
        assertThat(offered).doesNotContain("Ansprechpartnerin")
    }
}
