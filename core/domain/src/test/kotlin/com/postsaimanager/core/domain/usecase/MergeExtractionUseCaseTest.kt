package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [MergeExtractionUseCase].
 *
 * The behaviour under test is what happens to a person's corrections when the machine runs
 * again. The version this replaced deleted every extracted field before re-inserting, so
 * every correction was destroyed silently on the second run — the failure had no symptom
 * until someone noticed their work was gone. These pin each rule so it cannot come back.
 */
class MergeExtractionUseCaseTest {

    private val merge = MergeExtractionUseCase()
    private val now = 1_000L
    private var counter = 0
    private val newId: (String) -> String = { slot -> "$slot-rev${counter++}" }

    private fun field(
        name: String,
        value: String,
        confidence: Float = 0.9f,
        source: ValueSource = ValueSource.MACHINE,
        machineValue: String? = value,
        isConfirmed: Boolean = false,
        deletedByUser: Boolean = false,
        flagged: Boolean = false,
    ) = ExtractedData(
        id = "id-$name",
        documentId = "doc-1",
        fieldName = name,
        fieldValue = value,
        fieldType = ExtractedFieldType.TEXT,
        confidence = confidence,
        isConfirmed = isConfirmed,
        source = source,
        machineValue = machineValue,
        machineConfidence = confidence,
        deletedByUser = deletedByUser,
        hasUnreviewedMachineChange = flagged,
    )

    private fun run(existing: List<ExtractedData>, extracted: List<ExtractedData>) =
        merge(existing, extracted, engineVersion = "v2", now = now, newId = newId)

    @Nested
    @DisplayName("A value the user authored")
    inner class UserValues {

        @Test
        @DisplayName("survives an extractor that is far more confident and disagrees")
        fun `is never overwritten by a higher confidence machine value`() {
            // The scenario this whole mechanism exists for: the receiver name came out
            // wrong at 0.42, the user fixed it, and a newer extractor now reads it
            // differently at 0.81.
            val corrected = field(
                "receiverName",
                "Aylin Mustermann",
                source = ValueSource.USER,
                machineValue = "Frau Aylin Mustermann",
                confidence = 0.42f,
            )
            val fresh = field("receiverName", "A. Mustermann", confidence = 0.81f)

            val result = run(listOf(corrected), listOf(fresh))
            val merged = result.toPersist.single()

            assertThat(merged.fieldValue).isEqualTo("Aylin Mustermann")
            assertThat(merged.source).isEqualTo(ValueSource.USER)
            // The new reading is kept, so the user can be told what the document says now.
            assertThat(merged.machineValue).isEqualTo("A. Mustermann")
            assertThat(merged.hasUnreviewedMachineChange).isTrue()
            assertThat(result.newlyFlagged).containsExactly("receiverName")
        }

        @Test
        @DisplayName("is not flagged when the extractor still reads what it read before")
        fun `no flag when the machine has not changed its mind`() {
            val corrected = field(
                "receiverName",
                "Aylin Mustermann",
                source = ValueSource.USER,
                machineValue = "Frau Aylin Mustermann",
            )
            val fresh = field("receiverName", "Frau Aylin Mustermann")

            val merged = run(listOf(corrected), listOf(fresh)).toPersist.single()

            // The document has not changed; interrupting the user would be noise.
            assertThat(merged.hasUnreviewedMachineChange).isFalse()
            assertThat(merged.fieldValue).isEqualTo("Aylin Mustermann")
        }

        @Test
        fun `survives the extractor dropping the field entirely`() {
            val added = field("caseWorker", "Herr Schmidt", source = ValueSource.USER)

            val result = run(listOf(added), emptyList())

            assertThat(result.idsToDelete).isEmpty()
            assertThat(result.toPersist.single().fieldValue).isEqualTo("Herr Schmidt")
        }

        @Test
        @DisplayName("an unresolved flag is not cleared by a later agreeing run")
        fun `flags are sticky until reviewed`() {
            val flagged = field(
                "receiverName",
                "Aylin Mustermann",
                source = ValueSource.USER,
                machineValue = "A. Mustermann",
                flagged = true,
            )
            val fresh = field("receiverName", "A. Mustermann")

            // The machine repeats itself, which is not the user reviewing anything.
            assertThat(run(listOf(flagged), listOf(fresh)).toPersist.single()
                .hasUnreviewedMachineChange).isTrue()
        }
    }

    @Nested
    @DisplayName("A value the machine authored")
    inner class MachineValues {

        @Test
        fun `is replaced by a new reading`() {
            val stored = field("senderName", "Jobcenter Berln")
            val fresh = field("senderName", "Jobcenter Berlin")

            val merged = run(listOf(stored), listOf(fresh)).toPersist.single()

            assertThat(merged.fieldValue).isEqualTo("Jobcenter Berlin")
            assertThat(merged.source).isEqualTo(ValueSource.MACHINE)
        }

        @Test
        fun `is removed when the extractor no longer produces it`() {
            val result = run(listOf(field("stray", "noise")), emptyList())

            assertThat(result.idsToDelete).containsExactly("id-stray")
            assertThat(result.toPersist).isEmpty()
        }

        @Test
        @DisplayName("confirmation does not carry over to a different value")
        fun `a confirmed value that changes is no longer confirmed`() {
            val confirmed = field("date", "31.01.2026", isConfirmed = true)
            val fresh = field("date", "28.02.2026")

            val merged = run(listOf(confirmed), listOf(fresh)).toPersist.single()

            // The user confirmed the old reading. They have not seen this one.
            assertThat(merged.fieldValue).isEqualTo("28.02.2026")
            assertThat(merged.isConfirmed).isFalse()
        }

        @Test
        fun `an unchanged value keeps its confirmation and its timestamp`() {
            val confirmed = field("date", "31.01.2026", isConfirmed = true).copy(updatedAt = 5L)
            val merged = run(listOf(confirmed), listOf(field("date", "31.01.2026")))
                .toPersist.single()

            assertThat(merged.isConfirmed).isTrue()
            assertThat(merged.updatedAt).isEqualTo(5L)
        }
    }

