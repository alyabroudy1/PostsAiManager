package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.FieldRevision
import com.postsaimanager.core.model.ValueSource
import javax.inject.Inject

/**
 * Reconciles a fresh extraction against what is already stored.
 *
 * Extraction runs many times over a document's life — after a re-crop, after an app update
 * carrying a better extractor, after the user asks for another pass. Between those runs a
 * person may have corrected values, added fields the extractor missed and deleted ones it
 * invented. This decides what survives.
 *
 * ### The rules
 *
 * - **Machine values are disposable.** A new run replaces them; that is what they are for.
 * - **User values are never overwritten.** Not by a better extractor, not by a higher
 *   confidence score. Confidence says how sure the extractor is of its own reading; it says
 *   nothing about whether a person has already looked at the field and decided. If 0.81
 *   could beat a human correction, the app would argue more forcefully the more wrong it is.
 * - **Deletions stick.** A field the user removed is not resurrected on every run.
 * - **Disagreement surfaces rather than resolving itself.** When extraction reads something
 *   different from the value a user overrode, neither is obviously right — the page may have
 *   been re-cropped to reveal the real name, or the extractor may simply be wrong again. The
 *   user's value stands and the change is flagged.
 *
 * Pure: no repository, no clock, no IO. Every branch below is a decision about someone's
 * data, and each one is worth a test that does not need a database to run.
 */
class MergeExtractionUseCase @Inject constructor() {

    /**
     * @param toPersist rows to insert or update, keyed by slot
     * @param idsToDelete rows the extractor no longer produces and no user has touched
     * @param revisions history entries to append, in order
     */
    data class Outcome(
        val toPersist: List<ExtractedData>,
        val idsToDelete: List<String>,
        val revisions: List<FieldRevision>,
    ) {
        /** Slots where extraction now disagrees with the user — the review queue. */
        val newlyFlagged: List<String>
            get() = toPersist.filter { it.hasUnreviewedMachineChange }.map { it.fieldName }
    }

    operator fun invoke(
        existing: List<ExtractedData>,
        extracted: List<ExtractedData>,
        engineVersion: String,
        now: Long,
        newId: (String) -> String,
    ): Outcome {
        // Slot identity, not row identity. Ids are regenerated on every extraction run, so
        // matching by id would make every run look entirely new.
        val existingBySlot = existing.associateBy { it.fieldName }
        val extractedBySlot = extracted.associateBy { it.fieldName }

        val toPersist = mutableListOf<ExtractedData>()
        val idsToDelete = mutableListOf<String>()
        val revisions = mutableListOf<FieldRevision>()

        fun record(field: ExtractedData, value: String, source: ValueSource, confidence: Float?) {
            revisions += FieldRevision(
                id = newId("${field.documentId}#${field.fieldName}"),
                documentId = field.documentId,
                fieldName = field.fieldName,
                value = value,
                source = source,
                confidence = confidence,
                engineVersion = engineVersion.takeIf { source == ValueSource.MACHINE },
                createdAt = now,
            )
        }

        for ((slot, current) in existingBySlot) {
            val fresh = extractedBySlot[slot]

            when {
                // Tombstone. The machine reading is still recorded, so the history stays
                // complete and the user can see what it would have said — but the field
                // does not come back.
                current.deletedByUser -> {
                    val updated = current.copy(
                        machineValue = fresh?.fieldValue ?: current.machineValue,
                        machineConfidence = fresh?.confidence ?: current.machineConfidence,
                        engineVersion = if (fresh != null) engineVersion else current.engineVersion,
                        updatedAt = now,
                    )
                    toPersist += updated
                    if (fresh != null && fresh.fieldValue != current.machineValue) {
                        record(updated, fresh.fieldValue, ValueSource.MACHINE, fresh.confidence)
                    }
                }

                current.source == ValueSource.USER -> {
                    if (fresh == null) {
                        // The extractor no longer finds it, but a person put it there.
                        // Keeping it is the whole point of tracking who authored a value.
                        toPersist += current.copy(updatedAt = current.updatedAt)
                    } else {
                        val disagrees = fresh.fieldValue != current.machineValue
                        val updated = current.copy(
                            machineValue = fresh.fieldValue,
                            machineConfidence = fresh.confidence,
                            engineVersion = engineVersion,
                            // Sticky: an unresolved flag from an earlier run is not cleared
                            // by a later run that happens to agree with the previous reading.
                            hasUnreviewedMachineChange =
                                current.hasUnreviewedMachineChange || disagrees,
                            updatedAt = if (disagrees) now else current.updatedAt,
                        )
                        toPersist += updated
                        if (disagrees) {
                            record(updated, fresh.fieldValue, ValueSource.MACHINE, fresh.confidence)
                        }
                    }
                }

                // A plain machine value the extractor no longer produces. Nothing is lost
                // that a person cared about, so it goes.
                fresh == null -> idsToDelete += current.id

                else -> {
                    val changed = fresh.fieldValue != current.fieldValue
                    val updated = current.copy(
                        fieldValue = fresh.fieldValue,
                        fieldType = fresh.fieldType,
                        confidence = fresh.confidence,
                        pageNumber = fresh.pageNumber ?: current.pageNumber,
                        machineValue = fresh.fieldValue,
                        machineConfidence = fresh.confidence,
                        engineVersion = engineVersion,
                        source = ValueSource.MACHINE,
                        // A value the extractor has since changed is no longer the one the
                        // user confirmed, so the confirmation does not carry over.
                        isConfirmed = current.isConfirmed && !changed,
                        updatedAt = if (changed) now else current.updatedAt,
                    )
                    toPersist += updated
                    if (changed) {
                        record(updated, fresh.fieldValue, ValueSource.MACHINE, fresh.confidence)
                    }
                }
            }
        }

        // Slots the extractor found that are not stored at all.
        for ((slot, fresh) in extractedBySlot) {
            if (slot in existingBySlot) continue
            val created = fresh.copy(
                source = ValueSource.MACHINE,
                machineValue = fresh.fieldValue,
                machineConfidence = fresh.confidence,
                engineVersion = engineVersion,
                updatedAt = now,
            )
            toPersist += created
            record(created, created.fieldValue, ValueSource.MACHINE, created.confidence)
        }

        return Outcome(toPersist, idsToDelete, revisions)
    }

