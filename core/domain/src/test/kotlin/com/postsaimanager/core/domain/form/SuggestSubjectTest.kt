package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SuggestSubjectTest {

    private val today = LocalDate.of(2026, 10, 1)
    private val intro = listOf("Anmeldung Schwimmkurs Seepferdchen", "Kinder 6–10 Jahre · Kursbeginn im Herbst", "Bitte vollständig ausfüllen")
    private val ahmad = SubjectCandidate("p-ahmad", "Ahmad", Relationship.CHILD, birthDate = LocalDate.of(2019, 3, 12))
    private val me = SubjectCandidate("p-me", "Me", isSelf = true, birthDate = LocalDate.of(1985, 6, 1))

    private suspend fun session(script: FormScript) = FakePromptSession().apply {
        open("prefix")
        scorer = script::score
    }

    @Test
    fun `the profile the form is for ranks first with the quoted line that supports it`() = runTest {
        val script = FormScript(subjects = mapOf("Ahmad" to 3.0, "Me" to -2.0), reasonLine = "Kinder 6–10 Jahre · Kursbeginn im Herbst")
        val session = session(script)

        val ranking = SuggestSubject(FormScorer(session)).suggest(intro, listOf(me, ahmad), today)

        assertThat(ranking.map { it.profileId }).containsExactly("p-ahmad", "p-me").inOrder()
        assertThat(ranking[0].plausible).isTrue()
        assertThat(ranking[1].plausible).isFalse()
        assertThat(ranking[0].reasonLine).isEqualTo("Kinder 6–10 Jahre · Kursbeginn im Herbst")
        assertThat(ranking[1].reasonLine).isNull()
        assertThat(ranking[0].confidence).isGreaterThan(0.9f)
    }

    @Test
    fun `the question names the relationship and the age computed from the birth date`() = runTest {
        val session = session(FormScript())
        SuggestSubject(FormScorer(session)).suggest(intro, listOf(ahmad, me), today)
        val asked = session.scored.first()
        assertThat(asked[0]).contains("Is this form for the user's child Ahmad, aged 7? Answer:")
        assertThat(asked[1]).contains("Is this form for the user Me, aged 41? Answer:")
        assertThat(session.sharedLevels.first()).contains("Kinder 6–10 Jahre")
    }

    @Test
    fun `no reason is quoted when nothing is plausible`() = runTest {
        val session = session(FormScript(subjects = mapOf("Ahmad" to -1.0, "Me" to -2.0), reasonLine = "Bitte vollständig ausfüllen"))
        val ranking = SuggestSubject(FormScorer(session)).suggest(intro, listOf(ahmad, me), today)
        assertThat(ranking.none { it.plausible }).isTrue()
        assertThat(ranking.all { it.reasonLine == null }).isTrue()
        assertThat(session.scored).hasSize(1)
    }

    @Test
    fun `at most six profiles are scored and no profiles means no scores`() = runTest {
        val session = session(FormScript(subjects = mapOf("P0" to 1.0)))
        val many = (0..8).map { SubjectCandidate("id$it", "P$it") }
        val ranking = SuggestSubject(FormScorer(session)).suggest(intro, many, today)
        assertThat(ranking).hasSize(6)
        assertThat(SuggestSubject(FormScorer(session)).suggest(intro, emptyList(), today)).isEmpty()
    }

    @Test
    fun `a reason line that is not in the form is never returned`() = runTest {
        val script = FormScript(subjects = mapOf("Ahmad" to 3.0), reasonLine = "Eine Zeile die nicht im Formular steht")
        val session = session(script)
        // Only the form's own lines are offered as reasons, so a line the model prefers but the form lacks cannot come back.
        assertThat(SuggestSubject(FormScorer(session)).suggest(intro, listOf(ahmad), today).single().reasonLine).isNull()
        val withLine = SuggestSubject(FormScorer(session)).suggest(intro + "Eine Zeile die nicht im Formular steht", listOf(ahmad), today)
        assertThat(withLine.single().reasonLine).isEqualTo("Eine Zeile die nicht im Formular steht")
    }
}
