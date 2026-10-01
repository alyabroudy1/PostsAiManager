package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.ExtractionResult
import com.postsaimanager.core.model.ProcessingState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [DocumentProcessor] for ViewModel tests.
 *
 * Records every [enqueue] and [cancel] call rather than doing anything with them — the
 * ViewModels under test are exercised on *whether* and *with what arguments* they call the
 * port, not on pipeline behaviour, which `DocumentProcessingPipeline` has its own coverage
 * for.
 */
class FakeDocumentProcessor : DocumentProcessor {

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    override val processingState = _processingState

    /** When set, [processDocument] returns this instead of a default success. */
    var processResult: PamResult<ExtractionResult>? = null

    val enqueueCalls = mutableListOf<EnqueueCall>()
    val cancelCalls = mutableListOf<String>()

    data class EnqueueCall(val documentId: String, val force: Boolean, val forcedFamily: String? = null)

    fun emit(state: ProcessingState) {
        _processingState.value = state
    }

    val reprocessCalls = mutableListOf<String>()

    override suspend fun processDocument(documentId: String, reprocess: Boolean, forcedFamily: String?): PamResult<ExtractionResult> =
        processResult ?: PamResult.Success(
            ExtractionResult(documentId = documentId, language = null, fields = emptyList()),
        )

    override suspend fun enqueueReprocess(documentId: String) {
        reprocessCalls += documentId
    }

    /** The documents a test says have a second stage pending ([enrichingDocuments]). */
    val enriching = MutableStateFlow<Set<String>>(emptySet())
    override val enrichingDocuments: kotlinx.coroutines.flow.Flow<Set<String>> = enriching

    val enrichCalls = mutableListOf<Pair<String, EnrichmentTicket?>>()

    override suspend fun enrichDocument(documentId: String, ticket: EnrichmentTicket?): PamResult<Unit> {
        enrichCalls += documentId to ticket
        return PamResult.Success(Unit)
    }

    /** The documents whose second stage was queued without a ticket ([enqueueEnrichment]). */
    val enrichmentEnqueued = mutableListOf<String>()

    override suspend fun enqueueEnrichment(documentId: String) {
        enrichmentEnqueued += documentId
    }

    override suspend fun enqueue(documentId: String, force: Boolean, forcedFamily: String?) {
        enqueueCalls += EnqueueCall(documentId, force, forcedFamily)
    }

    /** Every "Read again as" call: the document and the family it was forced to. */
    val readAsCalls = mutableListOf<Pair<String, String?>>()

    override suspend fun enqueueReadAs(documentId: String, forcedFamily: String?) {
        readAsCalls += documentId to forcedFamily
    }

    override fun cancel(documentId: String) {
        cancelCalls += documentId
    }
}
