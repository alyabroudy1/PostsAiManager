package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The device's third pass: with "Test Kind" chosen as the subject, the child's name was not filled, and every field's section was
 * the form's TITLE. The page here is invented in that shape: a letterhead, a large title, then "Angaben zum Kind" (Vorname,
 * Nachname, Geburtsdatum, Anschrift) and "Erziehungsberechtigte/r" (Name, Telefon (Notfall), E-Mail).
 */
class ChildFormFillRegressionTest {

    private val h = 0.014f
    private val title = "Anmeldung zum Schwimmkurs „Seepferdchen“"

    private fun block(text: String, left: Float, top: Float, right: Float, height: Float = h) =
        OcrBlock(text, TextBounds(left, top, right, top + height), 0.95f, null)

    private fun page() = listOf(
        block("SV Musterbad 1920 e.V. · Badstraße 1 · 12345 Musterstadt", 0.08f, 0.02f, 0.70f),
        block(title, 0.08f, 0.07f, 0.80f, 0.024f),
        block("Angaben zum Kind", 0.08f, 0.14f, 0.34f, 0.016f),
        block("Vorname: ______________", 0.08f, 0.18f, 0.45f),
        block("Nachname: ______________", 0.08f, 0.215f, 0.45f),
        block("Geburtsdatum: ______________", 0.08f, 0.25f, 0.45f),
        block("Anschrift: ______________", 0.08f, 0.285f, 0.45f),
        block("Erziehungsberechtigte/r", 0.08f, 0.36f, 0.40f, 0.016f),
        block("Name: ______________", 0.08f, 0.40f, 0.45f),
        block("Telefon (Notfall): ______________", 0.08f, 0.435f, 0.50f),
        block("E-Mail: ______________", 0.08f, 0.47f, 0.45f),
    )

    private val keys = mapOf(
        "Vorname" to "given_name", "Nachname" to "family_name", "Geburtsdatum" to "birth_date", "Anschrift" to "address",
        "Name" to "full_name", "Telefon (Notfall)" to "phone", "E-Mail" to "email",
    )

    private val script = FormScript(
        keyOf = keys,
        sectionRoles = mapOf("Angaben zum Kind" to FormRole.SUBJECT, "Erziehungsberechtigte/r" to FormRole.GUARDIAN),
        subjects = mapOf("Test Kind" to 3.0),
    )

    private val kind = SubjectCandidate("kind", "Test Kind", Relationship.CHILD, birthDate = LocalDate.of(2019, 3, 12))
    private val nowMs = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private suspend fun understand(): FormUnderstanding {
        val session = FakePromptSession().apply { scorer = script::score }
        val embedder = FakeEmbedder(intent = { label -> keys[label]?.let(::listOf).orEmpty() })
        val request = UnderstandFormRequest("doc", "fill", listOf(page()), listOf(kind), LocalDate.of(2026, 10, 1), nowMs, Locale.GERMANY)
        return (UnderstandFormUseCase(session, { s, u -> "<s>$s|$u<u>" to "<a>" }, embedder)(request) as PamResult.Success).data
    }

    @Test
    fun `the title is not the section of the fields below a real heading`() = runTest {
        val fields = understand().fields
        assertThat(fields.single { it.labelText == "Vorname" }.section).isEqualTo("Angaben zum Kind")
        assertThat(fields.single { it.labelText == "Name" }.section).isEqualTo("Erziehungsberechtigte/r")
        assertThat(fields.mapNotNull { it.section }).doesNotContain(title)
    }

    @Test
    fun `a heading that is not larger than a later one is a section, not the title`() {
        val page = listOf(
            block("Angaben zum Kind", 0.08f, 0.14f, 0.34f, 0.016f),
            block("Vorname: ______________", 0.08f, 0.18f, 0.45f),
            block("Erziehungsberechtigte/r", 0.08f, 0.36f, 0.40f, 0.016f),
            block("Name: ______________", 0.08f, 0.40f, 0.45f),
        )
        val found = FindFillableFields().find(listOf(page))
        assertThat(found.map { it.section }).containsExactly("Angaben zum Kind", "Erziehungsberechtigte/r").inOrder()
    }

    @Test
    fun `a first heading of the page's own size is a real section, not a title`() {
        val sectionFirst = listOf(block("Angaben zum Kind", 0.08f, 0.14f, 0.34f, 0.016f), block("Vorname: ______________", 0.08f, 0.18f, 0.45f))
        val found = FindFillableFields().find(listOf(sectionFirst))
        assertThat(found.single().section).isEqualTo("Angaben zum Kind")
    }

    @Test
    fun `the child's name, birth date and address are filled from the chosen subject, the guardian's are not`() = runTest {
        val understanding = understand()
        assertThat(understanding.field("Vorname").role).isEqualTo(FormRole.SUBJECT)
        assertThat(understanding.field("Vorname").dataKey).isEqualTo("given_name")
        assertThat(understanding.field("Nachname").dataKey).isEqualTo("family_name")
        assertThat(understanding.field("Name").role).isEqualTo(FormRole.GUARDIAN)

        val source = FakePersonDataSource(
            mapOf(
                "kind" to mapOf(
                    "full_name" to FakePersonDataSource.profile("Test Kind", nowMs),
                    "birth_date" to FakePersonDataSource.profile("2019-03-12", nowMs),
                    "street" to FakePersonDataSource.profile("Musterstraße 12", nowMs),
                    "postcode" to FakePersonDataSource.profile("54321", nowMs),
                    "city" to FakePersonDataSource.profile("Beispieldorf", nowMs),
                ),
            ),
        )
        val context = FillContext(
            roleProfiles = mapOf(FormRole.SUBJECT to "kind"), confirmedRoles = setOf(FormRole.SUBJECT), locale = Locale.GERMANY,
            nowMs = nowMs, zone = ZoneOffset.UTC,
        )
        val filled = FillValues(source).fill(understanding.fields, context).fields.associateBy { it.labelText }

        assertThat(filled.getValue("Vorname").value).isEqualTo("Test")
        assertThat(filled.getValue("Nachname").value).isEqualTo("Kind")
        assertThat(filled.getValue("Geburtsdatum").value).isEqualTo("12.03.2019")
        assertThat(filled.getValue("Anschrift").value).contains("Musterstraße 12")
        // The guardian's section has no chosen person: nothing of the child's is written there.
        assertThat(filled.getValue("Name").value).isNull()
        assertThat(filled.getValue("E-Mail").value).isNull()
    }

    private fun FormUnderstanding.field(label: String) = fields.single { it.labelText == label }
}