    /**
     * Applies a user's edit, recording it as theirs.
     *
     * Separate from [invoke] because an edit is not a merge — there is nothing to reconcile,
     * only a value to attribute. Going through here rather than writing the row directly is
     * what keeps `source` and the history honest.
     */
    fun applyUserEdit(
        field: ExtractedData,
        newValue: String,
        now: Long,
        newId: (String) -> String,
    ): Pair<ExtractedData, FieldRevision> {
        val updated = field.copy(
            fieldValue = newValue,
            source = ValueSource.USER,
            // Editing is a stronger statement than confirming.
            isConfirmed = true,
            // The user has now seen whatever the extractor was saying.
            hasUnreviewedMachineChange = false,
            deletedByUser = false,
            updatedAt = now,
        )
        val revision = FieldRevision(
            id = newId("${field.documentId}#${field.fieldName}"),
            documentId = field.documentId,
            fieldName = field.fieldName,
            value = newValue,
            source = ValueSource.USER,
            confidence = null,
            engineVersion = null,
            createdAt = now,
        )
        return updated to revision
    }

    /** Marks a field deleted rather than removing it, so extraction cannot bring it back. */
    fun applyUserDelete(field: ExtractedData, now: Long): ExtractedData =
        field.copy(
            deletedByUser = true,
            hasUnreviewedMachineChange = false,
            updatedAt = now,
        )

    /** The user accepted the extractor's newer reading, ending the disagreement. */
    fun acceptMachineValue(
        field: ExtractedData,
        now: Long,
        newId: (String) -> String,
    ): Pair<ExtractedData, FieldRevision?> {
        val machine = field.machineValue ?: return field.copy(
            hasUnreviewedMachineChange = false,
            updatedAt = now,
        ) to null

        val updated = field.copy(
            fieldValue = machine,
            confidence = field.machineConfidence ?: field.confidence,
            source = ValueSource.MACHINE,
            hasUnreviewedMachineChange = false,
            // Accepting is an act of review, so the value is confirmed even though the
            // machine authored it.
            isConfirmed = true,
            updatedAt = now,
        )
        return updated to FieldRevision(
            id = newId("${field.documentId}#${field.fieldName}"),
            documentId = field.documentId,
            fieldName = field.fieldName,
            value = machine,
            source = ValueSource.USER,
            confidence = field.machineConfidence,
            engineVersion = field.engineVersion,
            createdAt = now,
        )
    }
}
