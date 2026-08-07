package com.postsaimanager.core.ai.catalog.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.postsaimanager.core.model.AiModelDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI needs to render a download. */
sealed interface ModelDownloadStatus {
    data object NotStarted : ModelDownloadStatus
    data object Queued : ModelDownloadStatus
    data class Running(val bytesDownloaded: Long, val totalBytes: Long?) : ModelDownloadStatus {
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { bytesDownloaded.toFloat() / it }
    }
    data class Failed(val message: String?) : ModelDownloadStatus
    data class Complete(val filePath: String) : ModelDownloadStatus
    data object Cancelled : ModelDownloadStatus
}

/**
 * Enqueues and observes model downloads.
 *
 * Wraps WorkManager so callers never touch `Data` keys, and so the policy decisions —
 * unmetered by default, one job per model — live in one place.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager get() = WorkManager.getInstance(context)

    /** Models land in app-private storage; nothing else may read them. */
    fun destinationFor(descriptor: AiModelDescriptor): File =
        File(File(context.filesDir, MODELS_DIR).apply { mkdirs() }, "${descriptor.id}.gguf")

    /**
     * @param allowMetered opt-in to mobile data. Defaults to false — a 2 GB download on a
     *   metered connection is a bill the user did not agree to.
     */
    fun enqueue(descriptor: AiModelDescriptor, allowMetered: Boolean = false): Boolean {
        val url = descriptor.downloadUrl
        val sha256 = descriptor.sha256
        // Enforced here as well as in ModelFit: nothing downloads without an integrity hash.
        if (url.isNullOrBlank() || sha256.isNullOrBlank()) return false

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                    )
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to url,
                    ModelDownloadWorker.KEY_SHA256 to sha256,
                    ModelDownloadWorker.KEY_DESTINATION to destinationFor(descriptor).absolutePath,
                    ModelDownloadWorker.KEY_MODEL_NAME to descriptor.name,
                    ModelDownloadWorker.KEY_SIZE_BYTES to descriptor.sizeBytes,
                ),
            )
            .addTag(TAG_MODEL_DOWNLOAD)
            .build()

        // KEEP, not REPLACE: re-tapping Install must join the running job rather than
        // restart the transfer.
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.workName(descriptor.id),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    /** Cancels the job. The `.part` file is deliberately kept so a later retry resumes. */
    fun cancel(modelId: String) {
        workManager.cancelUniqueWork(ModelDownloadWorker.workName(modelId))
    }

    fun observe(modelId: String): Flow<ModelDownloadStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workName(modelId))
            .map { infos -> infos.firstOrNull().toStatus() }

    private fun WorkInfo?.toStatus(): ModelDownloadStatus = when (this?.state) {
        null -> ModelDownloadStatus.NotStarted
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelDownloadStatus.Queued
        WorkInfo.State.RUNNING -> ModelDownloadStatus.Running(
            bytesDownloaded = progress.getLong(ModelDownloadWorker.KEY_PROGRESS_BYTES, 0L),
            totalBytes = progress.getLong(ModelDownloadWorker.KEY_PROGRESS_TOTAL, -1L)
                .takeIf { it > 0 },
        )
        WorkInfo.State.SUCCEEDED -> ModelDownloadStatus.Complete(
            outputData.getString(ModelDownloadWorker.KEY_DESTINATION).orEmpty(),
        )
        WorkInfo.State.FAILED -> ModelDownloadStatus.Failed(
            outputData.getString(ModelDownloadWorker.KEY_ERROR),
        )
        WorkInfo.State.CANCELLED -> ModelDownloadStatus.Cancelled
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val TAG_MODEL_DOWNLOAD = "model-download"
    }
}
