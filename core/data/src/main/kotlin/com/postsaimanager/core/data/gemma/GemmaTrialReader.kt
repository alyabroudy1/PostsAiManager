package com.postsaimanager.core.data.gemma

import android.util.Log
import com.postsaimanager.core.common.util.TimingLog
import com.postsaimanager.core.domain.ai.ChatImageStore
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderTrial
import com.postsaimanager.core.domain.extraction.gemma.GemmaReadingOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaReadingUseCase
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialReading
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialRequest
import com.postsaimanager.core.domain.extraction.gemma.shouldRead
import com.postsaimanager.core.model.DocumentUnderstanding
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The trial's seam in the document pipeline ([GemmaTrialReading]): when the switch is on (or this document was asked for), it prepares
 * the page pictures (the chat's own scaled copies, removed again afterwards), lets [GemmaReadingUseCase] read, and logs the reading for the
 * comparison with the old pipeline: one `PamTiming` line (the total, the pictures, the pages) next to the engine's own lines (prefill,
 * decode, the JSON's length), and one `DocProcessing` line of what was decided. Nothing in the logs is a word of the letter.
 *
 * Null (the old reading runs) whenever the trial is off for this reading or the answer is unavailable; the reason is logged.
 */
@Singleton
class GemmaTrialReader @Inject constructor(
    private val trial: GemmaReaderTrial,
    private val reading: GemmaReadingUseCase,
    private val images: ChatImageStore,
) : GemmaTrialReading {

    override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding? {
        // A quiet background re-read is never the trial's (and does not spend a request meant for a person's tap).
        if (request.reprocess || !trial.shouldRead(request.documentId)) return null
        val started = System.nanoTime()
        val folder = "$FOLDER_PREFIX${request.documentId}"
        try {
            val pictures = request.pageImagePaths.take(MAX_PICTURES).mapNotNull { images.import(folder, "file://$it") }
            return when (val outcome = reading(request.pages, pictures, request.pageAspect, request.forcedFamily)) {
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

        /** Pages shown to the model as pictures: the first two. Every page's text is in the lines. */
        const val MAX_PICTURES = 2
        const val NANOS_PER_MS = 1_000_000L
    }
}
