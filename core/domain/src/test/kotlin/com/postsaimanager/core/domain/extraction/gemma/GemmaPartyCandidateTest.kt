package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.followup.FollowUpPrompts
import com.postsaimanager.core.domain.document.followup.ReadParties
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.model.Relationship
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A party the model named by a line is the name candidate on that line; the people question carries the reading's own answer. */
class GemmaPartyCandidateTest {

    private val letter = GemmaLetter(
        lines = listOf(
            GemmaLine("L1", 1, "letterhead", 0.5f, 0.05f, "Jobcenter Musterstadt"),
            GemmaLine("L2", 1, "address-field", 0.1f, 0.2f, "Maria Mustermann"),
        ),
        candidates = listOf(
            GemmaCandidate("M1", CandidateKind.NAME, "Jobcenter Musterstadt", "Jobcenter Musterstadt", "", "L1"),
            GemmaCandidate("M2", CandidateKind.NAME, "Maria Mustermann", "Maria Mustermann", "", "L2"),
        ),
    )

    @Test
    @DisplayName("a party chosen by the line of a name candidate is that candidate, not the line's text")
    fun `line becomes its name candidate`() {
        val v = GemmaReadingVerifier().verify(
            GemmaReading(sender = GemmaParty(id = "L1"), addressee = GemmaParty(id = "M2")),
            letter, OfferedCandidates(emptyList()), "Jobcenter Musterstadt\nMaria Mustermann", null,
        )

        assertThat(v.parties.single { it.role == PartyRole.SENDER }.candidateId).isEqualTo("M1")
        assertThat(v.parties.single { it.role == PartyRole.ADDRESSEE }.candidateId).isEqualTo("M2")
    }

    @Test
    @DisplayName("the people question names each person with the relationship and tells the model what its own reading found")
    fun `people question carries the reading`() {
        val ask = FollowUpPrompts.concerned(
            listOf(SubjectCandidate("maria", "Maria Mustermann", Relationship.CHILD)), "Zoe Zufall", setOf("maria"),
            ReadParties(sender = "Jobcenter Musterstadt", addressee = "Maria Mustermann"),
        )

        assertThat(ask.prompt).contains("P1: Maria Mustermann (the user's child)")
        assertThat(ask.prompt).contains("addressed to Maria Mustermann; it was sent by Jobcenter Musterstadt")
        assertThat(ask.prompt).contains("FOR or ABOUT")
        assertThat(ask.prompt).contains("Z: Zoe Zufall")
        assertThat(ask.candidateIds).containsExactly("P1")
    }
}
