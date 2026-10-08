package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.LabelValuePairs
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.BlockZones
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The contact on the Jobcenter letter (pass 29: "Ansprechpartnerin", the label, was suggested instead of the name below it). */
class GemmaContactLabelTest {

    private val pages = listOf(DeviceLetters.jobcenterBlocks)
    private val layout = LetterLayoutAnalyzer.analyze(pages)
    private val pairs = LabelValuePairs.of(pages, BlockZones.of(pages, layout))
    private val letter = GemmaLetterBuilder.build(layout, OfferedCandidates(emptyList()), labelPairs = pairs)

    @Test
    @DisplayName("the label line is marked, with the line below it as its value, and is not offered as a party")
    fun `label is marked`() {
        val label = letter.lines.first { it.text == "Ansprechpartnerin" }

        assertThat(label.isLabel).isTrue()
        assertThat(letter.line(label.valueLineId!!)!!.text).isEqualTo("Frau Nadine Beispiel")
        assertThat(GemmaSchema.partyIds(letter)).doesNotContain(label.id)
    }

    @Test
    @DisplayName("a contact the model named by the label line is the value line the block pairs with it")
    fun `contact label becomes its value`() {
        val v = GemmaReadingVerifier().verify(
            GemmaReading(contact = GemmaParty(id = letter.lineOf("Ansprechpartnerin"))),
            letter, OfferedCandidates(emptyList()), DeviceLetters.jobcenterText, null,
        )

        val contact = v.parties.single { it.role == PartyRole.CONTACT }
        assertThat(contact.quote).isEqualTo("Frau Nadine Beispiel")
    }
}
