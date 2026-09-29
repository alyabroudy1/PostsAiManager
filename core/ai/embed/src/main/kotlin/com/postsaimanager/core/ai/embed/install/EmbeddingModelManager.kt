package com.postsaimanager.core.ai.embed.install

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.postsaimanager.core.ai.embed.EmbeddingModelFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the UI talks to about the embedding model.
 *
 * Exists so a ViewModel does not have to hold a [Context] or know that WorkManager is
 * involved. It also resolves the one thing neither the worker nor the installer can answer
 * alone: **whether the model is actually usable right now**.
 *
 * The worker only knows about work it has run. A model installed on a previous launch — or
 * one whose work history WorkManager has since pruned — leaves the worker reporting
 * `NotStarted` while the files sit on disk, ready. Disk is therefore the authority for
 * "installed", and the worker's state only fills in what disk cannot say: queued, running,
 * failed.
 */
@Singleton
class EmbeddingModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: EmbeddingModelFiles,
    private val installer: EmbeddingModelInstaller,
) {

    private val workManager get() = WorkManager.getInstance(context)

    /** Total transfer, for telling the user what an install will cost them. */
    val downloadBytes: Long get() = EmbeddingModelRelease.totalBytes

    val status: Flow<InstallStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(EmbeddingModelInstallWorker.WORK_NAME)
            .map { infos -> statusOf(infos.firstOrNull()) }

    fun isInstalled(): Boolean = files.arePresent()

    fun install(allowMetered: Boolean = false) {
        EmbeddingModelInstallWorker.enqueue(context, allowMetered)
    }

    fun cancel() {
        // The partial file survives, so resuming later costs only the missing bytes.
        workManager.cancelUniqueWork(EmbeddingModelInstallWorker.WORK_NAME)
    }

    suspend fun uninstall() {
        cancel()
        installer.uninstall()
    }

    /** Bytes on disk, including an interrupted download, for an honest storage figure. */
    fun bytesOnDisk(): Long {
        if (!files.directory.isDirectory) return 0L
        return files.directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun statusOf(info: WorkInfo?): InstallStatus {
        // Disk wins. If the files are there, the model works regardless of what WorkManager
        // remembers about how they arrived.
        if (files.arePresent()) return InstallStatus.Installed

        return when (info?.state) {
            null -> InstallStatus.NotStarted
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> InstallStatus.Waiting
            WorkInfo.State.RUNNING -> InstallStatus.Running(
                bytesDownloaded = info.progress.getLong(
                    EmbeddingModelInstallWorker.KEY_PROGRESS_BYTES,
                    0L,
                ),
                totalBytes = info.progress.getLong(
                    EmbeddingModelInstallWorker.KEY_PROGRESS_TOTAL,
                    EmbeddingModelRelease.totalBytes,
                ),
            )
            // Succeeded, but the files are not there — deleted since. Offer it again rather
            // than claiming an install the user cannot actually use.
            WorkInfo.State.SUCCEEDED -> InstallStatus.NotStarted
            WorkInfo.State.FAILED -> InstallStatus.Failed
            WorkInfo.State.CANCELLED -> InstallStatus.NotStarted
        }
    }
}
