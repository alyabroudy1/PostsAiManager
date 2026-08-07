package com.postsaimanager.core.ai.catalog.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.postsaimanager.core.common.result.PamResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Downloads a model in the background, as a **foreground service**.
 *
 * A 0.4–2 GB download cannot rely on the app staying in the foreground: the user will
 * switch away, and Android will kill a background process mid-transfer. Promoting the work
 * via [setForeground] keeps the process alive, and the ongoing notification is what makes
 * that legitimate — the user can see a long-running transfer and cancel it.
 *
 * This finally uses the `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and
 * `POST_NOTIFICATIONS` permissions the manifest has declared since the first commit for a
 * service that was never written.
 *
 * Progress survives process death regardless: it lives in the `.part` file on disk, so even
 * a killed download resumes from where the bytes stopped (see [ResumePolicy]).
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloader: ModelDownloader,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(
            errorData("No download URL supplied"),
        )
        val sha256 = inputData.getString(KEY_SHA256) ?: return Result.failure(
            errorData("No integrity hash supplied — refusing to download"),
        )
        val destinationPath = inputData.getString(KEY_DESTINATION) ?: return Result.failure(
            errorData("No destination supplied"),
        )
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "model"
        val expectedSize = inputData.getLong(KEY_SIZE_BYTES, -1L).takeIf { it > 0 }

        setForeground(foregroundInfo(modelName, progress = null))

        val result = downloader.download(
            url = url,
            destination = File(destinationPath),
            expectedSha256 = sha256,
            expectedSize = expectedSize,
        ) { progress ->
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_BYTES to progress.bytesDownloaded,
                    KEY_PROGRESS_TOTAL to (progress.totalBytes ?: -1L),
                ),
            )
        }

        return when (result) {
            is PamResult.Success -> Result.success(
                workDataOf(KEY_DESTINATION to result.data.absolutePath),
            )
            // Retryable: the partial file is preserved, so a retry resumes rather than
            // restarting. WorkManager applies its own backoff.
            is PamResult.Error -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(KEY_MODEL_NAME) ?: "model", progress = null)

    private fun foregroundInfo(modelName: String, progress: Int?): ForegroundInfo {
        ensureChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading $modelName")
            .setContentText("The model is downloading in the background.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress != null) setProgress(100, progress, false)
                else setProgress(0, 0, true)
            }
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Required from API 29 and enforced from 34: a foreground service must declare
            // its type, and it must match the manifest permission.
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Model downloads",
                // LOW: an ongoing transfer should be visible, not intrusive.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for AI models downloading in the background."
                setShowBadge(false)
            },
        )
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        const val CHANNEL_ID = "model_downloads"
        const val NOTIFICATION_ID = 4711

        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_DESTINATION = "destination"
        const val KEY_MODEL_NAME = "modelName"
        const val KEY_SIZE_BYTES = "sizeBytes"
        const val KEY_PROGRESS_BYTES = "progressBytes"
        const val KEY_PROGRESS_TOTAL = "progressTotal"
        const val KEY_ERROR = "error"

        /** One unique work name per model, so a re-enqueue joins rather than duplicates. */
        fun workName(modelId: String) = "model-download-$modelId"
    }
}
