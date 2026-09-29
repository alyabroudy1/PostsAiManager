package com.postsaimanager.core.ai.embed.install

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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
import com.postsaimanager.core.download.DownloadNotifications
import com.postsaimanager.core.download.ModelDownloadWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo(progress = null))

        val result = installer.install { progress ->
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_BYTES to progress.bytesDownloaded,
                    KEY_PROGRESS_TOTAL to progress.totalBytes,
                ),
            )
        }

        return when (result) {
            is PamResult.Success -> Result.success()
            // Retryable: verified files stay put and the partial one resumes, so a retry
            // costs only the bytes that had not arrived.
            is PamResult.Error -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(progress = null)

    private fun foregroundInfo(progress: Int?): ForegroundInfo {
        // Must happen before the notification is built. Posting to a channel that does not
        // exist is an invalid notification, and a foreground service that posts one is
        // killed rather than merely ignored.
        DownloadNotifications.ensureChannel(applicationContext)

        val notification = NotificationCompat.Builder(
            applicationContext,
            DownloadNotifications.CHANNEL_ID,
        )
            .setContentTitle("Preparing document search")
            // Says what the user gets, not what the app is doing. "Downloading
            // distiluse-base-multilingual-cased-v2" means nothing to them.
            .setContentText("Downloading the model that lets you search your documents by meaning.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress != null) setProgress(100, progress, false)
                else setProgress(0, 0, true)
            }
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Distinct from [ModelDownloadWorker]'s, so the two notifications coexist. */
        const val NOTIFICATION_ID = 4712

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
