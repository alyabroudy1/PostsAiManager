package com.postsaimanager.core.data.mapper

import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentType
import com.postsaimanager.core.model.SourceType
import javax.inject.Inject

/**
 * Maps between Room entities and domain models.
 * Keeps conversion logic centralized and testable.
 */
class DocumentMapper @Inject constructor() {

    fun toDomain(entity: DocumentEntity): Document = Document(
        id = entity.id,
        title = entity.title,
        status = DocumentStatus.valueOf(entity.status),
        documentType = entity.documentType?.let { runCatching { DocumentType.valueOf(it) }.getOrNull() },
        language = entity.language,
        sourceType = SourceType.valueOf(entity.sourceType),
        thumbnailPath = entity.thumbnailPath,
        pageCount = entity.pageCount,
        isFavorite = entity.isFavorite,
        createdAt = entity.createdAt,
        modifiedAt = entity.modifiedAt,
    )

    fun toEntity(domain: Document): DocumentEntity = DocumentEntity(
        id = domain.id,
        title = domain.title,
        status = domain.status.name,
        documentType = domain.documentType?.name,
        language = domain.language,
        sourceType = domain.sourceType.name,
        thumbnailPath = domain.thumbnailPath,
        pageCount = domain.pageCount,
        isFavorite = domain.isFavorite,
        createdAt = domain.createdAt,
        modifiedAt = domain.modifiedAt,
    )

    fun pageToDomain(entity: DocumentPageEntity): DocumentPage = DocumentPage(
        id = entity.id,
        documentId = entity.documentId,
        pageNumber = entity.pageNumber,
        imagePath = entity.imagePath,
        processedPath = entity.processedPath,
        ocrText = entity.ocrText,
        ocrConfidence = entity.ocrConfidence,
        width = entity.width,
        height = entity.height,
    )

    fun pageToEntity(domain: DocumentPage): DocumentPageEntity = DocumentPageEntity(
        id = domain.id,
        documentId = domain.documentId,
        pageNumber = domain.pageNumber,
        imagePath = domain.imagePath,
        processedPath = domain.processedPath,
        ocrText = domain.ocrText,
        ocrConfidence = domain.ocrConfidence,
        width = domain.width,
        height = domain.height,
    )
}
