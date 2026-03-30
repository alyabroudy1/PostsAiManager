package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for retrieving the document list.
 * Encapsulates business logic — currently delegates directly,
 * but can add sorting, filtering, or transformation logic.
 */
class GetDocumentsUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    operator fun invoke(): Flow<List<Document>> =
        documentRepository.getDocuments()
}
