package com.postsaimanager.core.data.mapper

import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.database.entity.FieldRevisionEntity
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentType
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.FieldRevision
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.model.ExtractedFieldType
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

    fun extractedDataToDomain(entity: ExtractedDataEntity): ExtractedData = ExtractedData(
        id = entity.id,
        documentId = entity.documentId,
        fieldName = entity.fieldName,
        fieldValue = entity.fieldValue,
        fieldType = runCatching { ExtractedFieldType.valueOf(entity.fieldType) }.getOrDefault(ExtractedFieldType.OTHER),
        confidence = entity.confidence,
        pageNumber = entity.pageNumber,
        isConfirmed = entity.isConfirmed,
        source = runCatching { ValueSource.valueOf(entity.source) }
            .getOrDefault(ValueSource.MACHINE),
        machineValue = entity.machineValue,
        machineConfidence = entity.machineConfidence,
        deletedByUser = entity.deletedByUser,
        hasUnreviewedMachineChange = entity.hasUnreviewedMachineChange,
        engineVersion = entity.engineVersion,
        updatedAt = entity.updatedAt,
    )

    fun extractedDataToEntity(domain: ExtractedData): ExtractedDataEntity = ExtractedDataEntity(
        id = domain.id,
        documentId = domain.documentId,
        fieldName = domain.fieldName,
        fieldValue = domain.fieldValue,
        fieldType = domain.fieldType.name,
        confidence = domain.confidence,
        pageNumber = domain.pageNumber,
        isConfirmed = domain.isConfirmed,
        source = domain.source.name,
        machineValue = domain.machineValue,
        machineConfidence = domain.machineConfidence,
        deletedByUser = domain.deletedByUser,
        hasUnreviewedMachineChange = domain.hasUnreviewedMachineChange,
        engineVersion = domain.engineVersion,
        updatedAt = domain.updatedAt,
    )

    fun revisionToEntity(domain: FieldRevision) = FieldRevisionEntity(
        id = domain.id,
        documentId = domain.documentId,
        fieldName = domain.fieldName,
        value = domain.value,
        source = domain.source.name,
        confidence = domain.confidence,
        engineVersion = domain.engineVersion,
        createdAt = domain.createdAt,
    )

    fun revisionToDomain(entity: FieldRevisionEntity) = FieldRevision(
        id = entity.id,
        documentId = entity.documentId,
        fieldName = entity.fieldName,
        value = entity.value,
        source = runCatching { ValueSource.valueOf(entity.source) }
            .getOrDefault(ValueSource.MACHINE),
        confidence = entity.confidence,
        engineVersion = entity.engineVersion,
        createdAt = entity.createdAt,
    )
}
