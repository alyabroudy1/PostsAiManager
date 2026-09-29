package com.postsaimanager.core.ai.embed

import kotlin.math.sqrt

/**
 * Turns a transformer's per-token output into one sentence vector.
 *
 * The ONNX graph stops at `last_hidden_state` — one vector **per token**. Reducing that to
 * a single embedding is arithmetic the model does not do, and getting it wrong produces
 * vectors that are subtly, silently poor rather than obviously broken. Kept pure so it can
 * be tested without a model.
 */
object Pooling {

    /**
     * Mean of the token vectors, **weighted by the attention mask**.
     *
     * The mask is not optional. Padding positions still produce output vectors, and
     * averaging them in makes a short sentence's embedding depend on how much padding it
     * happened to receive — so the same text encoded at two different lengths would land in
     * two different places. This is the standard sentence-transformers pooling.
     *
     * @param tokenVectors `[sequenceLength][hiddenSize]`
     * @param attentionMask 1 for real tokens, 0 for padding
     */
    fun meanPool(tokenVectors: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        if (tokenVectors.isEmpty()) return FloatArray(0)
        val hiddenSize = tokenVectors[0].size
        val sums = FloatArray(hiddenSize)
        var counted = 0

        for (position in tokenVectors.indices) {
            if (position >= attentionMask.size || attentionMask[position] == 0L) continue
            val vector = tokenVectors[position]
            for (dim in 0 until minOf(hiddenSize, vector.size)) {
                sums[dim] += vector[dim]
            }
            counted++
        }

        // An all-zero mask would divide by zero; return the zero vector, which scores 0
        // similarity against everything rather than producing NaNs that poison ranking.
        if (counted == 0) return FloatArray(hiddenSize)

        for (dim in sums.indices) sums[dim] /= counted
        return sums
    }

    /**
     * Scales to unit length.
     *
     * Cosine similarity is scale-invariant, so this is not strictly required for ranking —
     * but normalising once at write time means retrieval could later use a plain dot
     * product, and it keeps stored vectors on a consistent scale for debugging.
     */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (value in vector) sumSquares += value * value
        val norm = sqrt(sumSquares).toFloat()
        // A zero vector has no direction to preserve; scaling it would divide by zero.
        if (norm == 0f || !norm.isFinite()) return vector.copyOf()
        return FloatArray(vector.size) { vector[it] / norm }
    }
}
