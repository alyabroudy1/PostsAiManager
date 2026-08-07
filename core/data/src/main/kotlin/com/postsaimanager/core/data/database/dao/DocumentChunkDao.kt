package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.postsaimanager.core.data.database.entity.DocumentChunkEntity

@Dao
interface DocumentChunkDao {

    /**
     * Every chunk, embedded or not.
     *
     * Deliberately not filtered to embedded rows. Keyword search has to work on text that
     * was never embedded — that is the whole degradation path when no model is installed —
     * and filtering here would leave it with an empty corpus. The semantic half skips
     * un-embedded rows itself.
     *
     * Retrieval loads the whole set and scores it in memory. At a few hundred documents
     * that is under a megabyte of floats and sub-millisecond to scan — a vector index would
     * be infrastructure without a problem. Revisit past five figures of documents.
     */
    @Query("SELECT * FROM document_chunks")
    suspend fun getAll(): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE documentId = :documentId ORDER BY ordinal ASC")
    suspend fun getForDocument(documentId: String): List<DocumentChunkEntity>

    /** Documents with no chunks yet — the backlog for indexing. */
    @Query(
        """
        SELECT d.id FROM documents d
        WHERE NOT EXISTS (SELECT 1 FROM document_chunks c WHERE c.documentId = d.id)
        """,
    )
    suspend fun getUnindexedDocumentIds(): List<String>

    /** Chunked but never embedded — the backlog left by indexing without a model. */
    @Query("SELECT DISTINCT documentId FROM document_chunks WHERE embedding IS NULL")
    suspend fun getDocumentIdsMissingEmbeddings(): List<String>

    /** Chunks embedded by a different model — incomparable, so they need re-embedding. */
    @Query("SELECT DISTINCT documentId FROM document_chunks WHERE embeddingModelId != :modelId")
    suspend fun getDocumentIdsEmbeddedByOtherModel(modelId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<DocumentChunkEntity>)

    @Query("DELETE FROM document_chunks WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: String)

    @Query("SELECT COUNT(*) FROM document_chunks WHERE embedding IS NOT NULL")
    suspend fun embeddedChunkCount(): Int
}
