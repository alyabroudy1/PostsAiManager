package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The layout consistency of the parties on the Jobcenter letter (pass 28: "From Maria Mustermann · For Jobcenter"). */
class GemmaPartyLayoutTest {

    private val layout = LetterLayoutAnalyzer.analyze(listOf(DeviceLetters.jobcenterBlocks))
    private val letter = GemmaLetterBuilder.build(layout, OfferedCandidates(emptyList()))
    private val verifier = GemmaReadingVerifier()

    private fun verify(sender: String, addressee: String) = verifier.verify(
        GemmaReading(sender = GemmaParty(id = letter.lineOf(sender)), addressee = GemmaParty(id = letter.lineOf(addressee))),
        letter, OfferedCandidates(emptyList()), DeviceLetters.jobcenterText, null,
    )

    private fun VerifiedReading.name(role: PartyRole) = parties.firstOrNull { it.role == role }?.quote

    @Test
    @DisplayName("a sender in the address field with the addressee in the letterhead are swapped")
    fun `swapped`() {
        val v = verify(sender = "Maria Mustermann", addressee = "Jobcenter Musterstadt")

        assertThat(v.name(PartyRole.SENDER)).isEqualTo("Jobcenter Musterstadt")
        assertThat(v.name(PartyRole.ADDRESSEE)).isEqualTo("Maria Mustermann")
        assertThat(v.toCheck).isEmpty()
    }

    @Test
    @DisplayName("a correct answer is left as it is")
    fun `consistent`() {
        val v = verify(sender = "Jobcenter Musterstadt", addressee = "Maria Mustermann")

        assertThat(v.name(PartyRole.SENDER)).isEqualTo("Jobcenter Musterstadt")
        assertThat(v.name(PartyRole.ADDRESSEE)).isEqualTo("Maria Mustermann")
        assertThat(v.drops).isEmpty()
    }

    @Test
    @DisplayName("a sender in the address field with an addressee outside the sender zones: both are to check")
    fun `to check`() {
        val v = verify(sender = "Maria Mustermann", addressee = "Frau Nadine Beispiel")

        assertThat(v.parties.map { it.role }).containsNoneOf(PartyRole.SENDER, PartyRole.ADDRESSEE)
        assertThat(v.toCheck.mapNotNull { it.party?.role }).containsExactly(PartyRole.SENDER, PartyRole.ADDRESSEE)
        assertThat(v.losses).isNotEmpty()
    }
}
