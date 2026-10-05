package com.postsaimanager.core.download

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.postsaimanager.core.common.result.PamResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
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
    private val center: DownloadNotificationCenter,
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
        val itemId = inputData.getString(KEY_MODEL_ID) ?: destinationPath

        center.progress(itemId, modelName, 0L, expectedSize ?: 0L)
        setForeground(center.foregroundInfo())

        // A write per 64 KB chunk (thousands a second) floods WorkManager's progress queue and the observers behind it: the bar
        // moved once and then lagged behind for good. A few updates a second is smooth and cheap.
        val throttle = ProgressThrottle(PROGRESS_INTERVAL_MS)
        val result = try {
            downloader.download(
                url = url,
                destination = File(destinationPath),
                expectedSha256 = sha256,
                expectedSize = expectedSize,
            ) { progress ->
                center.progress(itemId, modelName, progress.bytesDownloaded, progress.totalBytes ?: 0L)
                if (throttle.allow(SystemClock.elapsedRealtime())) {
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_BYTES to progress.bytesDownloaded,
                            KEY_PROGRESS_TOTAL to (progress.totalBytes ?: -1L),
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            center.waiting(itemId)
            throw e
        }

        return when (result) {
            is PamResult.Success -> {
                center.done(itemId)
                Result.success(workDataOf(KEY_DESTINATION to result.data.absolutePath))
            }
            // Retryable: the partial file is preserved, so a retry resumes rather than
            // restarting. WorkManager applies its own backoff.
            is PamResult.Error -> {
                center.waiting(itemId)
                Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = center.foregroundInfo()

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        /** Delegates so the id can never be used without its channel existing. */
        const val CHANNEL_ID = DownloadNotifications.CHANNEL_ID
        const val NOTIFICATION_ID = DownloadNotificationCenter.NOTIFICATION_ID
        private const val PROGRESS_INTERVAL_MS = 250L

        const val KEY_MODEL_ID = "modelId"
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
