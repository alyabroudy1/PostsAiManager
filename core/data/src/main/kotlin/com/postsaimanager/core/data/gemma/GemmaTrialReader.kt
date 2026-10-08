package com.postsaimanager.core.data.gemma

import android.util.Log
import com.postsaimanager.core.common.util.TimingLog
import com.postsaimanager.core.domain.ai.ChatImageStore
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderTrial
import com.postsaimanager.core.domain.extraction.gemma.GemmaReadingOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaReadingUseCase
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextWriter
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsRequest
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialReading
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialRequest
import com.postsaimanager.core.domain.extraction.gemma.shouldRead
import com.postsaimanager.core.model.DocumentUnderstanding
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Gemma reader's seam in the document pipeline ([GemmaTrialReading]): unless the debug switch chose the old reader, it prepares
 * the page pictures (the chat's own scaled copies, removed again afterwards), lets [GemmaReadingUseCase] read, and logs the reading for the
 * comparison with the old pipeline: one `PamTiming` line (the total, the pictures, the pages) next to the engine's own lines (prefill,
 * decode, the JSON's length), and one `DocProcessing` line of what was decided. Nothing in the logs is a word of the letter.
 *
 * Null (the old reading runs) whenever the old reader was chosen or Gemma's answer is unavailable (not installed, busy, failed, too slow,
 * unusable JSON); the reason is logged.
 */
@Singleton
class GemmaTrialReader @Inject constructor(
    private val trial: GemmaReaderTrial,
    private val reading: GemmaReadingUseCase,
    private val images: ChatImageStore,
    private val texts: GemmaTextWriter,
) : GemmaTrialReading {

    /**
     * The second step: the summary and the key facts of a stored Gemma reading ([GemmaTextWriter]). Written for a reading whose own ticket
     * says Gemma read it, and for one whose ticket was lost while Gemma is the chosen reader; not for a document the old reader read.
     * The log gets one `PamTiming` line (the milliseconds, what was kept) and never a word of the letter.
     */
    override suspend fun writeTexts(request: GemmaTextsRequest): GemmaTextsOutcome {
        if (!request.oneGo && !trial.isEnabled()) return GemmaTextsOutcome.NotGemma
        return when (val outcome = texts.write(request.text)) {
            is GemmaTextOutcome.Written -> {
                TimingLog.log(
                    "reader: ${request.documentId} gemma texts total=${outcome.ms}ms summary=${outcome.summary.origin} keyInfo=${outcome.keyInfo.size}",
                )
                outcome.notes.forEach { Log.i(TAG, "gemma texts ${request.documentId}: $it") }
                GemmaTextsOutcome.Done(outcome)
            }
            is GemmaTextOutcome.Unavailable -> {
                TimingLog.log("reader: ${request.documentId} gemma texts unavailable: ${outcome.reason}")
                GemmaTextsOutcome.Done(outcome)
            }
        }
    }

    override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding? {
        // Gemma is the default reader; only the debug switch "Qwen scorer (old)" leaves the reading to the old pipeline. A quiet background
        // re-read reads with Gemma too: it already waits behind every reading a person is waiting for (the pipeline's ProcessingLock).
        if (!trial.shouldRead(request.documentId)) return null
        val started = System.nanoTime()
        val folder = "$FOLDER_PREFIX${request.documentId}"
        try {
            val pictures = request.pageImagePaths.take(MAX_PICTURES).mapNotNull { images.import(folder, "file://$it", longSide = PICTURE_LONG_SIDE) }
            return when (val outcome = reading(request.pages, pictures, request.pageAspect, request.forcedFamily, request.onSummary, keepOpenAs = request.documentId)) {
                is GemmaReadingOutcome.Read -> {
                    val ms = (System.nanoTime() - started) / NANOS_PER_MS
                    TimingLog.log("reader: ${request.documentId} gemma reading total=${ms}ms pages=${request.pages.size} pictures=${pictures.size} image=${if (pictures.isEmpty()) "no" else "yes"}")
                    Log.i(TAG, "gemma read ${request.documentId}: ${outcome.summary}")
                    outcome.understanding
                }
                is GemmaReadingOutcome.Unavailable -> {
                    TimingLog.log("reader: ${request.documentId} gemma unavailable after ${(System.nanoTime() - started) / NANOS_PER_MS}ms: ${outcome.reason}")
                    Log.w(TAG, "gemma reader unavailable for ${request.documentId} (${outcome.reason}); the usual reading runs")
                    null
                }
            }
        } finally {
            images.deleteAll(folder)
        }
    }

    private companion object {
        const val TAG = "DocProcessing"
        const val FOLDER_PREFIX = "gemma-reading-"

        /** Pages shown to the model as pictures: the first one. Every page's text is in the lines; the picture carries the layout. */
        const val MAX_PICTURES = 1

        /** The long side of the page picture in pixels: a page's layout needs no more, and every picture token is prefilled at 60 to 85 a second. */
        const val PICTURE_LONG_SIDE = 768
        const val NANOS_PER_MS = 1_000_000L
    }
}
