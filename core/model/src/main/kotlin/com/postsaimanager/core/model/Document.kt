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

/**
 * Stored as its `name` in Room ([DocumentEntity][com.postsaimanager.core.data.database.entity]
 * keeps `status` as a plain `TEXT` column read back through `valueOf`), so adding a value here
 * is additive and needs no migration — only code that exhaustively `when`s over every value
 * does.
 */
@Serializable
enum class DocumentStatus {
    NEW,

    /** Enqueued for processing but not yet running — see `DocumentProcessor.enqueue`. */
    QUEUED,
    PROCESSING,
    EXTRACTED,
    REVIEWED,
    ARCHIVED,

    /** The last processing attempt failed; the document keeps whatever it had before. */
    FAILED,
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
