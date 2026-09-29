package com.postsaimanager.core.domain.repository

/** A stored passage of a document with its embedding. */
data class StoredChunk(
    val id: String,
    val documentId: String,
    val ordinal: Int,
    val text: String,
    val embedding: FloatArray?,
    val embeddingModelId: String?,
    /**
     * Which page of the document this passage came from, or null for a chunk indexed
     * before pages were tracked (4.0) — such chunks stay null until the document is
     * re-processed, they are never backfilled. Lets a retrieved passage be labelled
     * `[p.N]` instead of only an opaque ordinal.
     */
    val pageNumber: Int? = null,
) {
    // FloatArray uses identity equality, so a data class would compare embeddings by
    // reference and report identical chunks as different.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredChunk) return false
        return id == other.id &&
            documentId == other.documentId &&
            ordinal == other.ordinal &&
            text == other.text &&
            embeddingModelId == other.embeddingModelId &&
            pageNumber == other.pageNumber &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + ordinal
        result = 31 * result + text.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + (embeddingModelId?.hashCode() ?: 0)
        result = 31 * result + (pageNumber ?: 0)
        return result
    }
}

interface DocumentChunkRepository {

    /**
     * Every chunk — the corpus retrieval scores against.
     *
     * Includes chunks with no embedding on purpose: keyword search must still reach text
     * that was indexed while no embedding model was installed.
     */
    suspend fun getAll(): List<StoredChunk>

    suspend fun getForDocument(documentId: String): List<StoredChunk>

    /** Replaces a document's chunks wholesale; re-indexing must not leave stale passages. */
    suspend fun replaceChunks(documentId: String, chunks: List<StoredChunk>)

    /** Documents with no chunks yet — the indexing backlog. */
    suspend fun unindexedDocumentIds(): List<String>

    /** Documents chunked but not embedded — indexed before a model was available. */
    suspend fun documentIdsMissingEmbeddings(): List<String>

    /** Documents embedded by a different model; their vectors are not comparable. */
    suspend fun documentIdsNeedingReindex(currentModelId: String): List<String>

    suspend fun deleteForDocument(documentId: String)

    suspend fun embeddedChunkCount(): Int
}
