package com.postsaimanager.core.domain.document.contacts

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.BaselineScores
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DecideSameContactUseCaseTest {

    /** Scores each candidate by id from [byId] (-9 when not listed); the baseline is [baseline]. */
    private class FakeSameContact(private val byId: Map<String, Double>, private val baseline: Double = 0.0) : SameContact {
        val questions = mutableListOf<SameContactQuestion>()
        var error: PamError? = null

        override suspend fun score(question: SameContactQuestion): PamResult<BaselineScores> {
            questions += question
            error?.let { return PamResult.Error(it) }
            return PamResult.Success(BaselineScores(question.candidates.map { byId[it.id] ?: -9.0 }, baseline))
        }
    }

    private val nadine = ContactCandidate("nadine", "Nadine Müller", "Sachbearbeiterin", lastSeenAt = 2_000)
    private val herr = ContactCandidate("herr", "Herr Müller", lastSeenAt = 1_000)
    private val schmidt = ContactCandidate("schmidt", "Karl Schmidt", lastSeenAt = 3_000)
    private val letterContact = ReadContact("N. Müller")

    private fun useCase(fake: FakeSameContact) = DecideSameContactUseCase(fake, SameContactProfile())

    private suspend fun decide(fake: FakeSameContact, contact: ReadContact = letterContact, vararg candidates: ContactCandidate) =
        (useCase(fake)(contact, "Jobcenter Musterstadt", "Ihre Ansprechpartnerin", candidates.toList()) as PamResult.Success).data

    @Test
    fun `the pre-filter narrows the candidates and the model is asked only about the rest`() = runTest {
        val fake = FakeSameContact(mapOf("nadine" to 3.0))
        val decision = decide(fake, letterContact, nadine, schmidt)
        assertThat(fake.questions.single().candidates.map { it.id }).containsExactly("nadine")
        assertThat(decision.matchedId).isEqualTo("nadine")
    }

    @Test
    fun `the pre-filter never decides a match, a single surviving candidate is still scored`() = runTest {
        val fake = FakeSameContact(mapOf("nadine" to 0.1)) // only slightly above the baseline
        val decision = decide(fake, ReadContact("Nadine Müller"), nadine)
        assertThat(fake.questions).hasSize(1)
        assertThat(decision.isNewPerson).isTrue()
    }

    @Test
    fun `nobody sharing a token means a new person without asking the model`() = runTest {
        val fake = FakeSameContact(emptyMap())
        val decision = decide(fake, letterContact, schmidt)
        assertThat(fake.questions).isEmpty()
        assertThat(decision.isNewPerson).isTrue()
        assertThat(decision.asked).isEmpty()
        assertThat(decision.baseline).isNull()
    }

    @Test
    fun `Frau Mueller against Herr Mueller goes to the model, which decides`() = runTest {
        val no = decide(FakeSameContact(mapOf("herr" to 0.2)), ReadContact("Frau Müller"), herr)
        assertThat(no.isNewPerson).isTrue()
        val fake = FakeSameContact(mapOf("herr" to 2.5))
        val yes = decide(fake, ReadContact("Frau Müller"), herr)
        assertThat(fake.questions.single().candidates.single().id).isEqualTo("herr")
        assertThat(yes.matchedId).isEqualTo("herr")
    }

    @Test
    fun `a candidate must beat the distractor by the margin, the margin itself counts`() = runTest {
        val below = decide(FakeSameContact(mapOf("nadine" to 0.69), baseline = 0.0), letterContact, nadine)
        assertThat(below.isNewPerson).isTrue()
        val at = decide(FakeSameContact(mapOf("nadine" to 0.7), baseline = 0.0), letterContact, nadine)
        assertThat(at.matchedId).isEqualTo("nadine")
        val overBaseline = decide(FakeSameContact(mapOf("nadine" to -1.0), baseline = -2.0), letterContact, nadine)
        assertThat(overBaseline.matchedId).isEqualTo("nadine") // the margin is over the baseline, not over zero
        val baselineWins = decide(FakeSameContact(mapOf("nadine" to 2.0), baseline = 3.0), letterContact, nadine)
        assertThat(baselineWins.isNewPerson).isTrue()
    }

    @Test
    fun `the best of several candidates wins`() = runTest {
        val fake = FakeSameContact(mapOf("nadine" to 2.0, "herr" to 4.0))
        val decision = decide(fake, ReadContact("Müller"), nadine, herr, schmidt)
        assertThat(decision.matchedId).isEqualTo("herr")
        assertThat(decision.asked.map { it.candidateId }).containsExactly("nadine", "herr") // most recently seen first
        assertThat(decision.asked.first { it.candidateId == "herr" }.score).isEqualTo(4.0)
        assertThat(decision.baseline).isEqualTo(0.0)
        assertThat(decision.margin).isEqualTo(0.7)
    }

    @Test
    fun `new person when none beats the margin`() = runTest {
        val decision = decide(FakeSameContact(mapOf("nadine" to 0.5, "herr" to 0.6)), ReadContact("Müller"), nadine, herr)
        assertThat(decision.isNewPerson).isTrue()
        assertThat(decision.asked).hasSize(2) // the scores stay for the debug log
    }

    @Test
    fun `the question carries the organisation, the excerpt and the contact`() = runTest {
        val fake = FakeSameContact(emptyMap())
        decide(fake, ReadContact("N. Müller", title = "Teamleitung", phone = "030 123"), nadine)
        val q = fake.questions.single()
        assertThat(q.organisation).isEqualTo("Jobcenter Musterstadt")
        assertThat(q.excerpt).isEqualTo("Ihre Ansprechpartnerin")
        assertThat(q.contact.title).isEqualTo("Teamleitung")
    }

    @Test
    fun `a model that cannot answer is an error`() = runTest {
        val fake = FakeSameContact(emptyMap()).apply { error = PamError.InferenceError("no model") }
        assertThat(useCase(fake)(letterContact, "Org", null, listOf(nadine))).isInstanceOf(PamResult.Error::class.java)
    }

    @Test
    fun `more candidates than the cap keeps the most recently seen`() = runTest {
        val many = (1..9).map { ContactCandidate("c$it", "Anna Müller $it", lastSeenAt = it.toLong()) }
        val fake = FakeSameContact(emptyMap())
        decide(fake, ReadContact("Müller"), *many.toTypedArray())
        assertThat(fake.questions.single().candidates.map { it.id }).containsExactly("c9", "c8", "c7", "c6", "c5", "c4").inOrder()
    }
}
