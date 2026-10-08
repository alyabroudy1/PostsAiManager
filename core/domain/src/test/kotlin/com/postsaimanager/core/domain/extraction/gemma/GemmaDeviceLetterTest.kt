package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.LabelValuePairs
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.BlockZones
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The invented Jobcenter letter exactly as the phone's OCR read it (blocks and boxes dumped from the device, pass 32). */
class GemmaDeviceLetterTest {

    private val blocks: List<OcrBlock> = checkNotNull(javaClass.getResourceAsStream("/ocr/jobcenter-device.txt")).bufferedReader().readLines()
        .filter { it.isNotBlank() }
        .map { row ->
            val (box, text) = row.split('|', limit = 2)
            val (l, t, r, b) = box.split(',').map { it.toFloat() }
            OcrBlock(text.replace("<NL>", "\n"), TextBounds(l, t, r, b), 0.9f)
        }

    private val pages = listOf(blocks)
    private val layout = LetterLayoutAnalyzer.analyze(pages)
    private val ordered = BlockZones.inReadingOrder(pages, layout)
    private val pairs = LabelValuePairs.of(ordered, BlockZones.of(ordered, layout))
    private val letter = GemmaLetterBuilder.build(layout, OfferedCandidates(emptyList()), labelPairs = pairs)

    @Test
    @DisplayName("on the phone's OCR the reference block's labels are found, the parties stay offered")
    fun `labels on the device letter`() {
        val labels = letter.lines.filter { it.isLabel }.map { it.text }
        println("DEVICE LINES: " + letter.lines.joinToString(" | ") { "${it.id}[${it.zone}${if (it.isLabel) ",LABEL" else ""}]=${it.text}" })
        println("DEVICE PAIRS: $pairs")

        assertThat(labels).containsAtLeast("BG-Nummer", "Ansprechpartnerin", "Telefon", "E-Mail", "Datum")
        val offered = GemmaSchema.partyIds(letter).mapNotNull { letter.line(it)?.text }
        assertThat(offered).containsAtLeast("Jobcenter Musterstadt", "Maria Mustermann", "Frau Nadine Beispiel")
        assertThat(offered).doesNotContain("Ansprechpartnerin")
        assertThat(letter.candidatesOf(CandidateKind.NAME).map { it.raw }).doesNotContain("Ansprechpartnerin")
    }
}
