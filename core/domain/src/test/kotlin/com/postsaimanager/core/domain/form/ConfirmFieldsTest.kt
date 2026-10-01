package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ConfirmFieldsTest {

    private fun candidate(label: String, evidence: FieldEvidence) =
        FieldCandidate(1, label, null, null, FormFieldKind.TEXT, evidence)

    private suspend fun session(script: (String) -> Double) = FakePromptSession().apply {
        open("prefix")
        scorer = { script(it.substringAfterLast("\n\n")) }
    }

    @Test
    fun `strong candidates pass unscored and only weak ones are asked`() = runTest {
        val session = session { 2.0 }
        val strong = candidate("Name des Kindes", FieldEvidence.FILL_RUN)
        val weak = candidate("Allergien", FieldEvidence.LABEL_SPACE)

        val kept = ConfirmFields(FormScorer(session)).confirm(listOf(strong, weak))

        assertThat(kept).containsExactly(strong, weak).inOrder()
        assertThat(session.scored.flatten()).hasSize(1)
        assertThat(session.scored.single().single()).contains("Is «Allergien …» something the reader must fill in? Answer:")
    }

    @Test
    fun `a weak candidate the model scores against is dropped`() = runTest {
        val session = session { q -> if (q.contains("Seite 1 von 2")) -3.0 else 3.0 }
        val page = candidate("Seite 1 von 2", FieldEvidence.LABEL_SPACE)
        val real = candidate("Telefon", FieldEvidence.TABLE_CELL)
        val box = candidate("Foto", FieldEvidence.BOX_GLYPH)

        assertThat(ConfirmFields(FormScorer(session)).confirm(listOf(page, real, box))).containsExactly(real, box).inOrder()
    }

    @Test
    fun `the threshold comes from the profile`() = runTest {
        val session = session { 0.5 }
        val weak = candidate("Allergien", FieldEvidence.LABEL_SPACE)

        assertThat(ConfirmFields(FormScorer(session), FormScoringProfile(confirmThreshold = 1.0)).confirm(listOf(weak))).isEmpty()
        assertThat(ConfirmFields(FormScorer(session), FormScoringProfile(confirmThreshold = 0.0)).confirm(listOf(weak))).hasSize(1)
    }

    @Test
    fun `no more weak candidates than the budget are scored, the rest are dropped`() = runTest {
        val session = session { 2.0 }
        val weak = (1..10).map { candidate("Feld $it", FieldEvidence.LABEL_SPACE) }

        val kept = ConfirmFields(FormScorer(session), FormScoringProfile(maxConfirmScores = 4)).confirm(weak)

        assertThat(kept).containsExactlyElementsIn(weak.take(4)).inOrder()
        assertThat(session.scored.flatten()).hasSize(4)
    }

    @Test
    fun `nothing is asked when every candidate is structural`() = runTest {
        val session = session { 2.0 }
        ConfirmFields(FormScorer(session)).confirm(listOf(candidate("IBAN", FieldEvidence.FILL_RUN)))
        assertThat(session.scored).isEmpty()
    }

    @Test
    fun `an engine failure is reported, not read as a no`() = runTest {
        val notOpen = FakePromptSession()
        val weak = candidate("Allergien", FieldEvidence.LABEL_SPACE)
        val failure = runCatching { ConfirmFields(FormScorer(notOpen)).confirm(listOf(weak)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(FormScoringException::class.java)
    }
}
