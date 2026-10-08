package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.SelectionVerifier
import com.postsaimanager.core.domain.extraction.v2.VerificationContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The Gemma path's own verification: what the model decided, held to the checks of [GemmaReadingVerifier], is the confidence; the scoring
 * reading's caps do not apply on top, and what a check refused stays visible as a value to check.
 */
class GemmaResultVerifierTest {

    private val mini = MiniLetter()
    private val checks = GemmaReadingVerifier()
    private val mapper = GemmaReadingMapper()

    private fun context(ocrText: String = mini.ocrText) = VerificationContext(
        candidates = mini.set, offered = mini.offered, pageTexts = listOf(ocrText), layoutCharsSent = 1, layoutCharsTotal = 1, pagesRead = 1, totalPages = 1,
    )

    private fun result(reading: GemmaReading, verifierOf: (VerifiedReading) -> com.postsaimanager.core.domain.extraction.v2.ResultVerifier = { r -> GemmaResultVerifier { r } }): ExtractionV2Result {
        val verified = checks.verify(reading, mini.letter, mini.offered, mini.ocrText, LocalDate.of(2026, 9, 25))
        val raw = mapper.map(verified, "de", DocDirection.INCOMING, null).raw
        return verifierOf(verified).verify(raw, null, context())
    }

    @Test
    @DisplayName("a sender and a contact named by a line of the letter are as sure as the model was: above the 0.75 the linking needs (the scoring reading capped them at 0.6)")
    fun `a quoted party is live`() {
        val reading = GemmaReading(
            sender = GemmaParty(id = mini.letter.lineOf("Stadtwerke"), kind = "company"),
            contact = GemmaParty(id = mini.letter.lineOf("Erika Mustermann"), kind = "person"),
        )

        val gemma = result(reading)
        assertThat(gemma.parties.sender!!.value.confidence).isAtLeast(0.75f)
        assertThat(gemma.parties.contact!!.value.confidence).isAtLeast(0.75f)
        assertThat(gemma.parties.sender!!.value.needsReview).isFalse()

        val scoring = result(reading) { _ -> SelectionVerifier() }
        assertThat(scoring.parties.sender!!.value.confidence).isAtMost(0.6f)
    }

    @Test
    @DisplayName("a name candidate, a date and an amount the model chose keep the model's own confidence")
    fun `chosen values are as sure as the model`() {
        val gemma = result(
            GemmaReading(
                sender = GemmaParty(id = "M1", kind = "company"),
                dates = listOf(GemmaValue("D1", "LETTER_DATE"), GemmaValue("D2", "DUE_DATE")),
                toPayId = "A1",
            ),
        )

        assertThat(gemma.parties.sender!!.value.confidence).isEqualTo(0.9f)
        assertThat(gemma.slots.values.map { it.confidence }.toSet()).containsExactly(0.9f)
        assertThat(gemma.slots.values.first { it.meaning == "TOTAL_DUE" }.candidateId).isEqualTo("A1")
        assertThat(gemma.needsReview).isFalse()
    }

    @Test
    @DisplayName("what a check refused is not a silent empty: a value to check below the review line with its reason, a conflict that makes the reading need review")
    fun `refused answers are visible`() {
        val gemma = result(GemmaReading(dates = listOf(GemmaValue("D4", "DUE_DATE")), sender = GemmaParty(id = "M1"), addressee = GemmaParty(id = mini.letter.lineOf("Erika Mustermann"))), verifierOf = { r ->
            GemmaResultVerifier { r }
        })

        val refused = gemma.extras.single()
        assertThat(refused.value.candidateId).isEqualTo("D4")
        assertThat(refused.value.confidence).isLessThan(0.75f)
        assertThat(refused.value.confidence).isAtLeast(0.5f)
        assertThat(refused.value.blocked).isTrue()
        assertThat(refused.value.notes.single()).contains("D4")
        assertThat(gemma.diagnostics.conflicts).isNotEmpty()
        assertThat(gemma.needsReview).isTrue()
    }

    @Test
    @DisplayName("a party the model named by a line the letter's text does not hold is shown as a party to check, not linked and not lost")
    fun `ungrounded party is shown to check`() {
        val verified = checks.verify(
            GemmaReading(addressee = GemmaParty(id = mini.letter.lineOf("Erika Mustermann"))), mini.letter, mini.offered, "something else entirely", null,
        )
        val raw = mapper.map(verified, "de", DocDirection.INCOMING, null).raw

        val gemma = GemmaResultVerifier { verified }.verify(raw, null, context())

        val party = gemma.parties.withRole(PartyRole.ADDRESSEE).single()
        assertThat(party.name).isEqualTo("Erika Mustermann")
        assertThat(party.value.blocked).isTrue()
        assertThat(party.value.confidence).isLessThan(0.75f)
        assertThat(party.value.notes.single()).contains("not found in the letter")
    }
}
