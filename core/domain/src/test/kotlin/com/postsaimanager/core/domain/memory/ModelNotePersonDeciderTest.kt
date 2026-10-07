package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.people.ConcernedPeopleProfile
import com.postsaimanager.core.domain.form.PromptFraming
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The person decision per note: a scored question against the household's names, with a made-up name as the baseline. */
class ModelNotePersonDeciderTest {

    private val session = FakePromptSession()
    private val framing = PromptFraming { system, user -> "[$system|$user]" to "<assistant>" }
    private val profile = ConcernedPeopleProfile()
    private val me = SubjectCandidate("me", "Erika Mustermann", isSelf = true)
    private val omar = SubjectCandidate("omar", "Omar Mustermann", Relationship.CHILD)
    private val maria = SubjectCandidate("maria", "Maria Mustermann", Relationship.PARTNER)

    private fun decider() = ModelNotePersonDecider(session, framing, profile)

    /** Scores by the name a statement is about; the made-up name is the baseline. */
    private fun scoring(vararg byName: Pair<String, Double>, baseline: Double = 0.0) {
        session.scorer = { text ->
            val about = text.substringAfterLast("«").substringBefore("»")
            if (about == profile.baselineName) baseline else byName.toMap().entries.firstOrNull { about.startsWith(it.key) }?.value ?: -9.0
        }
    }

    private suspend fun decide(note: String, vararg persons: SubjectCandidate) = (decider().decide(note, persons.toList()) as PamResult.Success).data

    @Test
    fun `the note is the prefix, the household is the shared context, each person and the made-up name is scored once`() = runTest {
        scoring("Erika" to 0.0, "Omar" to 3.0)

        assertThat(decide("Omar has swimming on Tuesdays", me, omar)).isEqualTo("omar")

        assertThat(session.opens).hasSize(1)
        assertThat(session.opens.single()).contains("Omar has swimming on Tuesdays")
        assertThat(session.scored.flatten()).hasSize(3) // two persons and the made-up name
        assertThat(session.sharedLevels.single()).contains("P2: Omar Mustermann")
        assertThat(session.scored.flatten().last()).contains(profile.baselineName)
        assertThat(session.closes).isEqualTo(1)
    }

    @Test
    fun `nobody beats the made-up name by the margin means a household-wide note`() = runTest {
        scoring("Erika" to 0.5, "Omar" to 0.6, baseline = 0.0)

        assertThat(decide("The family moves in March", me, omar)).isNull()
    }

    @Test
    fun `of several that pass the margin the best scores`() = runTest {
        scoring("Erika" to 2.0, "Omar" to 4.0, "Maria" to 1.0)

        assertThat(decide("Omar and Erika have a dentist appointment", me, omar, maria)).isEqualTo("omar")
    }

    @Test
    fun `no persons means no question`() = runTest {
        assertThat(decide("anything")).isNull()
        assertThat(session.opens).isEmpty()
    }

    @Test
    fun `a model that cannot answer is an error and the session is closed`() = runTest {
        session.openFailsWith = PamError.InferenceError("no model")

        assertThat(decider().decide("x", listOf(me))).isInstanceOf(PamResult.Error::class.java)
        session.openFailsWith = null
        assertThat(decider().decide("x", listOf(me))).isInstanceOf(PamResult.Success::class.java)
        assertThat(session.closes).isEqualTo(1)
    }
}
