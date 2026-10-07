package com.postsaimanager.core.data.importing

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.domain.importing.ImportQueue
import com.postsaimanager.core.domain.importing.ImportRequest
import com.postsaimanager.core.domain.importing.ImportResult
import com.postsaimanager.core.domain.importing.ImportStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ImportQueue] over WorkManager: one expedited job per confirmed request (so a 50-page PDF survives leaving the app), unique per
 * batch. If the system has no expedited quota left the job simply runs as ordinary work.
 */
@Singleton
class WorkManagerImportQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val requests: ImportRequestStore,
    @Dispatcher(PamDispatcher.IO) private val io: CoroutineDispatcher,
) : ImportQueue {

    override suspend fun submit(request: ImportRequest) {
        withContext(io) { requests.save(request) }
        val work = OneTimeWorkRequestBuilder<ImportFilesWorker>()
            .setInputData(Data.Builder().putString(ImportFilesWorker.KEY_BATCH_ID, request.batchId).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(request.batchId), ExistingWorkPolicy.KEEP, work)
    }

    override suspend fun awaitResult(batchId: String): ImportResult {
        val finished = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(batchId))
            .map { infos -> infos.firstOrNull() }
            .first { it == null || it.state.isFinished }
            ?: return ImportResult(emptyList(), failedGroups = 1)
        val ids = finished.outputData.getStringArray(ImportFilesWorker.KEY_DOCUMENT_IDS)?.toList().orEmpty()
        val failed = finished.outputData.getInt(ImportFilesWorker.KEY_FAILED_GROUPS, if (finished.state == WorkInfo.State.SUCCEEDED) 0 else 1)
        return ImportResult(ids, failed)
    }

    override val status: Flow<ImportStatus>
        get() = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)
            .map { infos -> statusOf(infos.map { it.state }) }
            .distinctUntilChanged()

    override fun dismissFailures() {
        WorkManager.getInstance(context).pruneWork()
    }

    companion object {
        const val TAG = "import-files"

        fun workName(batchId: String) = "import-files-$batchId"

        /** Running is anything that has not finished; failed is a job that ended with a problem and was not pruned yet. */
        fun statusOf(states: List<WorkInfo.State>): ImportStatus = ImportStatus(
            running = states.count { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING || it == WorkInfo.State.BLOCKED },
            failed = states.count { it == WorkInfo.State.FAILED },
        )
    }
}
