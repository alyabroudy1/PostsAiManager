package com.postsaimanager.core.domain.document.people

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.PromptFraming
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ModelConcernedPeopleTest {

    private val session = FakePromptSession()
    private val framing = PromptFraming { system, user -> "[$system|$user]" to "<assistant>" }
    private val me = SubjectCandidate("me", "Erika Mustermann", isSelf = true)
    private val maria = SubjectCandidate("maria", "Maria Mustermann", Relationship.CHILD)
    private val amir = SubjectCandidate("amir", "Amir Mustermann", Relationship.CHILD)
    private val profile = ConcernedPeopleProfile()

    private fun people() = ModelConcernedPeople(session, framing, profile)

    /** Scores by the name a statement is about; the made-up name is the baseline. */
    private fun scoring(vararg byName: Pair<String, Double>, baseline: Double = 0.0) {
        session.scorer = { text ->
            val about = text.substringAfterLast("«").substringBefore("»")
            if (about == profile.baselineName) baseline else byName.toMap().entries.firstOrNull { about.startsWith(it.key) }?.value ?: -9.0
        }
    }

    @Test
    fun `the letter is the prefix, the members are the shared context, each member and the made-up name is scored once`() = runTest {
        scoring("Erika" to 0.0, "Maria" to 3.0, "Amir" to 0.0)
        val result = people().decide("Dear Maria", listOf(me, maria, amir))
        assertThat((result as PamResult.Success).data).containsExactly("maria")
        assertThat(session.opens).hasSize(1)
        assertThat(session.opens.single()).contains("Dear Maria")
        assertThat(session.scored.flatten()).hasSize(4) // three members and the made-up name
        val shared = session.sharedLevels.single()
        assertThat(shared).contains("any one of the following members")
        assertThat(shared).contains("P2: Maria Mustermann")
        assertThat(session.scored.flatten().last()).contains(profile.baselineName)
        assertThat(session.closes).isEqualTo(1)
    }

    @Test
    fun `no members means no question`() = runTest {
        assertThat((people().decide("x", emptyList()) as PamResult.Success).data).isEmpty()
        assertThat(session.opens).isEmpty()
    }

    @Test
    fun `nobody beats the made-up name by the margin means nobody`() = runTest {
        scoring("Erika" to 0.5, "Maria" to 0.6, baseline = 0.0)
        assertThat((people().decide("x", listOf(me, maria)) as PamResult.Success).data).isEmpty()
    }

    @Test
    fun `a model that cannot answer is an error and the session is closed`() = runTest {
        session.openFailsWith = PamError.InferenceError("no model")
        assertThat(people().decide("x", listOf(me))).isInstanceOf(PamResult.Error::class.java)
        session.openFailsWith = null
        assertThat(people().decide("x", listOf(me))).isInstanceOf(PamResult.Success::class.java)
        assertThat(session.closes).isEqualTo(1)
    }

    @Test
    fun `the margin selection keeps what beats the baseline by at least the margin`() {
        val p = ConcernedPeopleProfile(margin = 0.7)
        assertThat(p.select(emptyList(), 0.0)).isEmpty()
        assertThat(p.select(listOf(0.2, 0.69, -1.0), 0.0)).isEmpty() // none
        assertThat(p.select(listOf(0.2, 3.0, 0.5), 0.0)).containsExactly(1) // one
        assertThat(p.select(listOf(2.0, 0.7, 0.1), 0.0)).containsExactly(0, 1).inOrder() // two (the margin itself counts)
        assertThat(p.select(listOf(1.0, 2.0), 3.0)).isEmpty() // the baseline beats everyone
        assertThat(p.select(listOf(-0.5, -0.1), -2.0)).containsExactly(0, 1) // the margin is over the baseline, not over zero
    }
}
