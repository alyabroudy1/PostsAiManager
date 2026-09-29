package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.ExtractionResult
import com.postsaimanager.core.model.ProcessingState
import kotlinx.coroutines.flow.Flow

/**
 * The port through which a feature runs the document pipeline and observes its progress.
 *
 * Lives in `:core:domain` because features may only see the domain layer (architecture
 * rule 1, documentation/README.md "The two structural guarantees"). The concrete pipeline
 * touches Room, ML Kit OCR and the on-device AI engine directly — none of which a feature is
 * allowed to reach around this port. `:core:data`'s `DocumentProcessingPipeline` is the only
 * implementation; see documentation/07-document-pipeline.md for what each stage does.
 */
interface DocumentProcessor {

    /** Progress of whichever document last started processing, or [ProcessingState.Idle]. */
    val processingState: Flow<ProcessingState>

    /**
     * Runs OCR, understanding, profile linking and indexing for [documentId], merging the
     * result into whatever is already stored rather than replacing it (see
     * `MergeExtractionUseCase`). Called only by the background worker the implementation
     * schedules from [enqueue] — a feature should never call this directly, or processing
     * stops the moment the user leaves the screen.
     */
    suspend fun processDocument(documentId: String): PamResult<ExtractionResult>

    /**
     * Schedules [documentId] to be processed in the background, surviving navigation and
     * the app being backgrounded — see documentation/07-document-pipeline.md §7. Sets the
     * document's status to [com.postsaimanager.core.model.DocumentStatus.QUEUED] (unless it
     * is already [com.postsaimanager.core.model.DocumentStatus.PROCESSING]) so the UI has an
     * honest state to show before the work actually starts running.
     *
     * Work is unique per document (`process-document-<id>`): a plain call while processing
     * is already queued or running joins it rather than starting a second run. [force]
     * requests a fresh run even so — for a manual Reprocess/Retry.
     */
    suspend fun enqueue(documentId: String, force: Boolean = false)

    /**
     * Cancels any queued or running work for [documentId] — called when the document itself
     * is deleted, so a stale worker does not resurrect rows a delete just removed.
     */
    fun cancel(documentId: String)
}
