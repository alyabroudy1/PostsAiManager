package com.postsaimanager.feature.documents

import com.postsaimanager.core.model.TimelineCodes
import com.postsaimanager.core.model.TimelineEvent

/**
 * What a timeline event says, as data: the event's code and numbers understood, not yet worded. The
 * screen words it from string resources (plurals included) in the user's language; [Stored] is an
 * event written before events were data, shown with the sentence it was stored with.
 */
sealed interface TimelineText {
    data class OcrDone(val pages: Int, val confidencePercent: Int?) : TimelineText
    data class FieldsExtracted(val count: Int, val labelKeys: List<String>) : TimelineText
    data class ReviewFlagged(val count: Int, val labelKeys: List<String>) : TimelineText
    data class ProcessingFailed(val detail: String?) : TimelineText
    /** A finished letter was quietly re-read by a newer extractor; [reason]-free, just the fact. */
    data object Reprocessed : TimelineText

    /** A background re-read did not finish; the letter kept its data. [reason] is a machine code. */
    data class ReprocessFailed(val reason: String?) : TimelineText
    data class Stored(val title: String, val description: String?) : TimelineText
}

/** Reads an event's code and args; anything it cannot read falls back to the stored sentence. */
fun TimelineEvent.toText(): TimelineText {
    val stored = TimelineText.Stored(title, description)
    return when (code) {
        TimelineCodes.OCR_DONE -> {
            val pages = args.getOrNull(0)?.toIntOrNull() ?: return stored
            TimelineText.OcrDone(pages, args.getOrNull(1)?.toIntOrNull())
        }
        TimelineCodes.FIELDS_EXTRACTED -> {
            val count = args.getOrNull(0)?.toIntOrNull() ?: return stored
            TimelineText.FieldsExtracted(count, args.drop(1))
        }
        TimelineCodes.REVIEW_FLAGGED -> {
            val count = args.getOrNull(0)?.toIntOrNull() ?: return stored
            TimelineText.ReviewFlagged(count, args.drop(1))
        }
        // The raw detail is an exception message or a reason in English; it stays as a diagnostic under the title.
        TimelineCodes.PROCESSING_FAILED -> TimelineText.ProcessingFailed(description)
        TimelineCodes.REPROCESSED -> TimelineText.Reprocessed
        TimelineCodes.REPROCESS_FAILED -> TimelineText.ReprocessFailed(args.firstOrNull())
        else -> stored
    }
}
