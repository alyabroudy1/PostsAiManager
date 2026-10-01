package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ClassifyFieldsTest {

    private fun candidate(label: String, kind: FormFieldKind = FormFieldKind.TEXT, section: String? = null) =
        FieldCandidate(1, label, null, null, kind, FieldEvidence.FILL_RUN, section)

    private suspend fun session(script: FormScript) = FakePromptSession().apply {
        open("prefix")
        scorer = script::score
    }

    /** The embedder ranks the key the label really asks for second; the model decides among the top three. */
    private val embedder = FakeEmbedder(intent = { label ->
        when (label) {
            "Geburtsdatum" -> listOf("birth_place", "birth_date", "today_date")
            "Name des Kindes" -> listOf("given_name", "full_name", "family_name")
            "Unterschrift" -> listOf("signature", "full_name", "today_place")
            "Hobby" -> listOf("occupation", "school", "employer")
            else -> emptyList()
        }
    })

    @Test
    fun `the model chooses among the embedding's top three keys and refines the kind from the key`() = runTest {
        val script = FormScript(keyOf = mapOf("Geburtsdatum" to "birth_date", "Name des Kindes" to "full_name", "Unterschrift" to "signature"))
        val session = session(script)

        val result = ClassifyFields(FormScorer(session), embedder).classify(
            listOf(candidate("Geburtsdatum"), candidate("Name des Kindes"), candidate("Unterschrift")),
        )

        assertThat(result.map { it.dataKey }).containsExactly("birth_date", "full_name", "signature").inOrder()
        assertThat(result.map { it.kind }).containsExactly(FormFieldKind.DATE, FormFieldKind.TEXT, FormFieldKind.SIGNATURE).inOrder()
        assertThat(result.all { it.confidence > 0.9f }).isTrue()
    }

    @Test
    fun `only the top three keys and none are scored per field`() = runTest {
        val script = FormScript(keyOf = mapOf("Geburtsdatum" to "birth_date"))
        val session = session(script)

        ClassifyFields(FormScorer(session), embedder).classify(listOf(candidate("Geburtsdatum")))

        val asked = session.scored.flatten()
        assertThat(asked).hasSize(4)
        assertThat(asked.count { it.contains(FormDataKeys.BIRTH_PLACE.description) }).isEqualTo(1)
        assertThat(asked.count { it.contains(FormDataKeys.BIRTH_DATE.description) }).isEqualTo(1)
        assertThat(asked.count { it.contains(FormDataKeys.TODAY_DATE.description) }).isEqualTo(1)
        assertThat(asked.any { it.contains(FormDataKeys.IBAN.description) }).isFalse()
    }

    @Test
    fun `a field no key fits gets no key`() = runTest {
        val session = session(FormScript())

        val result = ClassifyFields(FormScorer(session), embedder).classify(listOf(candidate("Hobby")))

        assertThat(result.single().dataKey).isNull()
    }

    @Test
    fun `none beating the best key leaves the field without a key`() = runTest {
        val session = FakePromptSession().apply {
            open("prefix")
            scorer = { q -> if (q.contains("something other than")) 3.0 else 1.0 }
        }
        val result = ClassifyFields(FormScorer(session), embedder).classify(listOf(candidate("Geburtsdatum")))
        assertThat(result.single().dataKey).isNull()
    }

    @Test
    fun `the section is part of the question`() = runTest {
        val session = session(FormScript(keyOf = mapOf("Name des Kindes" to "full_name")))
        ClassifyFields(FormScorer(session), embedder).classify(listOf(candidate("Name des Kindes", section = "Angaben zum Kind")))
        assertThat(session.scored.flatten().all { it.contains("(in the part «Angaben zum Kind»)") }).isTrue()
    }

    @Test
    fun `the thresholds are the profile's`() = runTest {
        val session = session(FormScript(keyOf = mapOf("Geburtsdatum" to "birth_date")))
        val strict = FormScoringProfile(keyThreshold = 10.0)
        assertThat(ClassifyFields(FormScorer(session), embedder, strict).classify(listOf(candidate("Geburtsdatum"))).single().dataKey).isNull()
    }

    @Test
    fun `fields beyond the score budget are left without a key`() = runTest {
        val session = session(FormScript(keyOf = mapOf("Geburtsdatum" to "birth_date")))
        val profile = FormScoringProfile(maxClassifyScores = 8)

        val result = ClassifyFields(FormScorer(session), embedder, profile).classify(List(3) { candidate("Geburtsdatum") })

        assertThat(result.map { it.dataKey }).containsExactly("birth_date", "birth_date", null).inOrder()
        assertThat(session.scored.flatten()).hasSize(8)
    }

    @Test
    fun `without an embedding model the model scores every key per field, the label read once`() = runTest {
        val session = FakePromptSession().apply {
            open("prefix")
            // The shared level carries the label; each question names one key.
            scorer = { whole ->
                val key = FormDataKeys.ALL.firstOrNull { whole.contains("ask for ${it.description}?") }
                if (whole.contains("«Geburtsdatum»") && key?.id == "birth_date") FormScript.YES else FormScript.NO
            }
        }
        val result = ClassifyFields(FormScorer(session), FakeEmbedder(ready = false)).classify(listOf(candidate("Geburtsdatum")))
        assertThat(result.single().dataKey).isEqualTo("birth_date")
        assertThat(result.single().kind).isEqualTo(FormFieldKind.DATE)
        assertThat(session.scored.flatten()).hasSize(FormDataKeys.ALL.size + 1)
        assertThat(session.sharedLevels.single()).contains("«Geburtsdatum»")
    }

    @Test
    fun `the fallback spends at most its budget and leaves the later fields without a key`() = runTest {
        val session = FakePromptSession().apply { open("prefix") }
        val profile = FormScoringProfile(maxFallbackClassifyScores = FormDataKeys.ALL.size + 1)
        val result = ClassifyFields(FormScorer(session), FakeEmbedder(ready = false), profile).classify(List(3) { candidate("Hobby") })
        assertThat(session.scored.flatten()).hasSize(FormDataKeys.ALL.size + 1)
        assertThat(result.drop(1).all { it.dataKey == null }).isTrue()
    }
}
