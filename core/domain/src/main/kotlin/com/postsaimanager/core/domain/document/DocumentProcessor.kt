package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.ExtractionResult
import com.postsaimanager.core.model.ProcessingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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
     *
     * @param forcedFamily a family a person chose ("Read again as ..."): the letter is read as this family, which is stored as the
     *   person's choice and kept by every later re-read. Null reads the family as the model decides it, except that a family a
     *   person chose earlier is read as that one.
     */
    suspend fun processDocument(documentId: String, reprocess: Boolean = false, forcedFamily: String? = null): PamResult<ExtractionResult>

    /**
     * Schedules a quiet, low-priority re-read of an already finished document by the current
     * extractor (see `ReprocessOutdatedDocumentsUseCase`). Separate unique work
     * (`reprocess-document-<id>`), only while the device is charging or idle and the battery is not
     * low, so it never competes with a new scan; joins work already pending for the same document.
     * Unlike [enqueue] it leaves the document's status alone: the user sees no progress and no
     * failure, and a document that fails to re-read keeps its earlier data.
     */
    suspend fun enqueueReprocess(documentId: String)

    /**
     * Schedules [documentId] to be processed in the background, surviving navigation and
     * the app being backgrounded — see documentation/07-document-pipeline.md §7. Sets the
     * document's status to [com.postsaimanager.core.model.DocumentStatus.QUEUED] (unless it
     * is already [com.postsaimanager.core.model.DocumentStatus.PROCESSING]) so the UI has an
     * honest state to show before the work actually starts running.
     *
     * Work is unique per document (`process-document-<id>`): a plain call while processing
     * is already queued or running joins it rather than starting a second run. [force]
     * requests a fresh run even so — for a manual Reprocess/Retry. [forcedFamily] is the family a person chose for "Read again as ..."
     * (see [processDocument]); null for every other run.
     */
    suspend fun enqueue(documentId: String, force: Boolean = false, forcedFamily: String? = null)

    /**
     * Schedules a fresh read of [documentId] in which the document's family is [forcedFamily] instead of the one the classifier
     * would pick ("Read again as ..."): the same run as `enqueue(documentId, force = true)`, with the family question skipped.
     * Rows a person confirmed, edited or ignored stay as they are (`MergeExtractionUseCase`). A null [forcedFamily] is a plain re-read.
     *
     * The default is a plain re-read that ignores the family, so an implementation that cannot force one stays correct.
     *
     * TODO(P4): `DocumentProcessingPipeline` implements this: it hands [forcedFamily] to the interpreter and stores it with
     * `FamilySource.USER`.
     */
    suspend fun enqueueReadAs(documentId: String, forcedFamily: String?) {
        enqueue(documentId, force = true)
    }

    /**
     * Cancels any queued or running work for [documentId] (a scan and a background re-read alike) — called when the document itself
     * is deleted, so a stale worker does not resurrect rows a delete just removed.
     */
    fun cancel(documentId: String)

    /**
     * The documents whose reading is shown but not finished: the first stage (type, parties, amounts, dates) is stored and the second
     * (language, extras, title, summary, suggested questions) is queued or running. A screen says "Summary coming…" for these.
     */
    val enrichingDocuments: Flow<Set<String>> get() = flowOf(emptySet())

    /**
     * The second stage of [documentId]'s reading, from the ticket its first stage left: writes the language, the extras and the free
     * text and merges them into what is stored (never over a value a person wrote or confirmed, and the title only where
     * `DocumentTitlePolicy` allows). Called only by the background worker that [processDocument] schedules once the first stage is
     * stored; it never runs while a scan is being read.
     *
     * A null [ticket] is a second stage whose ticket was lost (the process died between the first stage and the queueing of the
     * second, or a scan pushed the second stage aside and the ticket was never put back): the ticket is rebuilt from what is stored
     * (the family, the topics and the first stage's fields) and the document's own OCR, so every document eventually gets its
     * second stage.
     */
    suspend fun enrichDocument(documentId: String, ticket: EnrichmentTicket? = null): PamResult<Unit> = PamResult.Success(Unit)

    /**
     * Schedules the second stage of [documentId] without a ticket (it is rebuilt when the work runs), for a document the first stage
     * stored whose second stage never completed. Unique work per document and `KEEP`, so a second stage that is genuinely queued is
     * left alone. Called on app start (`DocumentProcessingRecovery`).
     */
    suspend fun enqueueEnrichment(documentId: String) = Unit
}
