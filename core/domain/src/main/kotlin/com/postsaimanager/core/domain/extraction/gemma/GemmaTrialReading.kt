package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.OcrBlock

/**
 * What the document pipeline hands the trial for one reading: the OCR blocks of every page (page 1 first), the files of the stored page
 * pictures, the shape of page 1 and the category a person chose, if any.
 *
 * @property reprocess a quiet background re-read of a finished letter: never part of the trial, which reads new documents and the ones a
 *   person asked it to read again
 */
class GemmaTrialRequest(
    val documentId: String,
    val pages: List<List<OcrBlock>>,
    val pageImagePaths: List<String>,
    val pageAspect: Float?,
    val forcedFamily: String?,
    val reprocess: Boolean,
)

/**
 * The seam between the document pipeline and the trial: the pipeline asks it for a reading first, and runs the one it always had when
 * the answer is null. Null whenever the trial is off for this reading, or Gemma could not read it (not installed, busy, failed, too
 * slow, an answer that could not be used): a document is never left unread because of the trial.
 */
interface GemmaTrialReading {

    suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding?

    companion object {
        /** No trial: the pipeline reads as it always did. */
        val NONE: GemmaTrialReading = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding? = null
        }
    }
}
