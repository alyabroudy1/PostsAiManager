package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.repository.DocumentChunkRepository
import com.postsaimanager.core.domain.repository.StoredChunk

/**
 * Deterministic [EmbeddingService] for tests.
 *
 * Vectors are assigned per registered phrase, so "similar" and "unrelated" are decided by
 * the test rather than by a real model. That keeps retrieval tests about *ranking* — which
 * is the logic under test — instead of about embedding quality, which is not.
 */
class FakeEmbeddingService(
    override val modelId: String = "fake-embed-v1",
    override val dimensions: Int = 4,
) : EmbeddingService {

    override var isReady: Boolean = true

    /** Exact-text → vector. Anything unregistered gets [defaultVector]. */
    private val vectors = mutableMapOf<String, FloatArray>()

    var defaultVector: FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    /** When set, [embed] fails — for exercising the degradation path. */
    var failWith: com.postsaimanager.core.common.result.PamError? = null

    fun register(text: String, vector: FloatArray) {
        vectors[text] = vector
    }

    override suspend fun embed(text: String): PamResult<FloatArray> {
        failWith?.let { return PamResult.Error(it) }
        return PamResult.Success(vectors[text] ?: defaultVector)
    }

    override suspend fun embedAll(texts: List<String>): PamResult<List<FloatArray>> {
        failWith?.let { return PamResult.Error(it) }
        return PamResult.Success(texts.map { vectors[it] ?: defaultVector })
    }
}

/** In-memory [DocumentChunkRepository]. */
class FakeDocumentChunkRepository : DocumentChunkRepository {

    private val chunks = mutableListOf<StoredChunk>()

    fun seed(vararg items: StoredChunk) {
        chunks += items
    }

    override suspend fun getAllEmbedded(): List<StoredChunk> =
        chunks.filter { it.embedding != null }

    override suspend fun getForDocument(documentId: String): List<StoredChunk> =
        chunks.filter { it.documentId == documentId }

    override suspend fun replaceChunks(documentId: String, chunks: List<StoredChunk>) {
        this.chunks.removeAll { it.documentId == documentId }
        this.chunks += chunks
    }

    override suspend fun unindexedDocumentIds(): List<String> = emptyList()

    override suspend fun documentIdsNeedingReindex(currentModelId: String): List<String> =
        chunks.filter { it.embeddingModelId != currentModelId }.map { it.documentId }.distinct()

    override suspend fun deleteForDocument(documentId: String) {
        chunks.removeAll { it.documentId == documentId }
    }

    override suspend fun embeddedChunkCount(): Int = chunks.count { it.embedding != null }
}

/** Convenience builder for test chunks. */
fun testChunk(
    id: String,
    documentId: String = "d1",
    ordinal: Int = 0,
    text: String,
    embedding: FloatArray? = null,
    embeddingModelId: String? = "fake-embed-v1",
) = StoredChunk(id, documentId, ordinal, text, embedding, embeddingModelId)
