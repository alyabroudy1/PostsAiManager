package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for retrieving document detail with pages.
 */
class GetDocumentDetailUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    suspend operator fun invoke(documentId: String): PamResult<DocumentDetail> {
        val docResult = documentRepository.getDocumentById(documentId)
        if (docResult is PamResult.Error) return docResult

        val document = (docResult as PamResult.Success).data
        val pagesResult = documentRepository.getDocumentPages(documentId)
        if (pagesResult is PamResult.Error) return pagesResult

        val pages = (pagesResult as PamResult.Success).data
        return PamResult.Success(DocumentDetail(document, pages))
    }
}

/**
 * Combined document with its pages.
 */
data class DocumentDetail(
    val document: Document,
    val pages: List<DocumentPage>,
)
