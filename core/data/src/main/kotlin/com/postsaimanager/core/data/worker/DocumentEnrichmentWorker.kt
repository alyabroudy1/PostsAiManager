package com.postsaimanager.core.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.EnrichmentTicket
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json

/**
 * The second stage of a reading, in the background: the language, the extras, the composed title, the summary and the suggested
 * questions, written after the first stage's result (family, parties, amounts, dates, addresses) is already stored and shown.
 *
 * Quiet like [ReprocessDocumentWorker]: no notification and no status (the document stays EXTRACTED), no retry of its own. It is
 * one unique work per document ([workName]), it never starts while a scan is queued or being read (it asks WorkManager to try again
 * later), and a new scan cancels it (`DocumentProcessingPipeline.enqueue`); the pipeline keeps the ticket and schedules it again once
 * the scan's first stage is stored.
 *
 * The ticket travels as one JSON value ([KEY_TICKET]). Work queued without one (see [DocumentProcessor.enqueueEnrichment]) runs with a
 * null ticket, which the pipeline rebuilds from the stored document; that is also what an unreadable ticket becomes.
 */
@HiltWorker
class DocumentEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val documentProcessor: DocumentProcessor,
    private val documentDao: DocumentDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.failure()
        val ticket = ticketOf(inputData)

        // A scan goes first: come back later (WorkManager's own backoff decides when).
        val scansInFlight = documentDao.getByStatus(DocumentStatus.QUEUED.name).isNotEmpty() ||
            documentDao.getByStatus(DocumentStatus.PROCESSING.name).isNotEmpty()
        if (scansInFlight) return Result.retry()

        return when (val result = documentProcessor.enrichDocument(documentId, ticket)) {
            is PamResult.Success -> Result.success()
            is PamResult.Error -> {
                Log.w(TAG_LOG, "second stage failed for $documentId: ${result.error.userMessage}")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG_LOG = "EnrichmentWorker"
        const val KEY_DOCUMENT_ID = "documentId"

        /** The whole [EnrichmentTicket] as JSON; absent for a second stage whose ticket is rebuilt. */
        const val KEY_TICKET = "ticket"

        // The keys of the ticket before it carried the family's topics and the summary's facts; work queued by that build still holds them.
        const val KEY_TYPE_ID = "typeId"
        const val KEY_TAKEN_IDS = "takenIds"
        const val KEY_ESTABLISHED = "established"

        private val json = Json { ignoreUnknownKeys = true }

        /** Tag of every second-stage work, so a new scan can push them all aside at once. */
        const val TAG = "enrich-documents"

        /** Never collides with `process-document-<id>` or the reprocess names. */
        fun workName(documentId: String) = "enrich-document-$documentId"

        /** The ticket [data] carries, or null when it has none (the pipeline then rebuilds it) or it cannot be read. */
        internal fun ticketOf(data: Data): EnrichmentTicket? {
            data.getString(KEY_TICKET)?.let { text ->
                return runCatching { json.decodeFromString(EnrichmentTicket.serializer(), text) }.getOrNull()
            }
            if (!data.keyValueMap.containsKey(KEY_TYPE_ID)) return null
            return EnrichmentTicket(
                typeId = data.getString(KEY_TYPE_ID)?.takeIf { it.isNotEmpty() },
                takenIds = data.getStringArray(KEY_TAKEN_IDS)?.toList().orEmpty(),
                established = data.getString(KEY_ESTABLISHED).orEmpty(),
            )
        }

        /** The work that writes the second stage of [documentId]; a null [ticket] is rebuilt from the stored document when the work runs. */
        fun request(documentId: String, ticket: EnrichmentTicket?): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DocumentEnrichmentWorker>()
                .setInputData(
                    if (ticket == null) {
                        workDataOf(KEY_DOCUMENT_ID to documentId)
                    } else {
                        workDataOf(KEY_DOCUMENT_ID to documentId, KEY_TICKET to json.encodeToString(EnrichmentTicket.serializer(), ticket))
                    },
                )
                .addTag(TAG)
                .build()
    }
}
