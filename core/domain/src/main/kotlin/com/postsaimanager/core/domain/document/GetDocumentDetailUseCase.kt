package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.TimelineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Use case for observing document detail reactively.
 * Combines document, pages, extracted data, and timeline into a single Flow.
 */
class GetDocumentDetailUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val timelineRepository: TimelineRepository,
) {
    operator fun invoke(documentId: String): Flow<DocumentDetailUiState> {
        return combine(
            documentRepository.observeDocument(documentId).filterNotNull(),
            documentRepository.observePages(documentId),
            documentRepository.observeExtractedData(documentId),
            timelineRepository.observeEvents(documentId),
        ) { document, pages, extractedData, timeline ->
            DocumentDetailUiState.Success(
                document = document,
                pages = pages,
                extractedData = extractedData,
                timeline = timeline,
            )
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
}
