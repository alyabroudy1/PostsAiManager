package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.RecognisedEntity
import com.postsaimanager.core.model.RecognisedFact
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [UnderstandingToFields].
 *
 * A field is keyed by `(documentId, fieldName)`, so what this produces decides whether
 * switching a document from patterns to the model preserves the user's corrections or
 * strands them on slots nothing writes to any more.
 */
class UnderstandingToFieldsTest {

    private var counter = 0
    private val newId: (String) -> String = { "id-${counter++}" }

    private fun map(understanding: DocumentUnderstanding) =
        UnderstandingToFields.invoke("doc-1", understanding, newId)

    private val letter = DocumentUnderstanding(
        language = "de",
        subject = "Widerspruchsbescheid",
        entities = listOf(
            RecognisedEntity("Jobcenter Berlin Mitte", EntityKind.AUTHORITY, EntityRole.SENDER, "", 0.95f),
            RecognisedEntity("Aylin Mustermann", EntityKind.PERSON, EntityRole.RECIPIENT, "", 0.9f),
            RecognisedEntity("Frau Müller", EntityKind.PERSON, EntityRole.SENDER_CONTACT, "", 0.85f),
            RecognisedEntity("Layla", EntityKind.PERSON, EntityRole.MENTIONED, "spouse", 0.6f),
        ),
        facts = listOf(
            RecognisedFact("Aktenzeichen", "BG 1234/5678", FactKind.REFERENCE, 0.92f),
            RecognisedFact("Frist", "31.01.2026", FactKind.DEADLINE, 0.85f),
            RecognisedFact("Datum", "15.01.2026", FactKind.DATE, 0.9f),
        ),
    )

    @Test
    @DisplayName("an organisation sender lands on the organisation slot, not the name slot")
    fun `sender kind decides the slot`() {
        val fields = map(letter).associateBy { it.fieldName }

        // The regex extractor writes these same names. A different one here would orphan
        // every field a user had already corrected.
        assertThat(fields[UnderstandingToFields.SENDER_ORGANISATION]?.fieldValue)
            .isEqualTo("Jobcenter Berlin Mitte")
        assertThat(fields).doesNotContainKey(UnderstandingToFields.SENDER_NAME)
        assertThat(fields[UnderstandingToFields.SENDER_ORGANISATION]?.fieldType)
            .isEqualTo(ExtractedFieldType.ORGANIZATION)
    }

    @Test
    fun `a person sender lands on the name slot`() {
        val fromPerson = letter.copy(
            entities = listOf(
                RecognisedEntity("Herr Schmidt", EntityKind.PERSON, EntityRole.SENDER, "", 0.9f),
            ),
        )
        val fields = map(fromPerson).associateBy { it.fieldName }

        assertThat(fields).containsKey(UnderstandingToFields.SENDER_NAME)
        assertThat(fields).doesNotContainKey(UnderstandingToFields.SENDER_ORGANISATION)
    }

    @Test
    fun `recipient and sender contact get their own slots`() {
        val fields = map(letter).associateBy { it.fieldName }

        assertThat(fields[UnderstandingToFields.RECEIVER_NAME]?.fieldValue)
            .isEqualTo("Aylin Mustermann")
        assertThat(fields[UnderstandingToFields.CONTACT_PERSON]?.fieldValue)
            .isEqualTo("Frau Müller")
    }

    @Test
    @DisplayName("mentioned people are not fields — they become profiles and links")
    fun `mentioned entities are not flattened into slots`() {
        val fields = map(letter)

        // A slot is unique per document, so two mentioned people cannot both be "Mentioned
        // Person" — and numbering them would make a correction attach to a different person
        // as soon as the model's ordering changed.
        assertThat(fields.none { it.fieldValue == "Layla" }).isTrue()
    }

    @Test
    @DisplayName("a deadline is canonical, whatever the letter called it")
    fun `deadline label is normalised`() {
        val fields = map(letter).associateBy { it.fieldName }

        // The model writes "Frist" or "Deadline" depending on the letter's language. Both
        // must reach the one slot reminders are built from.
        assertThat(fields[UnderstandingToFields.DEADLINE]?.fieldValue).isEqualTo("31.01.2026")
        assertThat(fields[UnderstandingToFields.DEADLINE]?.fieldType)
            .isEqualTo(ExtractedFieldType.DEADLINE)
        assertThat(fields).doesNotContainKey("Frist")
    }

    @Test
    fun `the letter date does not become the deadline`() {
        val fields = map(letter).associateBy { it.fieldName }

        // Confusing the two would put a reminder on a date that has already passed.
        assertThat(fields[UnderstandingToFields.DOCUMENT_DATE]?.fieldValue).isEqualTo("15.01.2026")
        assertThat(fields[UnderstandingToFields.DEADLINE]?.fieldValue).isEqualTo("31.01.2026")
    }

    @Test
    @DisplayName("references keep their own labels — they are genuinely different references")
    fun `reference labels are preserved`() {
        val withTwo = letter.copy(
            facts = listOf(
                RecognisedFact("Aktenzeichen", "BG 1234/5678", FactKind.REFERENCE, 0.9f),
                RecognisedFact("Ihr Zeichen", "WS-2026-0142", FactKind.REFERENCE, 0.9f),
            ),
        )
        val fields = map(withTwo).associateBy { it.fieldName }

        assertThat(fields["Aktenzeichen"]?.fieldValue).isEqualTo("BG 1234/5678")
        assertThat(fields["Ihr Zeichen"]?.fieldValue).isEqualTo("WS-2026-0142")
    }

    @Test
    fun `duplicate labels collapse to the most confident, not the last`() {
        val conflicting = letter.copy(
            facts = listOf(
                RecognisedFact("Frist", "31.01.2026", FactKind.DEADLINE, 0.9f),
                RecognisedFact("Frist", "28.02.2026", FactKind.DEADLINE, 0.4f),
            ),
        )
        val fields = map(conflicting).associateBy { it.fieldName }

        // The slot is unique, so one has to win. Order of parsing is not a reason.
        assertThat(fields[UnderstandingToFields.DEADLINE]?.fieldValue).isEqualTo("31.01.2026")
    }

    @Test
    fun `confidence is carried through, not invented`() {
        val fields = map(letter).associateBy { it.fieldName }

        // Real per-field confidence is the point of using a model — the regex extractor
        // reported a constant per field kind regardless of what it read.
        assertThat(fields[UnderstandingToFields.SENDER_ORGANISATION]?.confidence).isEqualTo(0.95f)
        assertThat(fields[UnderstandingToFields.DEADLINE]?.confidence).isEqualTo(0.85f)
    }

    @Test
    fun `blank values produce no field`() {
        val empty = DocumentUnderstanding(
            entities = listOf(
                RecognisedEntity("  ", EntityKind.AUTHORITY, EntityRole.SENDER, "", 0.9f),
            ),
            facts = listOf(RecognisedFact("Frist", "", FactKind.DEADLINE, 0.9f)),
        )
        assertThat(map(empty)).isEmpty()
    }

    @Test
    fun `an empty understanding maps to nothing`() {
        assertThat(map(DocumentUnderstanding())).isEmpty()
    }
}
