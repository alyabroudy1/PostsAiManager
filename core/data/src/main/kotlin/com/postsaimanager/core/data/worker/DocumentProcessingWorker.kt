package com.postsaimanager.core.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [DocumentProcessor.processDocument] in the background, as a **foreground service**.
 *
 * Follows the same shape as `core.download.ModelDownloadWorker`: a document is enqueued the
 * moment it is captured (documentation/07-document-pipeline.md §7), which means it must keep
 * running after the user leaves the detail screen or backgrounds the app entirely — a plain
 * coroutine tied to a ViewModel's `viewModelScope` dies with the screen. [setForeground]
 * keeps the process alive, and the ongoing, low-importance notification is what makes that
 * legitimate.
 *
 * **Retry policy — deliberately none.** Unlike a download, a failure here (OCR error, model
 * unavailable, a malformed page) is almost always deterministic: the same document fed
 * through the same pipeline again fails the same way. Returning [Result.retry] would have
 * WorkManager re-run it on a backoff schedule with nobody watching, burning battery for a
 * document that needs a person to look at it — re-cropping a page, installing a model, or
 * just trying again once whatever was transient (e.g. low memory) has passed. The document
 * itself is never lost: [DocumentProcessingPipeline][com.postsaimanager.core.data.repository
 * .DocumentProcessingPipeline] marks it `FAILED` rather than leaving it stuck, and the detail
 * screen offers a manual retry, which is exactly [DocumentProcessor.enqueue] with `force`.
 */
@HiltWorker
class DocumentProcessingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val documentProcessor: DocumentProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val documentId = inputData.getString(KEY_DOCUMENT_ID)
            ?: return@coroutineScope Result.failure()

        // Never let a notification problem fail processing — a POST_NOTIFICATIONS denial on
        // API 33+ just means the ongoing notification is invisible, not that the document
        // stops being processed.
        runCatching { setForeground(foregroundInfo(null)) }

        // Mirrors the progress this worker's document is making into the notification.
        // Cancelled in the `finally` below regardless of how processDocument finishes.
        val progressJob = launch {
            documentProcessor.processingState.collect { state ->
                if (state is ProcessingState.Running && state.documentId == documentId) {
                    runCatching { setForeground(foregroundInfo(state)) }
                }
            }
        }

        try {
            when (documentProcessor.processDocument(documentId)) {
                is PamResult.Success -> Result.success()
                is PamResult.Error -> Result.failure()
            }
        } finally {
            progressJob.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(state: ProcessingState.Running?): ForegroundInfo {
        DocumentProcessingNotifications.ensureChannel(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Reading your document…")
            .setContentText(state?.toNotificationText() ?: "Starting…")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

        // Derived from the document id rather than a shared constant: the pipeline's mutex
        // serialises the actual work, but a second worker can already be alive and calling
        // setForeground while it waits for the lock — a shared id would have it silently
        // replace the running document's notification instead of holding its own.
        val notificationId = NOTIFICATION_ID_BASE +
            (inputData.getString(KEY_DOCUMENT_ID)?.hashCode() ?: 0)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Must match the FOREGROUND_SERVICE_DATA_SYNC permission and the merged
            // manifest's SystemForegroundService type — see AndroidManifest.xml's comment.
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * A short notification line for [state]. Duplicates a little of the sentence-building
     * `DocumentDetailScreen.toDisplayMessage` already does for the in-app banner — that one
     * cannot be reused here because a feature module owns it and `:core:data` may not depend
     * on `feature:documents`. Kept intentionally terser than the banner: a notification has
     * one line, the banner has room for more.
     */
    private fun ProcessingState.Running.toNotificationText(): String = when (stage) {
        ProcessingStage.CAPTURE -> "Preparing…"
        ProcessingStage.READ -> if (currentPage != null && totalPages != null) {
            "Reading page $currentPage of $totalPages"
        } else {
            "Reading…"
        }
        ProcessingStage.UNDERSTAND -> "Analysing…"
        ProcessingStage.LINK -> "Matching profiles…"
        ProcessingStage.INDEX -> "Indexing for search…"
    }

    companion object {
        const val CHANNEL_ID = DocumentProcessingNotifications.CHANNEL_ID
        const val NOTIFICATION_ID_BASE = 5711
        const val KEY_DOCUMENT_ID = "documentId"

        /**
         * One unique work name per document, so opening the same `NEW` document twice, or a
         * startup recovery re-enqueue racing a user-triggered one, joins rather than
         * duplicates — see [DocumentProcessor.enqueue].
         */
        fun workName(documentId: String) = "process-document-$documentId"
    }
}
