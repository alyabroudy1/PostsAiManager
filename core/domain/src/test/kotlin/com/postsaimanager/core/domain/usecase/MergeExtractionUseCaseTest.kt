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
        slotKey: String? = null,
        id: String = "id-$name",
    ) = ExtractedData(
        slotKey = slotKey,
        id = id,
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
        @DisplayName("a normal run keeps a confirmed machine value and flags the new reading")
        fun `a confirmed machine value is protected`() {
            val confirmed = field("date", "31.01.2026", isConfirmed = true).copy(updatedAt = 5L)
            val fresh = field("date", "28.02.2026")

            val result = run(listOf(confirmed), listOf(fresh))
            val merged = result.toPersist.single()

            assertThat(merged.fieldValue).isEqualTo("31.01.2026")
            assertThat(merged.isConfirmed).isTrue()
            assertThat(merged.machineValue).isEqualTo("28.02.2026")
            assertThat(merged.hasUnreviewedMachineChange).isTrue()
            assertThat(result.newlyFlagged).containsExactly("date")
        }

        @Test
        @DisplayName("a run does not delete a confirmed value the new reading dropped")
        fun `a confirmed value the extractor no longer finds is kept`() {
            val confirmed = field("date", "31.01.2026", isConfirmed = true)

            val result = run(listOf(confirmed), emptyList())

            assertThat(result.idsToDelete).isEmpty()
            assertThat(result.toPersist.single().fieldValue).isEqualTo("31.01.2026")
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

    @Nested
    @DisplayName("Matching a fresh reading to a stored row")
    inner class Matching {

        @Test
        fun `a slot key match wins over the name, so a reworded field is the same row`() {
            val stored = field("Amount", "64,98 €", slotKey = "total", id = "row-1")
            val fresh = field("Total due", "70,00 €", slotKey = "total", id = "new-1")

            val outcome = run(listOf(stored), listOf(fresh))

            assertThat(outcome.idsToDelete).isEmpty()
            val row = outcome.toPersist.single()
            assertThat(row.id).isEqualTo("row-1")
            assertThat(row.fieldName).isEqualTo("Total due")
            assertThat(row.fieldValue).isEqualTo("70,00 €")
            assertThat(row.slotKey).isEqualTo("total")
        }

        @Test
        fun `the sender changing from a name to an organisation renames the row`() {
            val stored = field("Sender Name", "Nordlicht", slotKey = "sender", id = "row-s")
            val fresh = field("Sender Organization", "Nordlicht Mobilfunk GmbH", slotKey = "sender", id = "new-s")

            val outcome = run(listOf(stored), listOf(fresh))

            assertThat(outcome.toPersist.single().id).isEqualTo("row-s")
            assertThat(outcome.toPersist.single().fieldName).isEqualTo("Sender Organization")
            assertThat(outcome.idsToDelete).isEmpty()
        }

        @Test
        fun `a row stored before slot keys is matched by name and gains its slot key`() {
            val stored = field("Deadline", "01.10.2026", id = "row-d")
            val fresh = field("Deadline", "15.10.2026", slotKey = "due_date", id = "new-d")

            val row = run(listOf(stored), listOf(fresh)).toPersist.single()

            assertThat(row.id).isEqualTo("row-d")
            assertThat(row.slotKey).isEqualTo("due_date")
            assertThat(row.fieldValue).isEqualTo("15.10.2026")
        }

        @Test
        fun `an extra whose printed label changed but whose value is the same is a rename, not a delete and a create`() {
            val stored = field("Zaehlernummer", "1EMH0012345678", slotKey = "x:zaehlernummer", id = "row-x")
            val fresh = field("Zähler-Nr.", "1EMH0012345678", slotKey = "x:zahler_nr", id = "new-x")

            val outcome = run(listOf(stored), listOf(fresh))

            assertThat(outcome.idsToDelete).isEmpty()
            val row = outcome.toPersist.single()
            assertThat(row.id).isEqualTo("row-x")
            assertThat(row.fieldName).isEqualTo("Zähler-Nr.")
            assertThat(row.slotKey).isEqualTo("x:zahler_nr")
        }

        @Test
        fun `the value match only applies to a lone machine row, never when two rows share the value`() {
            val a = field("Alpha", "12,00", slotKey = "x:alpha", id = "row-a")
            val b = field("Beta", "12,00", slotKey = "x:beta", id = "row-b")
            val fresh = field("Gamma", "12,00", slotKey = "x:gamma", id = "new-g")

            val outcome = run(listOf(a, b), listOf(fresh))

            assertThat(outcome.idsToDelete).containsExactly("row-a", "row-b")
            assertThat(outcome.toPersist.map { it.id }).containsExactly("new-g")
        }

        @Test
        fun `a rename that would take another stored row's name keeps the old name`() {
            val stored = field("Sender Name", "Nordlicht", slotKey = "sender", id = "row-s")
            val other = field("Sender Organization", "Something the user typed", source = ValueSource.USER, id = "row-u")
            val fresh = field("Sender Organization", "Nordlicht GmbH", slotKey = "sender", id = "new-s")

            val outcome = run(listOf(stored, other), listOf(fresh))

            assertThat(outcome.toPersist.map { it.fieldName }).containsNoDuplicates()
            assertThat(outcome.toPersist.single { it.id == "row-s" }.fieldName).isEqualTo("Sender Name")
        }

        @Test
        fun `a user row is not renamed or re-valued by a value match or a slot match`() {
            val mine = field(
                "Amount", "70,00 €", source = ValueSource.USER, machineValue = "64,98 €", slotKey = "total", id = "row-m",
            ).copy(role = "USER_ROLE", origin = null, aiConfidence = null)
            val fresh = field("Total due", "80,00 €", slotKey = "total", id = "new-m")

            val row = run(listOf(mine), listOf(fresh)).toPersist.single()

            assertThat(row.id).isEqualTo("row-m")
            assertThat(row.fieldName).isEqualTo("Amount")
            assertThat(row.fieldValue).isEqualTo("70,00 €")
            assertThat(row.source).isEqualTo(ValueSource.USER)
            assertThat(row.slotKey).isEqualTo("total")
            assertThat(row.role).isEqualTo("USER_ROLE")
            // The disagreement is flagged, as before; the value is not touched.
            assertThat(row.machineValue).isEqualTo("80,00 €")
            assertThat(row.hasUnreviewedMachineChange).isTrue()
        }

        @Test
        fun `a user row is never taken as a rename target for a same-valued machine reading`() {
            val mine = field("My note", "12,00", source = ValueSource.USER, machineValue = null, id = "row-m")
            val fresh = field("Fee", "12,00", slotKey = "fee", id = "new-f")

            val outcome = run(listOf(mine), listOf(fresh))

            assertThat(outcome.toPersist.map { it.id }).containsExactly("row-m", "new-f")
            assertThat(outcome.toPersist.single { it.id == "row-m" }.fieldName).isEqualTo("My note")
        }

        @Test
        fun `both the model's confidence and the final one are stored, with the evidence`() {
            val stored = field("Amount", "1", slotKey = "total", id = "row-1")
            val fresh = field("Amount", "2", confidence = 0.4f, slotKey = "total", id = "new-1").copy(
                aiConfidence = 0.9f, evidence = "Gesamt 2", role = "TOTAL_DUE", origin = "MODEL_CHOICE",
            )

            val row = run(listOf(stored), listOf(fresh)).toPersist.single()

            assertThat(row.confidence).isEqualTo(0.4f)
            assertThat(row.aiConfidence).isEqualTo(0.9f)
            assertThat(row.evidence).isEqualTo("Gesamt 2")
            assertThat(row.origin).isEqualTo("MODEL_CHOICE")
            assertThat(row.machineConfidence).isEqualTo(0.4f)
        }

        @Test
        fun `a deleted field stays deleted and is matched by its slot key too`() {
            val tomb = field("Amount", "1", slotKey = "total", deletedByUser = true, id = "row-t")
            val fresh = field("Total due", "2", slotKey = "total", id = "new-t")

            val outcome = run(listOf(tomb), listOf(fresh))

            assertThat(outcome.toPersist.single().id).isEqualTo("row-t")
            assertThat(outcome.toPersist.single().deletedByUser).isTrue()
            assertThat(outcome.toPersist.single().fieldName).isEqualTo("Amount")
        }
    }
}
