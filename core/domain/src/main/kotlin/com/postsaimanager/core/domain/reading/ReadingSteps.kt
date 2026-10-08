package com.postsaimanager.core.domain.reading

import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ReadingStage
import com.postsaimanager.core.model.ReadingStep

/**
 * The one owner of "which step of the reading is this document at", for the list's step label ("Reading text…", "Reading details…",
 * "Almost done…"). Derived from the stored [ReadingStage] and status, never from a timer or a percentage.
 *
 * A document that is not being read (waiting in the queue, failed, finished, read before stages existed, or quietly re-read after an
 * extractor version bump, which never moves its stage) has no step.
 */
object ReadingSteps {

    fun of(document: Document): ReadingStep? = when (document.status) {
        // Being read: the stage says how far. Null means the pages are still being read.
        DocumentStatus.PROCESSING -> when (document.readingStage) {
            null -> ReadingStep.READING_TEXT
            ReadingStage.TEXT_READY -> ReadingStep.READING_DETAILS
            ReadingStage.FIELDS_READY -> ReadingStep.ALMOST_DONE
            ReadingStage.UNDERSTOOD -> null
        }
        // The first stage is stored and shown as read; the second stage is still owed.
        DocumentStatus.EXTRACTED -> ReadingStep.ALMOST_DONE.takeIf { document.readingStage == ReadingStage.FIELDS_READY }
        else -> null
    }

    /** Whether the reading of [document] is still going on, from the first page read to the second stage written. */
    fun isUnfinished(document: Document): Boolean = of(document) != null
}
