package com.postsaimanager.core.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.domain.ai.ChatActivityGate
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.model.DocumentStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Re-reads one finished document with the current extractor, quietly.
 *
 * Deliberately unlike [DocumentProcessingWorker]: no foreground service and no notification (the user
 * did not ask for this and must not notice it), no progress published, and it never touches the
 * document's status. It starts only under [Constraints] (see [requests]) and steps aside for new
 * scans ([ReprocessGate]). There is no WorkManager retry: a failure is recorded by the pipeline and a
 * later app start retries once (`ReprocessOutdatedDocumentsUseCase`).
 */
@HiltWorker
class ReprocessDocumentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val documentProcessor: DocumentProcessor,
    private val documentDao: DocumentDao,
    private val chatActivity: ChatActivityGate,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.failure()

        val scansInFlight = documentDao.getByStatus(DocumentStatus.QUEUED.name).isNotEmpty() ||
            documentDao.getByStatus(DocumentStatus.PROCESSING.name).isNotEmpty()
        when (ReprocessGate.decide(documentDao.getById(documentId), scansInFlight)) {
            ReprocessGate.Decision.SKIP -> {
                cancelSiblings(documentId)
                return Result.success()
            }
            // New scans always go first: come back later, WorkManager's own backoff decides when.
            ReprocessGate.Decision.DEFER -> return Result.retry()
            ReprocessGate.Decision.RUN -> Unit
        }

        // Not between two chat messages: replacing the chat model then costs the next message a reload and a full re-read. Come back later.
        if (!chatActivity.awaitIdle()) return Result.retry()

        // The charging and the idle request are the same job under two names; whichever starts first
        // does it and the other is dropped.
        cancelSiblings(documentId)

        return when (val result = documentProcessor.processDocument(documentId, reprocess = true)) {
            is PamResult.Success -> Result.success()
            is PamResult.Error -> {
                // The pipeline kept the old data and status, logged and recorded why.
                Log.w(TAG, "reprocess failed for $documentId: ${result.error.userMessage}")
                Result.failure()
            }
        }
    }

    private fun cancelSiblings(documentId: String) {
        val workManager = WorkManager.getInstance(applicationContext)
        val self = tags.firstOrNull { it.startsWith(NAME_PREFIX) }
        listOf(chargingWorkName(documentId), idleWorkName(documentId), urgentWorkName(documentId))
            .filter { it != self }
            .forEach { workManager.cancelUniqueWork(it) }
    }

    companion object {
        private const val TAG = "ReprocessWorker"
        const val KEY_DOCUMENT_ID = "documentId"

        /** Every reprocess work name starts with this, so it can never collide with `process-document-<id>`. */
        const val NAME_PREFIX = "reprocess-document-"

        fun chargingWorkName(documentId: String) = "$NAME_PREFIX$documentId"
        fun idleWorkName(documentId: String) = "$NAME_PREFIX$documentId-idle"
        fun urgentWorkName(documentId: String) = "$NAME_PREFIX$documentId-now"

        /** Only the battery condition: for a letter scanned before the model was there, which should be read as soon as it is. */
        val urgentConstraints: Constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        fun urgentRequest(documentId: String): Pair<String, OneTimeWorkRequest> =
            urgentWorkName(documentId) to request(documentId, urgentConstraints, urgentWorkName(documentId))

        /**
         * WorkManager constraints are all required together, so "charging OR idle" is two requests
         * with the same job: one that waits for a charger, one that waits for the device to be idle.
         * Both also require the battery not to be low.
         */
        val chargingConstraints: Constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val idleConstraints: Constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Unique work name to request, for [documentId]: the charging one and the idle one. */
        fun requests(documentId: String): List<Pair<String, OneTimeWorkRequest>> = listOf(
            chargingWorkName(documentId) to request(documentId, chargingConstraints, chargingWorkName(documentId)),
            idleWorkName(documentId) to request(documentId, idleConstraints, idleWorkName(documentId)),
        )

        private fun request(documentId: String, constraints: Constraints, name: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ReprocessDocumentWorker>()
                .setInputData(workDataOf(KEY_DOCUMENT_ID to documentId))
                .setConstraints(constraints)
                .addTag(name)
                .build()
    }
}

/**
 * Whether a reprocess run should go ahead. Pure, so the rules are unit-testable without WorkManager.
 * Runs again the checks the use case did at scheduling time, because work can wait a long time for its
 * constraints and the world moves on: the letter may be trashed, re-read by the other of its two
 * requests, or be being scanned again.
 */
internal object ReprocessGate {
    enum class Decision { RUN, DEFER, SKIP }

    fun decide(document: DocumentEntity?, scansInFlight: Boolean): Decision = when {
        document == null || document.deletedAt != null -> Decision.SKIP
        document.status != DocumentStatus.EXTRACTED.name -> Decision.SKIP
        !ExtractorVersion.isOutdated(document.extractorVersion) && !ExtractorVersion.awaitsModel(document.extractorVersion) -> Decision.SKIP
        // A scan is queued or running: new scans always go first.
        scansInFlight -> Decision.DEFER
        else -> Decision.RUN
    }
}
