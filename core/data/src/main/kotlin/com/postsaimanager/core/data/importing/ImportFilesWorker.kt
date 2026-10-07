package com.postsaimanager.core.data.importing

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.postsaimanager.core.data.R
import com.postsaimanager.core.data.worker.DocumentProcessingNotifications
import com.postsaimanager.core.domain.importing.ImportFilesUseCase
import com.postsaimanager.core.domain.importing.PageImageSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs [ImportFilesUseCase] for one confirmed batch, expedited, so converting a large PDF into pages is not lost when the user leaves
 * the app. The reading of each new document is the ordinary processing job, queued by the use case.
 *
 * No automatic retry: a failure (a damaged file that passed the first look) would fail the same way again. The documents of the
 * batch that did import stay; the list shows that something could not be imported until the person dismisses it.
 */
@HiltWorker
class ImportFilesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val importFiles: ImportFilesUseCase,
    private val requests: ImportRequestStore,
    private val pageImages: PageImageSource,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        val request = requests.load(batchId)
        if (request == null) {
            // Nothing to do; make sure nothing is left of it.
            pageImages.discard(batchId)
            return Result.failure()
        }
        runCatching { setForeground(foregroundInfo(batchId)) }
        val outcome = importFiles(request)
        if (outcome.problems.isNotEmpty()) Log.w(TAG, "${outcome.problems.size} of ${request.groups.size} documents could not be imported")
        val output = workDataOf(
            KEY_DOCUMENT_IDS to outcome.documentIds.toTypedArray(),
            KEY_FAILED_GROUPS to outcome.problems.size,
        )
        return if (outcome.isComplete) Result.success(output) else Result.failure(output)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(inputData.getString(KEY_BATCH_ID).orEmpty())

    private fun foregroundInfo(batchId: String): ForegroundInfo {
        DocumentProcessingNotifications.ensureChannel(applicationContext)
        // The text is the same generic line whatever the lock setting: it names no file and no count.
        val notification = NotificationCompat.Builder(applicationContext, DocumentProcessingNotifications.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.import_notification_title))
            .setContentText(applicationContext.getString(R.string.import_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        val id = NOTIFICATION_ID_BASE + (batchId.hashCode() and 0xFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Same type as the processing worker; see the manifest comment on SystemForegroundService.
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        private const val TAG = "ImportFilesWorker"
        const val KEY_BATCH_ID = "batchId"
        const val KEY_DOCUMENT_IDS = "documentIds"
        const val KEY_FAILED_GROUPS = "failedGroups"
        private const val NOTIFICATION_ID_BASE = 6100
    }
}