    @Nested
    @DisplayName("A field the user deleted")
    inner class Tombstones {

        @Test
        fun `is not resurrected by extraction`() {
            val removed = field("bogus", "nonsense", deletedByUser = true)

            val result = run(listOf(removed), listOf(field("bogus", "nonsense again")))

            // Otherwise every reprocess re-adds what the user just removed, and the app
            // argues with them once per run.
            assertThat(result.toPersist.single().deletedByUser).isTrue()
            assertThat(result.toPersist.single().fieldValue).isEqualTo("nonsense")
        }

        @Test
        fun `still records what the extractor would have said`() {
            val removed = field("bogus", "nonsense", deletedByUser = true)

            val result = run(listOf(removed), listOf(field("bogus", "nonsense again")))

            assertThat(result.toPersist.single().machineValue).isEqualTo("nonsense again")
            assertThat(result.revisions.map { it.value }).contains("nonsense again")
        }
    }

    @Nested
    @DisplayName("New fields")
    inner class NewFields {

        @Test
        fun `are inserted with machine provenance and a first revision`() {
            val result = run(emptyList(), listOf(field("iban", "DE02 1203 0000 0000 2020 51")))

            val created = result.toPersist.single()
            assertThat(created.source).isEqualTo(ValueSource.MACHINE)
            assertThat(created.machineValue).isEqualTo(created.fieldValue)
            assertThat(created.engineVersion).isEqualTo("v2")
            assertThat(result.revisions.single().value).isEqualTo(created.fieldValue)
        }

        @Test
        fun `matching is by slot, not by row id`() {
            val stored = field("senderName", "Alt").copy(id = "old-row")
            val fresh = field("senderName", "Neu").copy(id = "brand-new-row")

            val result = run(listOf(stored), listOf(fresh))

            // Ids are regenerated every run; matching on them would make every run look
            // like a fresh document and duplicate every field.
            assertThat(result.toPersist).hasSize(1)
            assertThat(result.toPersist.single().id).isEqualTo("old-row")
        }
    }

    @Nested
    @DisplayName("Review queue")
    inner class Review {

        @Test
        fun `a low confidence machine value asks to be checked`() {
            assertThat(field("senderName", "Jobcenter", confidence = 0.42f).needsReview).isTrue()
        }

        @Test
        fun `a confident machine value does not`() {
            assertThat(field("senderName", "Jobcenter", confidence = 0.9f).needsReview).isFalse()
        }

        @Test
        @DisplayName("a user's own value is never 'unreviewed', however unsure the machine was")
        fun `user values are not flagged for low confidence`() {
            val corrected = field(
                "senderName",
                "Jobcenter Berlin Mitte",
                confidence = 0.1f,
                source = ValueSource.USER,
            )
            assertThat(corrected.needsReview).isFalse()
        }

        @Test
        fun `a deleted field never asks for review`() {
            assertThat(
                field("x", "y", confidence = 0.1f, deletedByUser = true).needsReview,
            ).isFalse()
        }
    }

    @Nested
    @DisplayName("User actions")
    inner class UserActions {

        @Test
        fun `editing attributes the value and records a revision`() {
            val machine = field("receiverName", "Frau Aylin Mustermann", confidence = 0.42f)

            val (updated, revision) = merge.applyUserEdit(machine, "Aylin Mustermann", now, newId)

            assertThat(updated.fieldValue).isEqualTo("Aylin Mustermann")
            assertThat(updated.source).isEqualTo(ValueSource.USER)
            assertThat(updated.isConfirmed).isTrue()
            // The machine reading is retained so a later disagreement can be detected.
            assertThat(updated.machineValue).isEqualTo("Frau Aylin Mustermann")
            assertThat(revision.source).isEqualTo(ValueSource.USER)
            assertThat(revision.value).isEqualTo("Aylin Mustermann")
        }

        @Test
        fun `editing clears an outstanding flag`() {
            val flagged = field("d", "v", source = ValueSource.USER, flagged = true)
            assertThat(merge.applyUserEdit(flagged, "v2", now, newId).first
                .hasUnreviewedMachineChange).isFalse()
        }

        @Test
        fun `accepting the machine reading adopts it and ends the disagreement`() {
            val flagged = field(
                "receiverName",
                "Aylin Mustermann",
                source = ValueSource.USER,
                machineValue = "A. Mustermann",
                flagged = true,
            )

            val (updated, revision) = merge.acceptMachineValue(flagged, now, newId)

            assertThat(updated.fieldValue).isEqualTo("A. Mustermann")
            assertThat(updated.hasUnreviewedMachineChange).isFalse()
            assertThat(updated.isConfirmed).isTrue()
            // Adopted, so protected. Otherwise the next run overwrites the value the user
            // just chose and raises the identical conflict again.
            assertThat(updated.source).isEqualTo(ValueSource.USER)
            // Recorded as the user's act, because accepting is a decision they made.
            assertThat(revision?.source).isEqualTo(ValueSource.USER)
        }

        @Test
        fun `deleting leaves a tombstone rather than removing the row`() {
            val deleted = merge.applyUserDelete(field("bogus", "nonsense"), now)

            assertThat(deleted.deletedByUser).isTrue()
        }
    }
}
