package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.common.result.PamResult

/**
 * Turns text into a vector for semantic search.
 *
 * A port, not an implementation: retrieval logic is testable without an ML runtime, and the
 * embedding model can be swapped — or absent — without touching anything above it.
 *
 * Kept separate from [AiEngine] deliberately. Embedding is far cheaper than generation, so
 * a device that cannot run a chat model can still index and search its documents. Fusing
 * the two would make search a casualty of that limit for no reason.
 */
interface EmbeddingService {

    /** Identifies the model. Vectors from different models are not comparable. */
    val modelId: String

    val dimensions: Int

    /** True once a model is available; false means retrieval degrades to keyword search. */
    val isReady: Boolean

    suspend fun embed(text: String): PamResult<FloatArray>

    /**
     * Batch form. Implementations may process these together, which is markedly faster than
     * one call per chunk on the same hardware.
     */
    suspend fun embedAll(texts: List<String>): PamResult<List<FloatArray>>
}

/** Vector maths for retrieval. Pure, so it is testable without a model. */
object VectorMath {

    /**
     * Cosine similarity in [-1, 1].
     *
     * Computed in a single pass rather than as three: the norms are needed anyway, and
     * separate passes over a 384-float vector triple the memory traffic for no gain.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (Math.sqrt(normA) * Math.sqrt(normB))).toFloat()
    }

    /** Little-endian float32, matching how embeddings are stored as a BLOB. */
    fun toBytes(vector: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        vector.forEach(buffer::putFloat)
        return buffer.array()
    }

    /** @return null if [bytes] is not a whole number of float32s — corrupt rather than empty. */
    fun fromBytes(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return null
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }
}
