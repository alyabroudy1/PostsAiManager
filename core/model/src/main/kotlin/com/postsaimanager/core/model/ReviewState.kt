package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Where a field stands with the person who owns the document. The single owner of review state:
 * `ExtractedData.isConfirmed` and `deletedByUser` are kept in step with it for older callers.
 *
 * Stored as its `name` in `extracted_data.reviewState`.
 */
@Serializable
enum class ReviewState {
    /** Nobody has looked at it. Extraction may replace it on a re-read. */
    UNREVIEWED,

    /** A person accepted the extracted value as it is. Protected from a re-read. */
    CONFIRMED,

    /** A person typed or chose the value. Protected from a re-read. */
    EDITED,

    /** A person removed the field. A tombstone: a re-read never brings it back. */
    IGNORED,
    ;

    companion object {
        /**
         * The state implied by the pre-v15 flags, for a row or a caller that only knows those. A confirmation also set
         * the source to USER, so a confirmed person's value is EDITED only when it differs from the machine's ([valueChanged]).
         */
        fun fromFlags(isConfirmed: Boolean, deletedByUser: Boolean, source: ValueSource, valueChanged: Boolean = true): ReviewState = when {
            deletedByUser -> IGNORED
            source == ValueSource.USER && (valueChanged || !isConfirmed) -> EDITED
            isConfirmed -> CONFIRMED
            else -> UNREVIEWED
        }

        /** Reads a stored name; an unknown one reads as [UNREVIEWED]. */
        fun parse(name: String?): ReviewState =
            entries.firstOrNull { it.name == name } ?: UNREVIEWED
    }
}
