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

    private fun people(profile: ConcernedPeopleProfile = ConcernedPeopleProfile(reversedCheck = false)) = ModelConcernedPeople(session, framing, profile)

    @Test
    fun `the letter is the prefix, the members are listed once and the answer maps back to profiles`() = runTest {
        session.responder = { _, _ -> "P2; P3" }
        val result = people().decide("Dear Maria", listOf(me, maria, amir))
        assertThat((result as PamResult.Success).data).containsExactly("maria", "amir")
        assertThat(session.opens).hasSize(1)
        assertThat(session.opens.single()).contains("Dear Maria")
        assertThat(session.asks).hasSize(1)
        val ask = session.asks.single()
        assertThat(ask.question).contains("P1: Erika Mustermann")
        assertThat(ask.question).contains("P2: Maria Mustermann")
        assertThat(ask.grammar).contains("P3")
        assertThat(ask.grammar).contains("NONE")
        assertThat(session.closes).isEqualTo(1)
    }

    @Test
    fun `NONE and an id nobody was listed under name no one`() = runTest {
        session.responder = { _, _ -> "NONE" }
        assertThat((people().decide("x", listOf(me, maria)) as PamResult.Success).data).isEmpty()
        session.responder = { _, _ -> "P9; P1" }
        assertThat((people().decide("x", listOf(me, maria)) as PamResult.Success).data).containsExactly("me")
    }

    @Test
    fun `no members means no question`() = runTest {
        assertThat((people().decide("x", emptyList()) as PamResult.Success).data).isEmpty()
        assertThat(session.opens).isEmpty()
    }

    @Test
    fun `the reversed check asks again with the order reversed and keeps only those named both times`() = runTest {
        // A model that always names the first listed member.
        session.responder = { _, _ -> "P1" }
        val once = (people().decide("x", listOf(me, maria, amir)) as PamResult.Success).data
        assertThat(once).containsExactly("me")

        session.asks.clear()
        val twice = (people(ConcernedPeopleProfile(reversedCheck = true)).decide("x", listOf(me, maria, amir)) as PamResult.Success).data
        assertThat(session.asks).hasSize(2)
        assertThat(session.asks[1].question).contains("P1: Amir Mustermann")
        assertThat(twice).isEmpty() // P1 was Erika, then Amir: a position bias, named once each, never both times
    }

    @Test
    fun `a model that cannot answer is an error and the session is closed`() = runTest {
        session.openFailsWith = PamError.InferenceError("no model")
        assertThat(people().decide("x", listOf(me))).isInstanceOf(PamResult.Error::class.java)
        session.openFailsWith = null
        session.responder = { _, _ -> null }
        assertThat(people().decide("x", listOf(me))).isInstanceOf(PamResult.Error::class.java)
        assertThat(session.closes).isEqualTo(1)
    }

    @Test
    fun `the comparison records both orders and the per member scores from one session`() = runTest {
        session.responder = { _, _ -> "P1" }
        session.scorer = { if (it.contains("Maria")) 3.0 else -2.0 }
        val comparison = (people().compare("x", listOf(me, maria)) as PamResult.Success).data
        assertThat(comparison.forward).containsExactly("me")
        assertThat(comparison.reversed).containsExactly("maria")
        assertThat(comparison.scores).containsExactly(-2.0, 3.0).inOrder()
        assertThat(session.opens).hasSize(1)
    }
}
