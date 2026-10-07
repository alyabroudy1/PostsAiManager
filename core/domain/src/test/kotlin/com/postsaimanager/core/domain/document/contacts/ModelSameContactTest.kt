package com.postsaimanager.core.domain.document.contacts

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.PromptFraming
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ModelSameContactTest {

    private val session = FakePromptSession()
    private val framing = PromptFraming { system, user -> "[$system|$user]" to "<assistant>" }
    private val profile = SameContactProfile()
    private val nadine = ContactCandidate("nadine", "Nadine Müller", "Sachbearbeiterin", "030 / 555-123", "n.mueller@example.org", 1_700_000_000_000)
    private val other = ContactCandidate("other", "Herr Müller", phone = "030 999999")

    private fun model() = ModelSameContact(session, framing, profile)

    private fun question(contact: ReadContact, vararg candidates: ContactCandidate) =
        SameContactQuestion(contact, "Jobcenter Musterstadt", "Ihre Ansprechpartnerin ist N. Müller.", candidates.toList())

    @Test
    fun `the contact is the prefix, the candidates are the shared context, each candidate and the distractor is scored once`() = runTest {
        session.scorer = { text -> if (text.contains(profile.baselineName)) 0.0 else 2.0 }
        val result = model().score(question(ReadContact("N. Müller", "Teamleitung"), nadine, other)) as PamResult.Success
        assertThat(result.data.candidates).containsExactly(2.0, 2.0)
        assertThat(result.data.baseline).isEqualTo(0.0)
        assertThat(session.opens.single()).contains("ORGANISATION: Jobcenter Musterstadt")
        assertThat(session.opens.single()).contains("N. Müller; Teamleitung")
        assertThat(session.opens.single()).contains("LETTER EXCERPT")
        assertThat(session.sharedLevels.single()).contains("C1: Nadine Müller; Sachbearbeiterin; phone 030 / 555-123")
        assertThat(session.sharedLevels.single()).contains("last seen 2023-11-14")
        val asked = session.scored.flatten()
        assertThat(asked).hasSize(3)
        assertThat(asked.last()).contains("«${profile.baselineName}»")
        assertThat(asked.first()).contains("same person as «Nadine Müller» (Sachbearbeiterin, last seen 2023-11-14)?")
        assertThat(session.closes).isEqualTo(1)
    }

    @Test
    fun `an equal phone number or email is shown as a hint, only for the candidate it matches`() = runTest {
        session.scorer = { 0.0 }
        model().score(question(ReadContact("N. Müller", phone = "030 555123", email = "N.Mueller@example.org"), nadine, other))
        val asked = session.scored.flatten()
        assertThat(asked[0]).contains("The phone number is the same.")
        assertThat(asked[0]).contains("The email address is the same.")
        assertThat(asked[1]).doesNotContain("is the same.")
        assertThat(asked[2]).doesNotContain("is the same.") // the distractor never gets one
    }

    @Test
    fun `different numbers show no hint`() = runTest {
        session.scorer = { 0.0 }
        model().score(question(ReadContact("N. Müller", phone = "040 111222"), nadine))
        assertThat(session.scored.flatten().first()).doesNotContain("same.")
    }

    @Test
    fun `a model that cannot answer is an error`() = runTest {
        session.openFailsWith = PamError.InferenceError("no model")
        assertThat(model().score(question(ReadContact("N. Müller"), nadine))).isInstanceOf(PamResult.Error::class.java)
    }
}
