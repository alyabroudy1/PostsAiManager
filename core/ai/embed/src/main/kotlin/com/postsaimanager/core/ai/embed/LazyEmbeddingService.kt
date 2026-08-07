package com.postsaimanager.core.ai.embed

import android.util.Log
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [EmbeddingService] the app injects. Loads the encoder the first time something
 * actually needs it.
 *
 * ### Why lazily
 *
 * Loading costs ~400 ms and holds ~250 MB of model. Most launches never embed anything —
 * the user opens a document, checks a date, leaves — so paying that at startup would slow
 * every launch for a feature used in some of them. Indexing and search both go through
 * here, and both already run off the main thread.
 *
 * ### Why [isReady] does not load
 *
 * It is a plain property, so it cannot suspend, and callers use it to *decide* whether to
 * attempt semantic search. It therefore answers the question it can answer honestly —
 * "is a model installed?" — and the real load happens inside [embed] / [embedAll]. A model
 * that is present but unloadable is reported through the result of those calls, where the
 * caller can degrade, rather than through a property that has no way to say so.
 *
 * A failed load is not retried. Re-reading a corrupt 250 MB file once per chunk would turn
 * one broken model into a stalled indexing run; [reset] clears the flag once the file has
 * been replaced.
 */
@Singleton
class LazyEmbeddingService @Inject constructor(
    private val files: EmbeddingModelFiles,
    private val delegate: OnnxEmbeddingService,
) : EmbeddingService {

    private val loadMutex = Mutex()

    @Volatile
    private var loadFailed = false

    /**
     * The id vectors are stored under and compared by.
     *
     * Comes from [EmbeddingModelFiles], not the delegate, so it is correct *before* the
     * first load. Reading it from the delegate would return "none" until something happened
     * to embed, and retrieval would discard every stored vector as belonging to another
     * model.
     */
    override val modelId: String get() = files.modelId

    override val dimensions: Int get() = delegate.dimensions

    override val isReady: Boolean get() = delegate.isReady || (!loadFailed && files.arePresent())

    override suspend fun embed(text: String): PamResult<FloatArray> {
        ensureLoaded()?.let { return it }
        return delegate.embed(text)
    }

    override suspend fun embedAll(texts: List<String>): PamResult<List<FloatArray>> {
        ensureLoaded()?.let { return it }
        return delegate.embedAll(texts)
    }

    /** Allows a newly downloaded or repaired model to be tried again. */
    suspend fun reset() {
        loadMutex.withLock {
            loadFailed = false
            delegate.release()
        }
    }

    /** @return the error to return to the caller, or null if the model is loaded. */
    private suspend fun ensureLoaded(): PamResult.Error? {
        if (delegate.isReady) return null

        return loadMutex.withLock {
            // Another caller may have loaded it while this one waited for the lock.
            if (delegate.isReady) return@withLock null
            if (loadFailed) return@withLock notInstalled()
            if (!files.arePresent()) return@withLock notInstalled()

            when (
                val result = delegate.load(
                    modelFile = files.modelFile,
                    vocabFile = files.vocabFile,
                    modelId = files.modelId,
                    doLowerCase = files.doLowerCase,
                )
            ) {
                is PamResult.Success -> null
                is PamResult.Error -> {
                    Log.w(TAG, "embedding model failed to load: ${result.error.userMessage}")
                    loadFailed = true
                    result
                }
            }
        }
    }

    private fun notInstalled() =
        PamResult.Error(PamError.ModelNotLoaded("embedding model"))

    private companion object {
        const val TAG = "LazyEmbedding"
    }
}
