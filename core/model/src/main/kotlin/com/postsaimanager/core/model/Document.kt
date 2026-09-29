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
    /**
     * Set when the last extraction had to cut this document's layout to fit its context
     * budget (5.4, `InputTruncation`) — how many of [pageCount] pages the model actually
     * saw. Null means the whole document was read, which is both the common case and what a
     * document scanned before this existed reads as.
     */
    val extractionPagesRead: Int? = null,
    val extractionTotalPages: Int? = null,
    /**
     * Set when the document is in the trash (moved there by the user, or by processing
     * itself refusing to write after a race with a delete). Null means "not deleted". A
     * trashed document is hidden from every list, search and chat-retrieval path, but its
     * rows and files are kept until it is restored or purged — see
     * documentation/07-document-pipeline.md, "Deleting documents".
     */
    val deletedAt: Long? = null,
) {
    val isTrashed: Boolean get() = deletedAt != null
}

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
