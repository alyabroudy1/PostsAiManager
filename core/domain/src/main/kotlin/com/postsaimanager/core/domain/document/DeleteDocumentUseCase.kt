package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import javax.inject.Inject

/**
 * Use case for deleting a document with timeline tracking.
 */
class DeleteDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    suspend operator fun invoke(documentId: String): PamResult<Unit> {
        return documentRepository.deleteDocument(documentId)
    }
}
