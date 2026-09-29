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

    /** Which extractor produced [machineValue]. */
    val engineVersion: String? = null,

    val updatedAt: Long = 0L,
) {
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
        /**
         * Below this, a machine value is flagged for review.
         *
         * **Currently inert, and honestly so.** `EntityExtractor` does not measure
         * confidence — it assigns a constant per field kind: every `Receiver Name` is
         * 0.80 whether it read "Aylin Mustermann" or, as it did on a real scan, the bare
         * salutation "Frau". The values it emits span 0.70 to 0.95, so no threshold below
         * 0.70 can ever fire and any threshold above it flags whole categories of field
         * regardless of whether they are right.
         *
         * The number is kept at a defensible level rather than tuned to make something
         * happen, because tuning it against constants would only encode which *kinds* of
         * field the extractor guesses about — not which values are likely wrong. It starts
         * being useful the moment extraction reports evidence instead of a category, and
         * nothing above this line has to change when it does.
         *
         * The other half of [needsReview] — the extractor disagreeing with a value the user
         * set — does not depend on confidence and works today.
         */
        const val LOW_CONFIDENCE = 0.6f
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
