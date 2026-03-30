package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Core domain model representing a scanned/imported document.
 */
@Serializable
data class Document(
    val id: String,
    val title: String,
    val status: DocumentStatus = DocumentStatus.NEW,
    val documentType: DocumentType? = null,
    val language: String? = null,
    val sourceType: SourceType,
    val thumbnailPath: String? = null,
    val pageCount: Int = 0,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val modifiedAt: Long,
)

@Serializable
enum class DocumentStatus {
    NEW,
    PROCESSING,
    EXTRACTED,
    REVIEWED,
    ARCHIVED,
}

@Serializable
enum class DocumentType {
    OFFICIAL_LETTER,
    INVOICE,
    NOTICE,
    FORM,
    CONTRACT,
    CERTIFICATE,
    RECEIPT,
    OTHER,
}

@Serializable
enum class SourceType {
    CAMERA,
    UPLOAD,
    PDF_IMPORT,
}
