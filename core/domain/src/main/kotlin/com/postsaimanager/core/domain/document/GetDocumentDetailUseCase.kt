package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.TimelineEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * Use case for observing document detail reactively.
 * Combines document, pages, extracted data, and timeline into a single Flow.
 *
 * `observeDocument` is unfiltered by trash state (see `DocumentDao.observeById`), so a
 * trashed document still reaches [DocumentDetailUiState.Success] here — with
 * [Document.isTrashed] true — rather than disappearing; the screen decides how to render
 * that. Only an id with no row at all (permanently deleted, or a stale deep link/citation)
 * maps to [DocumentDetailUiState.NotFound]. Before this used `filterNotNull()`, which meant a
 * document deleted permanently while its detail screen was open just stopped emitting —
 * the screen was left showing whatever it last had, forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetDocumentDetailUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val timelineRepository: TimelineRepository,
) {
    operator fun invoke(documentId: String): Flow<DocumentDetailUiState> {
        return documentRepository.observeDocument(documentId).flatMapLatest { document ->
            if (document == null) {
                flowOf(DocumentDetailUiState.NotFound)
            } else {
                combine(
                    documentRepository.observePages(documentId),
                    documentRepository.observeExtractedData(documentId),
                    timelineRepository.observeEvents(documentId),
                ) { pages, extractedData, timeline ->
                    DocumentDetailUiState.Success(
                        document = document,
                        pages = pages,
                        extractedData = extractedData,
                        timeline = CollapseProcessingLog.collapse(timeline),
                    )
                }
            }
        }
    }
}

sealed interface DocumentDetailUiState {
    data object Loading : DocumentDetailUiState
    data class Success(
        val document: Document,
        val pages: List<DocumentPage>,
        val extractedData: List<ExtractedData>,
        val timeline: List<TimelineEvent>,
    ) : DocumentDetailUiState
    data class Error(val message: String) : DocumentDetailUiState

    /** No document at this id — permanently deleted, or a stale deep link/citation. */
    data object NotFound : DocumentDetailUiState
}
