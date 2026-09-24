package com.postsaimanager.core.data.worker

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.model.DocumentStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-enqueues documents a killed process left stuck at `PROCESSING`.
 *
 * A document only ever reaches `PROCESSING` because [DocumentProcessingWorker] is running —
 * see [DocumentProcessor.enqueue]. If the app (and with it, the worker's process) is killed
 * mid-run, the row is left at `PROCESSING` forever: nothing else ever moves it on, and
 * nothing ever re-enqueues it, since enqueuing only happens on capture or on opening a `NEW`
 * document. Called once from `PostsAiManagerApp.onCreate`.
 *
 * Deliberately narrow: this recovers documents that were *actively* being processed, not
 * every legacy `NEW` document sitting unopened — bulk-enqueuing those would silently start
 * running the on-device model against a whole backlog the moment the app updates, which is
 * a surprise, not a fix. `NEW` documents already enqueue themselves the moment their detail
 * screen is opened (`DocumentDetailViewModel`), and `QUEUED` documents already have work
 * enqueued (`ExistingWorkPolicy.KEEP` makes a duplicate enqueue here harmless, but they are
 * excluded anyway since nothing about them needs recovering).
 */
@Singleton
class DocumentProcessingRecovery @Inject constructor(
    private val documentDao: DocumentDao,
    private val documentProcessor: DocumentProcessor,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun resumeInterrupted() = withContext(ioDispatcher) {
        documentDao.getByStatus(DocumentStatus.PROCESSING.name).forEach { entity ->
            documentProcessor.enqueue(entity.id)
        }
    }
}
