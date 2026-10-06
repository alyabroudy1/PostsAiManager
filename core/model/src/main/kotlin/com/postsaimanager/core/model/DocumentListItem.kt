package com.postsaimanager.core.model

import java.time.LocalDate

/**
 * Everything one row of a document list shows, decided in `:core:domain` so the Home and Documents
 * screens only render it. The text is data (names as printed, a date, a count); the words around it
 * ("From", "Due", "Action needed") are string resources of the row.
 */
data class DocumentListItem(
    val document: Document,
    /** The image of page 1, for the thumbnail; null when the document has no page stored. */
    val firstPagePath: String?,
    /** The sender as the row names them (already passed through the party-name seam); null when unknown. */
    val sender: String?,
    /** The addressee as the row names them; null when unknown. */
    val addressee: String?,
    val status: DocumentListStatus,
    /** The one date the row shows. */
    val dateChip: DocumentDateChip,
    /** Open things to do for this document; 0 shows no badge. Today a hint from the extracted fields, later real tasks. */
    val openActionCount: Int,
    /** The managed people the document is for or about, "for" first; empty when no profile matches. */
    val people: List<PersonTag> = emptyList(),
) {
    val id: String get() = document.id
}

/** What the status icon of a row says. */
sealed interface DocumentListStatus {
    /** Not read yet: new or waiting in the queue. */
    data object Waiting : DocumentListStatus

    /** Being read now (or marked so); the row shows the pipeline's progress when it is the running document. */
    data object Processing : DocumentListStatus

    /** Read, and [count] fields are worth a look. */
    data class NeedsReview(val count: Int) : DocumentListStatus

    /** Read, nothing left to check. */
    data object Ready : DocumentListStatus

    data object Failed : DocumentListStatus
}

/** The single date chip of a row: a deadline if there is one, else the letter's own date, else the scan date. */
data class DocumentDateChip(
    val kind: Kind,
    val date: LocalDate,
    val urgency: Urgency = Urgency.NORMAL,
) {
    enum class Kind { DUE, LETTER, SCANNED }

    enum class Urgency {
        NORMAL,

        /** A deadline three days away or less. */
        SOON,

        /** A deadline that has passed. */
        OVERDUE,
    }
}
