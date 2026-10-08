package com.postsaimanager.core.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.domain.ai.ChatActivityGate
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.EnrichmentTicket
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

/**
 * The second stage of a reading, in the background: the language, the extras, the composed title, the summary and the suggested
 * questions, written after the first stage's result (family, parties, amounts, dates, addresses) is already stored and shown.
 *
 * Quiet like [ReprocessDocumentWorker]: no notification and no status (the document stays EXTRACTED), no retry of its own. It is
 * one unique work per document ([workName]), it never starts while a scan is queued or being read (it queues itself again after a short
 * fixed delay, [RETRY_DELAY_SECONDS]), and a new scan cancels it (`DocumentProcessingPipeline.enqueue`); the pipeline keeps the ticket and schedules it again once
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
    private val chatActivity: ChatActivityGate,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.failure()
        val ticket = ticketOf(inputData)

        // A scan goes first: come back shortly (a fixed short delay, never WorkManager's exponential backoff, which grew to hours).
        val scansInFlight = documentDao.getByStatus(DocumentStatus.QUEUED.name).isNotEmpty() ||
            documentDao.getByStatus(DocumentStatus.PROCESSING.name).isNotEmpty()
        if (scansInFlight) return tryAgainSoon(documentId)

        // Quiet work must not replace the chat model between two chat messages (the next message would reload it and read the whole
        // conversation again): wait for the chat to be idle, and come back shortly when it is not. Nothing is dropped.
        if (!chatActivity.awaitIdle()) return tryAgainSoon(documentId)

        // The same quiet background queue also carries "who is this letter for or about?" (see [peopleRequest]).
        if (inputData.getBoolean(KEY_PEOPLE_CHECK, false)) {
            return when (val result = documentProcessor.decideConcernedPeople(documentId)) {
                is PamResult.Success -> Result.success()
                is PamResult.Error -> {
                    Log.w(TAG_LOG, "people check failed for $documentId: ${result.error.userMessage}")
                    // The model was busy or gave no answer: not lost, asked again after the usual short wait (bounded). A document that is gone
                    // or has no text can never be answered.
                    val attempt = inputData.getInt(KEY_ATTEMPT, 0)
                    val final = result.error is com.postsaimanager.core.common.result.PamError.FileNotFound ||
                        result.error is com.postsaimanager.core.common.result.PamError.OcrFailed
                    if (final || attempt >= MAX_PEOPLE_ATTEMPTS) Result.failure() else tryAgainSoon(documentId, attempt + 1)
                }
            }
        }

        return when (val result = documentProcessor.enrichDocument(documentId, ticket)) {
            is PamResult.Success -> Result.success()
            is PamResult.Error -> {
                Log.w(TAG_LOG, "second stage failed for $documentId: ${result.error.userMessage}")
                Result.failure()
            }
        }
    }

    /**
     * Blocked by a scan or a chat: this run ends and the same work is queued again after [RETRY_DELAY_SECONDS], appended to this one's own
     * unique name (so a `KEEP` or `REPLACE` of the pipeline still finds it). The wait is the same every time: `Result.retry()` would
     * have WorkManager double it up to five hours, and with Doze the second stage then sat unwritten ("Summary coming...") for hours.
     */
    private fun tryAgainSoon(documentId: String, attempt: Int = 0): Result {
        val people = inputData.getBoolean(KEY_PEOPLE_CHECK, false)
        val name = if (people) peopleWorkName(documentId) else workName(documentId)
        val input = if (attempt == 0) inputData else Data.Builder().putAll(inputData).putInt(KEY_ATTEMPT, attempt).build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, again(input, people))
        return Result.success()
    }

    companion object {
        private const val TAG_LOG = "EnrichmentWorker"

        /** How long a blocked second stage waits before it looks again: short and the same every time. */
        const val RETRY_DELAY_SECONDS = 60L

        /** How many times a people check the model could not answer is queued again (one wait each) before it is left for the next backfill. */
        const val MAX_PEOPLE_ATTEMPTS = 20
        const val KEY_ATTEMPT = "attempt"

        /** The next attempt of a blocked work: the same input, the same tag, queued to start after [RETRY_DELAY_SECONDS]. */
        internal fun again(input: Data, people: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DocumentEnrichmentWorker>()
                .setInputData(input)
                .setInitialDelay(RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                .addTag(if (people) PEOPLE_TAG else TAG)
                .build()
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

        /** Marks the work that decides who a document is for or about instead of writing its second stage. */
        const val KEY_PEOPLE_CHECK = "peopleCheck"

        /** Not [TAG]: a new scan pushes the second stages aside, but the people check is cheap and waits for the scan by itself. */
        const val PEOPLE_TAG = "people-documents"

        fun peopleWorkName(documentId: String) = "people-document-$documentId"

        /** The work that decides who [documentId] is for or about, over its stored text. */
        fun peopleRequest(documentId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DocumentEnrichmentWorker>()
                .setInputData(workDataOf(KEY_DOCUMENT_ID to documentId, KEY_PEOPLE_CHECK to true))
                .addTag(PEOPLE_TAG)
                .build()

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
