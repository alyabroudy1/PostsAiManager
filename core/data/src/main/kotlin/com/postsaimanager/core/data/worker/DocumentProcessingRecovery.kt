package com.postsaimanager.core.data.worker

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.model.DocumentStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-enqueues documents a killed process left stuck at `PROCESSING` or `QUEUED`.
 *
 * A document only ever reaches `PROCESSING` because [DocumentProcessingWorker] is running —
 * see [DocumentProcessor.enqueue]. If the app (and with it, the worker's process) is killed
 * mid-run, the row is left at `PROCESSING` forever: nothing else ever moves it on, and
 * nothing ever re-enqueues it, since enqueuing only happens on capture or on opening a `NEW`
 * document. Called once from `PostsAiManagerApp.onCreate`.
 *
 * `QUEUED` documents are re-enqueued too, for the same reason: [DocumentProcessor.enqueue]
 * sets a document `QUEUED` *before* scheduling its `WorkManager` request (an honest status
 * ahead of the work actually starting), so a process kill in that narrow window — or
 * WorkManager itself losing the request, e.g. the `:inference` process becoming unreachable
 * during Understand and `RemoteAiEngine.connect()` returning an error rather than hanging
 * (see that class) — can leave a row `QUEUED` with no work actually scheduled for it, stuck
 * exactly like a lost `PROCESSING` row. Re-enqueuing here is idempotent either way: `enqueue`
 * uses `ExistingWorkPolicy.KEEP` under a unique work name, so a `QUEUED` document whose work
 * genuinely is still pending just gets a harmless duplicate call that WorkManager drops.
 *
 * Deliberately still narrow beyond those two statuses: this recovers documents that were (or
 * were meant to be) *actively* processing, not every legacy `NEW` document sitting unopened —
 * bulk-enqueuing those would silently start running the on-device model against a whole
 * backlog the moment the app updates, which is a surprise, not a fix. `NEW` documents already
 * enqueue themselves the moment their detail screen is opened (`DocumentDetailViewModel`).
 */
@Singleton
class DocumentProcessingRecovery @Inject constructor(
    private val documentDao: DocumentDao,
    private val documentProcessor: DocumentProcessor,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun resumeInterrupted() = withContext(ioDispatcher) {
        RECOVERABLE_STATUSES.forEach { status ->
            documentDao.getByStatus(status.name).forEach { entity ->
                documentProcessor.enqueue(entity.id)
            }
        }
        // A reading whose first stage was stored but whose second never completed (the process died before the second was queued,
        // or while a scan had pushed it aside, and the ticket lived only in memory): the second stage is queued again, its ticket
        // rebuilt from the stored document. Idempotent (`KEEP`), so a second stage that is genuinely queued is left alone.
        documentDao.getAwaitingEnrichment(ExtractorVersion.CURRENT).forEach { entity ->
            documentProcessor.enqueueEnrichment(entity.id)
        }
    }

    private companion object {
        val RECOVERABLE_STATUSES = listOf(DocumentStatus.PROCESSING, DocumentStatus.QUEUED)
    }
}
