package com.postsaimanager.core.ai.engine

import android.util.Log

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import com.postsaimanager.core.ai.tokenizer.ArabicTokenizer
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.ArabicSyntaxTree
import com.postsaimanager.core.model.SyntaxNode
import java.nio.LongBuffer

class OnnxArabicSyntaxEngine {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val tokenizer = ArabicTokenizer()

    val isReady: Boolean
        get() = ortSession != null

    /**
     * Initializes the ONNX session given the absolute path to the .onnx model file.
     */
    fun initialize(modelPath: String): PamResult<Unit> {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                // Optimize for mobile Edge CPU
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            ortSession = ortEnv?.createSession(modelPath, sessionOptions)
            
            if (ortSession != null) {
                PamResult.Success(Unit)
            } else {
                PamResult.Error(PamError.InferenceError("Failed to initialize ONNX Session."))
            }
        } catch (e: Exception) {
            Log.e("ORT_CRASH", "Failed Initialization", e)
            PamResult.Error(PamError.InferenceError(e.message ?: "Unknown init crash", e))
        }
    }

    /**
     * Parses Arabic text into a structured Syntax Tree.
     */
    fun parse(text: String): PamResult<ArabicSyntaxTree> {
        val session = ortSession ?: return PamResult.Error(PamError.InferenceError("Session is not initialized."))
        val env = ortEnv ?: return PamResult.Error(PamError.InferenceError("Environment is null."))

        return try {
            // 1. Tokenization and matrix generation
            val tokens = tokenizer.tokenizeAndMap(text)

            // 2. Wrap basic arrays into n-dimensional ONNX Tensors
            val b = 1L
            val w = ArabicTokenizer.MAX_WORDS.toLong()
            val c = ArabicTokenizer.MAX_CHARS.toLong()
            val s = ArabicTokenizer.MAX_SUBWORDS.toLong()

            val wShape = longArrayOf(b, w)
            val cShape = longArrayOf(b, w, c)
            val sShape = longArrayOf(b, w, s)

            val inputMap = mapOf(
                "word_ids"      to createTensor(env, tokens.wordIds, wShape),
                "pos_tags"      to createTensor(env, tokens.posTags, wShape),
                "char_ids"      to createTensor(env, tokens.charIds, cShape),
                "bpe_ids"       to createTensor(env, tokens.bpeIds, sShape),
                "root_ids"      to createTensor(env, tokens.rootIds, wShape),
                "pattern_ids"   to createTensor(env, tokens.patternIds, wShape),
                "proclitic_ids" to createTensor(env, tokens.procliticIds, wShape),
                "enclitic_ids"  to createTensor(env, tokens.encliticIds, wShape),
                "diac_ids"      to createTensor(env, tokens.diacIds, cShape),
                "mask"          to createTensor(env, tokens.mask, wShape)
            )

            // 3. Run Inference
            val result = session.run(inputMap)

            // Output Shapes:
            // pred_heads: [B, W]
            // pred_rels:  [B, W]
            // pred_cases: [B, W]

            // We must cast them depending on how PyTorch exported them.
            // Typically argmax() outputs LongTensors (INT64).
            val headsFlat = getFlatLongArray(result, "pred_heads")
            val relsFlat = getFlatLongArray(result, "pred_rels")
            val casesFlat = getFlatLongArray(result, "pred_cases")

            // 4. Close tensors to avoid memory leaks
            inputMap.values.forEach { it.close() }
            result.close()

            // 5. Reconstruct the syntax tree
            val nodes = mutableListOf<SyntaxNode>()
            // The valid words length depends on the un-padded representation
            for ((idx, word) in tokens.words.withIndex()) {
                val head = headsFlat[idx].toInt()
                val rel = relsFlat[idx].toInt()
                val caseKey = casesFlat[idx].toInt()

                nodes.add(
                    SyntaxNode(
                        id = idx,
                        word = word,
                        headId = head,
                        relationKey = rel,
                        caseKey = caseKey,
                        isRoot = (idx == 0)
                    )
                )
            }

            PamResult.Success(ArabicSyntaxTree(sentence = text, nodes = nodes))
            
        } catch (e: Exception) {
            Log.e("ORT_CRASH", "Parse Inference Exploded:", e)
            PamResult.Error(PamError.InferenceError("ORT Crash: ${e.stackTraceToString()}"))
        }
    }

    private fun createTensor(env: OrtEnvironment, data: LongArray, shape: LongArray): OnnxTensor {
        val buffer = LongBuffer.wrap(data)
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    private fun getFlatLongArray(result: OrtSession.Result, name: String): LongArray {
        val value = result.get(name).get().value
        return when (value) {
            is LongArray -> value
            is Array<*> -> {
                // If it's a 2D array Long[][]
                try {
                    @Suppress("UNCHECKED_CAST")
                    val arr2D = value as Array<LongArray>
                    arr2D[0] // since B=1
                } catch (e: Exception) {
                    LongArray(0)
                }
            }
            else -> LongArray(0)
        }
    }

    fun release() {
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
    }
}
