package com.postsaimanager.core.ai.embed.install

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.download.DownloadNotificationCenter
import com.postsaimanager.core.download.ModelDownloadWorker
import com.postsaimanager.core.download.ProgressThrottle
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Installs the embedding model in the background, as a foreground service.
 *
 * A ~257 MB transfer cannot assume the app stays open. Same reasoning as
 * [ModelDownloadWorker], and it deliberately reuses that worker's notification channel:
 * from the user's side these are the same activity — "the app is fetching a model" — and
 * two channels for one concept means two toggles in system settings to silence it.
 *
 * Unlike [ModelDownloadWorker] this drives [EmbeddingModelInstaller] rather than the
 * downloader directly, because installing means two files that are only useful together.
 */
@HiltWorker
class EmbeddingModelInstallWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val installer: EmbeddingModelInstaller,
    private val center: DownloadNotificationCenter,
) : CoroutineWorker(appContext, params) {

    private val itemName: String
        get() = applicationContext.getString(com.postsaimanager.core.download.R.string.download_search_model)

    override suspend fun doWork(): Result {
        center.progress(WORK_NAME, itemName, 0L, EmbeddingModelRelease.totalBytes)
        setForeground(center.foregroundInfo())

        // See ModelDownloadWorker: an unthrottled progress write per chunk floods WorkManager and the bar never moves.
        val throttle = ProgressThrottle(PROGRESS_INTERVAL_MS)
        val result = try {
            installer.install { progress ->
                center.progress(WORK_NAME, itemName, progress.bytesDownloaded, progress.totalBytes)
                if (throttle.allow(SystemClock.elapsedRealtime())) {
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_BYTES to progress.bytesDownloaded,
                            KEY_PROGRESS_TOTAL to progress.totalBytes,
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            center.waiting(WORK_NAME)
            throw e
        }

        return when (result) {
            is PamResult.Success -> {
                center.done(WORK_NAME)
                Result.success()
            }
            // Retryable: verified files stay put and the partial one resumes, so a retry
            // costs only the bytes that had not arrived.
            is PamResult.Error -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                center.failed(WORK_NAME)
                Result.failure()
            } else {
                center.waiting(WORK_NAME)
                Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = center.foregroundInfo()

    companion object {
        /** The same id as [ModelDownloadWorker]'s: one notification for all downloads, rendered by [DownloadNotificationCenter]. */
        const val NOTIFICATION_ID = DownloadNotificationCenter.NOTIFICATION_ID
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val MAX_ATTEMPTS = 5

        const val WORK_NAME = "embedding-model-install"
        const val KEY_PROGRESS_BYTES = "progressBytes"
        const val KEY_PROGRESS_TOTAL = "progressTotal"

        /**
         * @param allowMetered the default is false. This is a quarter-gigabyte transfer for
         *   a feature the user did not explicitly ask for at this moment, so it waits for
         *   unmetered network unless they say otherwise.
         */
        fun enqueue(context: Context, allowMetered: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<EmbeddingModelInstallWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                        )
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                // KEEP, not REPLACE: a second request while one is running should join it
                // rather than cancel a transfer that is already part-way through.
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun observe(context: Context): Flow<InstallStatus> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .map { infos -> infos.firstOrNull().toStatus() }

        private fun WorkInfo?.toStatus(): InstallStatus = when (this?.state) {
            null -> InstallStatus.NotStarted
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> InstallStatus.Waiting
            WorkInfo.State.RUNNING -> InstallStatus.Running(
                bytesDownloaded = progress.getLong(KEY_PROGRESS_BYTES, 0L),
                totalBytes = progress.getLong(KEY_PROGRESS_TOTAL, 0L),
            )
            WorkInfo.State.SUCCEEDED -> InstallStatus.Installed
            WorkInfo.State.FAILED -> InstallStatus.Failed
            WorkInfo.State.CANCELLED -> InstallStatus.NotStarted
        }
    }
}

sealed interface InstallStatus {
    data object NotStarted : InstallStatus
    data object Waiting : InstallStatus
    data class Running(val bytesDownloaded: Long, val totalBytes: Long) : InstallStatus {
        val fraction: Float
            get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }
    data object Installed : InstallStatus
    data object Failed : InstallStatus
}
