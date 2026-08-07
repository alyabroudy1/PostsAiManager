package com.postsaimanager.core.ai.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sentence embeddings via ONNX Runtime.
 *
 * Three stages, only the middle one of which the model file provides:
 *
 *  1. [WordPieceTokenizer] — text to token ids. Not in the ONNX graph; its inputs are
 *     int64 tensors.
 *  2. The transformer — token ids to `last_hidden_state`, one vector per token.
 *  3. [Pooling] — those per-token vectors to one normalised sentence vector. Also not in
 *     the graph.
 *
 * ### Why this is separate from `:core:ai:local`
 *
 * Embedding is far cheaper than generation — a ~250 MB encoder against a multi-gigabyte
 * LLM — so a device that cannot run chat can still index and search its documents. Fusing
 * the two would make search a casualty of that limit for no reason.
 *
 * It also stays **in-process**, unlike llama.cpp. ONNX Runtime is a managed inference
 * runtime without the C++ abort paths that made process isolation necessary there, and a
 * boundary would cost IPC on every chunk during indexing for no safety gained.
 */
@Singleton
class OnnxEmbeddingService @Inject constructor(
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : EmbeddingService {

    private val mutex = Mutex()

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null

    @Volatile
    private var loadedModelId: String = NO_MODEL

    @Volatile
    private var hiddenSize: Int = 0

    override val modelId: String get() = loadedModelId

    override val dimensions: Int get() = hiddenSize

    override val isReady: Boolean get() = session != null && tokenizer != null

    /**
     * Loads the encoder and its vocabulary.
     *
     * @param modelId recorded against every vector produced. Vectors from different models
     *   are not comparable, so retrieval uses this to exclude stale embeddings rather than
     *   scoring across incompatible spaces.
     */
    suspend fun load(
        modelFile: File,
        vocabFile: File,
        modelId: String,
        doLowerCase: Boolean = false,
    ): PamResult<Unit> = mutex.withLock {
        withContext(ioDispatcher) {
            if (!modelFile.exists()) {
                return@withContext PamResult.Error(PamError.FileNotFound(modelFile.absolutePath))
            }
            if (!vocabFile.exists()) {
                return@withContext PamResult.Error(PamError.FileNotFound(vocabFile.absolutePath))
            }

            releaseLocked()

            try {
                val vocab = vocabFile.bufferedReader().useLines(WordPieceTokenizer::parseVocab)
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    // Indexing runs in the background; leaving cores for the UI matters
                    // more than finishing a batch marginally sooner.
                    setIntraOpNumThreads(threadCount())
                    // BASIC_OPT, not ALL_OPT. The extended fusions rewrite the graph
                    // aggressively and fail on fp16 exports of this model:
                    //   ORT_FAIL ... SimplifiedLayerNormFusion ... InsertedPrecisionFreeCast_
                    // An ORT graph-optimiser limitation rather than a model defect. The
                    // basic level still folds constants and eliminates dead nodes; the
                    // extended passes buy little on an encoder this size.
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                }
                val newSession = env.createSession(modelFile.absolutePath, options)

                environment = env
                session = newSession
                tokenizer = WordPieceTokenizer(vocab, doLowerCase = doLowerCase)
                loadedModelId = modelId
                hiddenSize = 0 // discovered on first inference

                Log.i(
                    TAG,
                    "loaded $modelId vocab=${vocab.size} inputs=${newSession.inputNames}",
                )
                PamResult.Success(Unit)
            } catch (e: Exception) {
                releaseLocked()
                PamResult.Error(
                    PamError.InferenceError(
                        "Could not load the embedding model: ${e.message}",
                        e,
                    ),
                )
            }
        }
    }

    override suspend fun embed(text: String): PamResult<FloatArray> =
        embedAll(listOf(text)).let { result ->
            when (result) {
                is PamResult.Success -> result.data.firstOrNull()
                    ?.let { PamResult.Success(it) }
                    ?: PamResult.Error(PamError.InferenceError("Embedding produced no output"))
                is PamResult.Error -> result
            }
        }

    override suspend fun embedAll(texts: List<String>): PamResult<List<FloatArray>> =
        mutex.withLock {
            withContext(ioDispatcher) {
                val currentSession = session
                val currentTokenizer = tokenizer
                val env = environment

                if (currentSession == null || currentTokenizer == null || env == null) {
                    return@withContext PamResult.Error(
                        PamError.ModelNotLoaded("embedding model"),
                    )
                }
                if (texts.isEmpty()) return@withContext PamResult.Success(emptyList())

                try {
                    val encodings = texts.map { currentTokenizer.encode(it, MAX_SEQUENCE_LENGTH) }
                    val batch = encodings.size

                    // Pad to the longest sequence in THIS batch, not to the 256 cap.
                    // Attention cost is quadratic in length, so padding a ten-word chunk
                    // out to 256 spends most of the compute on positions the mask then
                    // discards. Measured on device, a fixed 256 made batching worthless
                    // (1649 ms one-by-one vs 1554 ms batched for 8 short sentences).
                    // Masked mean pooling is what makes this safe: the vector for a given
                    // text is identical at any padding length, which
                    // `batchingDoesNotChangeResults` pins on the real model.
                    val length = encodings.maxOf { encoding ->
                        encoding.attentionMask.count { it != 0L }
                    }.coerceIn(1, MAX_SEQUENCE_LENGTH)

                    val ids = LongArray(batch * length)
                    val mask = LongArray(batch * length)
                    val types = LongArray(batch * length)
                    encodings.forEachIndexed { row, encoding ->
                        // `encode` pads to the cap; take only the prefix this batch needs.
                        encoding.inputIds.copyInto(ids, row * length, 0, length)
                        encoding.attentionMask.copyInto(mask, row * length, 0, length)
                        encoding.tokenTypeIds.copyInto(types, row * length, 0, length)
                    }

                    val shape = longArrayOf(batch.toLong(), length.toLong())
                    val tensors = mutableMapOf<String, OnnxTensor>()

                    // Built from what the session actually declares. DistilBERT has no
                    // token-type embeddings and rejects `token_type_ids`, while BERT
                    // requires it — passing a fixed set breaks one model or the other.
                    val expected = currentSession.inputNames
                    if (INPUT_IDS in expected) {
                        tensors[INPUT_IDS] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
                    }
                    if (ATTENTION_MASK in expected) {
                        tensors[ATTENTION_MASK] =
                            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
                    }
                    if (TOKEN_TYPE_IDS in expected) {
                        tensors[TOKEN_TYPE_IDS] =
                            OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape)
                    }

                    val vectors = currentSession.run(tensors).use { results ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = results[0].value as Array<Array<FloatArray>>
                        if (hidden.isNotEmpty() && hidden[0].isNotEmpty()) {
                            hiddenSize = hidden[0][0].size
                        }
                        hidden.mapIndexed { row, tokenVectors ->
                            // The mask must be the trimmed one; the encoding still carries
                            // its full-cap mask, which would not line up with `length`.
                            Pooling.l2Normalize(
                                Pooling.meanPool(
                                    tokenVectors,
                                    mask.copyOfRange(row * length, (row + 1) * length),
                                ),
                            )
                        }
                    }

                    tensors.values.forEach(OnnxTensor::close)
                    PamResult.Success(vectors)
                } catch (e: Exception) {
                    Log.e(TAG, "embedding failed", e)
                    PamResult.Error(
                        PamError.InferenceError("Embedding failed: ${e.message}", e),
                    )
                }
            }
        }

    suspend fun release() = mutex.withLock {
        withContext(ioDispatcher) { releaseLocked() }
    }

    /** Caller must hold [mutex]. */
    private fun releaseLocked() {
        runCatching { session?.close() }
        session = null
        tokenizer = null
        loadedModelId = NO_MODEL
        hiddenSize = 0
        // OrtEnvironment is process-wide and shared; closing it would break any other user.
        environment = null
    }

    private companion object {
        const val TAG = "OnnxEmbedding"
        const val NO_MODEL = "none"

        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val TOKEN_TYPE_IDS = "token_type_ids"

        /**
         * Chunks target ~1200 characters, comfortably inside this. Shorter sequences are
         * markedly faster — cost is quadratic in length for attention — so this is a cap,
         * not a target.
         */
        const val MAX_SEQUENCE_LENGTH = 256

        fun threadCount(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}
