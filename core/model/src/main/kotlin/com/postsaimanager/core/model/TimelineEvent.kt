package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Timeline event tracking all actions on a document.
 */
@Serializable
data class TimelineEvent(
    val id: String,
    val documentId: String,
    val eventType: TimelineEventType,
    val title: String,
    val description: String? = null,
    val data: String? = null,
    val referenceId: String? = null,
    val referenceType: String? = null,
    val createdAt: Long,
    /**
     * What happened, as a code the UI renders from string resources (see [TimelineCodes]); null for a
     * row written before events were data, whose [title] and [description] are then shown as stored.
     */
    val code: String? = null,
    /** The values the code's sentence needs (counts, field keys), as plain strings. */
    val args: List<String> = emptyList(),
    /** How many identical log entries this one stands for (never stored: the screen's log collapses them, see `CollapseProcessingLog`). */
    val repeats: Int = 1,
)

/** The codes the processing pipeline records. A new one needs a string in the UI, nothing else. */
object TimelineCodes {
    /** args: page count, average OCR confidence in percent (empty when no page produced text). */
    const val OCR_DONE = "ocr_done"

    /** args: number of fields, then their label keys (`ExtractedData.labelKey`). */
    const val FIELDS_EXTRACTED = "fields_extracted"

    /** args: number of fields, then their label keys that now differ from the user's version. */
    const val REVIEW_FLAGGED = "review_flagged"

    /** data carries the machine reason (`no_pages`, `error`); description carries the raw detail. */
    const val PROCESSING_FAILED = "processing_failed"

    /** A finished letter was quietly re-read by a newer extractor. args: the version it had (empty if none), the new one. */
    const val REPROCESSED = "reprocessed"

    /**
     * A background re-read did not finish and the letter kept its earlier data and status. args: a machine
     * reason (`no_model`, `no_pages`, `error`). Counted to allow one retry on a later start.
     */
    const val REPROCESS_FAILED = "reprocess_failed"
}

/** The [Document.titleCode] the scanner writes. args: page count. */
object DocumentTitleCodes {
    const val SCANNED_PAGES = "scanned_pages"

    /**
     * A title composed from verified fields. args: `[family id, sender, subject]`, always three, `""` for a slot the letter did not
     * give (see `TitleComposer`, which owns how it is built).
     */
    const val COMPOSED = "composed"

    /** Between the slots of a composed title. */
    const val SEPARATOR = " · "
}

@Serializable
enum class TimelineEventType {
    DOCUMENT_SCANNED,
    TEXT_EXTRACTED,
    ENTITIES_EXTRACTED,
    PROFILE_LINKED,
    PROFILE_CREATED,
    RELATION_ADDED,
    AI_CHAT_LOCAL,
    AI_CHAT_ONLINE,
    SUMMARY_GENERATED,
    DRAFT_CREATED,
    PDF_GENERATED,
    EMAIL_PREPARED,
    SHARED,
    TAG_ADDED,
    DOCUMENT_MODIFIED,
    DEADLINE_SET,
    REMINDER_SET,

    /**
     * A processing run ended without extracting anything — see
     * [com.postsaimanager.core.model.DocumentStatus.FAILED]. [TimelineEvent.data] carries a
     * short machine-readable reason code (`"no_pages"`, `"error"`) the detail screen can
     * branch on; [TimelineEvent.description] carries the human-readable detail.
     */
    PROCESSING_FAILED,
}
