package com.postsaimanager.core.domain.form

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.model.FormDataKey

/**
 * An embedder with scripted meaning: a key's description is its own axis, and a label is the weighted sum of the axes [intent]
 * names for it (best first), so the cosine ranking of the keys is the order the test scripted.
 */
class FakeEmbedder(
    private val keys: List<FormDataKey> = FormDataKeys.ALL,
    var ready: Boolean = true,
    private val intent: (String) -> List<String> = { emptyList() },
) : EmbeddingService {

    override val modelId = "fake-embedder"
    override val dimensions = keys.size
    override val isReady: Boolean get() = ready

    var embeddedTexts = 0
        private set

    override suspend fun embed(text: String): PamResult<FloatArray> = PamResult.Success(vector(text))

    override suspend fun embedAll(texts: List<String>): PamResult<List<FloatArray>> {
        if (!ready) return PamResult.Error(PamError.ModelNotLoaded("embedder"))
        embeddedTexts += texts.size
        return PamResult.Success(texts.map(::vector))
    }

    private fun vector(text: String): FloatArray {
        val v = FloatArray(keys.size)
        val own = keys.indexOfFirst { it.description == text }
        if (own >= 0) {
            v[own] = 1f
            return v
        }
        intent(text).forEachIndexed { rank, id ->
            val i = keys.indexOfFirst { it.id == id }
            if (i >= 0) v[i] = (RANKS - rank).toFloat()
        }
        return v
    }

    private companion object {
        const val RANKS = 3
    }
}
