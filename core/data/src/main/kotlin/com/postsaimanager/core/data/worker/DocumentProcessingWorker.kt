package com.postsaimanager.core.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.ProcessingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
    private val userPreferencesRepository: UserPreferencesRepository,
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
            val forcedFamily = inputData.getString(KEY_FORCED_FAMILY)?.takeIf { it.isNotBlank() }
            when (val result = documentProcessor.processDocument(documentId, forcedFamily = forcedFamily)) {
                is PamResult.Success -> Result.success()
                is PamResult.Error -> {
                    // The pipeline itself already logged (and recorded on the timeline) the
                    // specific reason via `failDocument` — this line is what turns
                    // logcat's bare "Worker result FAILURE" into something that names the
                    // document and says why, without having to cross-reference the two logs.
                    Log.w(TAG, "processing failed for $documentId: ${result.error.userMessage}")
                    Result.failure()
                }
            }
        } finally {
            progressJob.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private suspend fun foregroundInfo(state: ProcessingState.Running?): ForegroundInfo {
        DocumentProcessingNotifications.ensureChannel(applicationContext)

        // Read fresh each time so turning the app lock on mid-run takes effect at the next
        // update. A failed read means "not locked", the same default as the stored value.
        val discreet = runCatching {
            userPreferencesRepository.getUserPreferences().first().biometricEnabled
        }.getOrDefault(false)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(DocumentProcessingNotifications.TITLE))
            .setContentText(DocumentProcessingNotifications.progressText(state, discreet).resolve(applicationContext))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            // Progress is not shown on a locked screen; the same generic line is.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setContentTitle(applicationContext.getString(DocumentProcessingNotifications.TITLE))
                    .setContentText(DocumentProcessingNotifications.DISCREET_TEXT.resolve(applicationContext))
                    .setSmallIcon(android.R.drawable.ic_menu_edit)
                    .build(),
            )
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

    companion object {
        private const val TAG = "DocProcessingWorker"
        const val CHANNEL_ID = DocumentProcessingNotifications.CHANNEL_ID
        const val NOTIFICATION_ID_BASE = 5711
        const val KEY_DOCUMENT_ID = "documentId"

        /** The family a person chose for "Read again as ..."; empty or absent for every other run. */
        const val KEY_FORCED_FAMILY = "forcedFamily"

        /**
         * One unique work name per document, so opening the same `NEW` document twice, or a
         * startup recovery re-enqueue racing a user-triggered one, joins rather than
         * duplicates — see [DocumentProcessor.enqueue].
         */
        fun workName(documentId: String) = "process-document-$documentId"
    }
}
