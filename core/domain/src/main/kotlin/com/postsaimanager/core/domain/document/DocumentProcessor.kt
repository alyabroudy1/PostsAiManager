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
     * `MergeExtractionUseCase`).
     */
    suspend fun processDocument(documentId: String): PamResult<ExtractionResult>
}
