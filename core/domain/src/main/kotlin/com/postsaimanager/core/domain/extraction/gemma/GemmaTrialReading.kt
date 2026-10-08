package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.OcrBlock

/**
 * What the document pipeline hands the trial for one reading: the OCR blocks of every page (page 1 first), the files of the stored page
 * pictures, the shape of page 1 and the category a person chose, if any.
 *
 * @property reprocess a quiet background re-read of a finished letter (after an extractor version bump); Gemma reads it too, behind every
 *   reading a person is waiting for
 */
class GemmaTrialRequest(
    val documentId: String,
    val pages: List<List<OcrBlock>>,
    val pageImagePaths: List<String>,
    val pageAspect: Float?,
    val forcedFamily: String?,
    val reprocess: Boolean,
    /** Called with the summary the moment the reader's first turn has written it, long before the reading is finished. */
    val onSummary: (suspend (EarlySummary) -> Unit)? = null,
)

/**
 * The summary a reading's first turn wrote, with what the summary gate said of it.
 *
 * @property checked the summary passed every check of [com.postsaimanager.core.domain.extraction.text.SummaryGate]; when false it is
 *   stored as "to check" (never lost: the text step that follows replaces it with a verified one when it can write one)
 * @property verdict the gate's verdict in words for the log (accepted, or the reason it was not), never a word of the letter
 */
class EarlySummary(val text: String, val checked: Boolean, val verdict: String)

/**
 * The seam between the document pipeline and the trial: the pipeline asks it for a reading first, and runs the one it always had when
 * the answer is null. Null whenever the trial is off for this reading, or Gemma could not read it (not installed, busy, failed, too
 * slow, an answer that could not be used): a document is never left unread because of the trial.
 */
interface GemmaTrialReading {

    suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding?

    /**
     * The second step of a Gemma reading, after it is stored: the summary and the key facts ([GemmaTextWriter]).
     * [GemmaTextsOutcome.NotGemma] when the old reader is the one chosen and the reading was not Gemma's.
     */
    suspend fun writeTexts(request: GemmaTextsRequest): GemmaTextsOutcome = GemmaTextsOutcome.NotGemma

    companion object {
        /** No trial: the pipeline reads as it always did. */
        val NONE: GemmaTrialReading = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding? = null
        }
    }
}

/**
 * What the pipeline hands the text step for one stored reading.
 *
 * @property oneGo the reading's own ticket says Gemma read it (a one-off "read again with Gemma" included): the step then writes whatever
 *   the switch says. Without a ticket (it was lost with the process) the step writes only while Gemma is the chosen reader.
 */
class GemmaTextsRequest(val documentId: String, val text: GemmaTextRequest, val oneGo: Boolean)

sealed interface GemmaTextsOutcome {

    /** The reading is not Gemma's: the usual second stage writes the texts. */
    data object NotGemma : GemmaTextsOutcome

    class Done(val outcome: GemmaTextOutcome) : GemmaTextsOutcome
}
