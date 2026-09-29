package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/** Who authored a value. */
@Serializable
enum class ValueSource {
    /** Produced by extraction. Freely replaced when extraction runs again. */
    MACHINE,

    /** Typed, corrected or accepted by a person. Never overwritten by extraction. */
    USER,
}

/**
 * Data extracted from a document by the OCR + AI pipeline.
 *
 * A field is a **slot** identified by `(documentId, fieldName)`, not by [id]. Extraction
 * runs many times over a document's life — after a re-crop, after an app update with a
 * better extractor — and each run has to be matched against what is already stored. An
 * identity that changes every run cannot do that.
 */
@Serializable
data class ExtractedData(
    val id: String,
    val documentId: String,
    val fieldName: String,
    val fieldValue: String,
    val fieldType: ExtractedFieldType,
    val confidence: Float,
    val pageNumber: Int? = null,
    val isConfirmed: Boolean = false,

    /** Who authored [fieldValue]. */
    val source: ValueSource = ValueSource.MACHINE,

    /**
     * The most recent value extraction produced for this slot, kept even after a person has
     * overridden it.
     *
     * This is what makes re-extraction a merge rather than a guess: without it, a new value
     * that differs from the stored one is ambiguous between "the extractor changed its
     * mind" and "a person corrected it and the extractor is still wrong the same way".
     */
    val machineValue: String? = null,

    val machineConfidence: Float? = null,

    /** The user removed this field. Extraction must not resurrect it. */
    val deletedByUser: Boolean = false,

    /** Extraction now disagrees with the value the user set. Surfaced, not auto-resolved. */
    val hasUnreviewedMachineChange: Boolean = false,

    /** Which extractor produced [machineValue]. The stored "extractor version" of this row. */
    val engineVersion: String? = null,

    val updatedAt: Long = 0L,

    /**
     * The stable key of the slot this value fills (`total`, `due_date`, `sender`, or `x:` plus the
     * printed label for an open extra). Null for a row a person added and for rows read by an
     * older extractor. It is the identity a re-read is matched by, ahead of [fieldName], and the
     * key the UI renders a label from, so [fieldName] can be reworded without losing the row.
     */
    val slotKey: String? = null,

    /** What the model said the value is (an amount or date role, or a party role); null when it did not say. */
    val role: String? = null,

    /** How the value was obtained (`MODEL_CHOICE`, `MODEL_QUOTED`, `MODEL_GENERATED`, `FOUND`); null for a person's row. */
    val origin: String? = null,

    /** What the model said about its own answer, kept unchanged next to the final [confidence] for calibration. */
    val aiConfidence: Float? = null,

    /** The text of the page the value was read from. */
    val evidence: String? = null,

    /** Where on [pageNumber] the evidence sits, in the page's own scale-free coordinates. */
    val bbox: TextBounds? = null,
) {
    /** True for an open extra: something the model found that no fixed slot covers, keyed by its printed label. */
    val isExtra: Boolean get() = slotKey?.startsWith(EXTRA_KEY_PREFIX) == true

    /**
     * What a screen or a log entry renders a label from: the slot key of a fixed slot (a string
     * resource per key), else [fieldName] (an extra keeps the label the letter printed; a person's
     * or an older row has only its name).
     */
    val labelKey: String get() = slotKey?.takeUnless { it.startsWith(EXTRA_KEY_PREFIX) } ?: fieldName

    /**
     * Worth the user's eye.
     *
     * Low confidence is where corrections come from, so it routes attention — but it
     * carries no authority once someone has touched the field. A user value is never
     * "unreviewed" however unsure the extractor was about the value it replaced.
     */
    val needsReview: Boolean
        get() = !deletedByUser &&
            (
                hasUnreviewedMachineChange ||
                    (source == ValueSource.MACHINE && !isConfirmed && confidence < LOW_CONFIDENCE)
                )

    companion object {
        /** [slotKey]s of open extras start with this, followed by the folded printed label. */
        const val EXTRA_KEY_PREFIX = "x:"

        /**
         * Below this, a machine value is flagged for review ("worth checking").
         *
         * Since extraction v2 the confidence is honest: the model's own word (LOW 0.4, MEDIUM 0.7,
         * HIGH 0.9) capped by what the code's checks found, and never raised by them. So a
         * medium-confidence answer and anything a check failed are flagged, a high one is not.
         * Kept equal to `ConfidenceCombiner.REVIEW_BELOW` in `:core:domain`, which flags the same
         * values while extraction runs.
         *
         * The other half of [needsReview] — the extractor disagreeing with a value the user
         * set — does not depend on confidence.
         */
        const val LOW_CONFIDENCE = 0.75f
    }
}

/**
 * One entry in a field's history.
 *
 * Append-only. [ExtractedData] holds the current effective value; this records how it got
 * there — what extraction first read, what the user changed it to, and what later runs
 * said. Enough to explain a conflict to the person who has to resolve it, and to revert.
 */
@Serializable
data class FieldRevision(
    val id: String,
    val documentId: String,
    val fieldName: String,
    val value: String,
    val source: ValueSource,
    val confidence: Float? = null,
    val engineVersion: String? = null,
    val createdAt: Long,
)

@Serializable
enum class ExtractedFieldType {
    TEXT,
    DATE,
    ADDRESS,
    REFERENCE_NUMBER,
    PERSON_NAME,
    ORGANIZATION,
    PHONE,
    EMAIL,
    IBAN,
    SUBJECT,
    DEADLINE,
    TAG_SUGGESTION,
    OTHER,
}

/**
 * Structured extraction result from the AI-enhanced pipeline.
 */
@Serializable
data class ExtractionResult(
    val documentId: String,
    val language: String?,
    val sender: ExtractedContact? = null,
    val receiver: ExtractedContact? = null,
    val subject: String? = null,
    val date: String? = null,
    val referenceNumbers: List<String> = emptyList(),
    val deadline: String? = null,
    val summary: String? = null,
    val documentType: DocumentType? = null,
    val suggestedTags: List<String> = emptyList(),
    val mentionedPersons: List<MentionedPerson> = emptyList(),
    val fields: List<ExtractedData> = emptyList(),
)

@Serializable
data class ExtractedContact(
    val name: String? = null,
    val organization: String? = null,
    val department: String? = null,
    val street: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val reference: String? = null,
)

@Serializable
data class MentionedPerson(
    val name: String,
    val role: String,
)
