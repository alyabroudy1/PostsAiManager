package com.postsaimanager.core.ai.engine

import android.util.Log

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import com.postsaimanager.core.ai.arabiya.ArabicConstants
import com.postsaimanager.core.ai.arabiya.ArabicStemLexicon
import com.postsaimanager.core.ai.arabiya.CaseEndingApplicator
import com.postsaimanager.core.ai.arabiya.DiacriticCombiner
import com.postsaimanager.core.ai.arabiya.HrmMappings
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
     * Parses Arabic text into a structured Syntax Tree with full tashkeel.
     */
    fun parse(text: String): PamResult<ArabicSyntaxTree> {
        val session = ortSession ?: return PamResult.Error(PamError.InferenceError("Session is not initialized."))
        val env = ortEnv ?: return PamResult.Error(PamError.InferenceError("Environment is null."))

        return try {
            // 1. Tokenization
            val tokens = tokenizer.tokenizeAndMap(text)

            // 2. Create ONNX Tensors
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

            val headsFlat = getFlatLongArray(result, "pred_heads")
            val relsFlat = getFlatLongArray(result, "pred_rels")
            val casesFlat = getFlatLongArray(result, "pred_cases")

            // 4. Close tensors
            inputMap.values.forEach { it.close() }
            result.close()

            // 5. Post-process: Apply full Arabiya diacritization pipeline
            val tree = buildSyntaxTreeWithTashkeel(text, tokens.words, headsFlat, relsFlat, casesFlat)
            PamResult.Success(tree)

        } catch (e: Exception) {
            Log.e("ORT_CRASH", "Parse Inference Exploded:", e)
            PamResult.Error(PamError.InferenceError("ORT Crash: ${e.stackTraceToString()}"))
        }
    }

    /**
     * Full post-processing pipeline matching the Python Arabiya engine.
     *
     * Steps:
     * 1. Map integer predictions → UD strings (case tag, relation, POS)
     * 2. Infer morphological features (definiteness, number, gender)
     * 3. Look up stem diacritics from lexicon
     * 4. Apply case ending diacritics
     * 5. Combine stem + case ending → final diacritized word
     */
    private fun buildSyntaxTreeWithTashkeel(
        originalText: String,
        words: List<String>,
        headsFlat: LongArray,
        relsFlat: LongArray,
        casesFlat: LongArray
    ): ArabicSyntaxTree {
        val nodes = mutableListOf<SyntaxNode>()
        val diacritizedWords = mutableListOf<String>()

        for ((idx, word) in words.withIndex()) {
            val head = headsFlat.getOrElse(idx) { 0L }.toInt()
            val relKey = relsFlat.getOrElse(idx) { 0L }.toInt()
            val caseKeyRaw = casesFlat.getOrElse(idx) { 0L }.toInt()

            // Step 1: Map integer predictions → UD strings
            val caseName = HrmMappings.CASE_CLASSES[caseKeyRaw]
            val relation = HrmMappings.DEP_RELS_REVERSE[relKey] ?: "dep"

            // Step 2: Infer POS from relation + case + word morphology
            val bareWord = ArabicConstants.stripDiacritics(word)
            val posTag = if (idx == 0 && word == "<ROOT>") {
                "X"
            } else {
                HrmMappings.inferPosFromRelation(bareWord, relation, caseName)
            }

            // Step 3: Infer morphological features
            val features = HrmMappings.inferFeatures(bareWord, relation, posTag)
            val isDefinite = features["definite"] == "yes" || ArabicConstants.hasDefiniteArticle(bareWord)
            val number = features["number"] ?: "sing"
            val gender = features["gender"] ?: "masc"
            val pluralType = features["plural_type"] ?: ""
            val isConstruct = features["construct"] == "yes"
            val verbForm = features["verb_form"] ?: ""

            // Step 4: Look up stem diacritics from lexicon
            val stemDiacritized = if (idx == 0) {
                ""
            } else {
                ArabicStemLexicon.lookup(bareWord, posTag) ?: ""
            }
            val diacSource = when {
                idx == 0 -> "root"
                stemDiacritized.isNotBlank() -> "lexicon"
                !bareWord.any { it in '\u0621'..'\u064A' } -> "passthrough"
                else -> "none"
            }

            // Step 5: Apply case ending and combine
            val caseDiacChar = if (idx == 0) null else {
                CaseEndingApplicator.getCaseDiacritic(
                    CaseEndingApplicator.WordContext(
                        caseName = caseName,
                        pos = posTag,
                        bareWord = bareWord,
                        isDefinite = isDefinite,
                        isConstruct = isConstruct,
                        number = number,
                        gender = gender,
                        pluralType = pluralType,
                        verbForm = verbForm
                    )
                )
            }

            val combineResult = DiacriticCombiner.combine(
                stemDiacritized = stemDiacritized,
                bareWord = bareWord,
                caseDiacriticChar = caseDiacChar
            )

            val displayWord = combineResult.finalDiacritized

            if (idx > 0) { // Skip <ROOT>
                diacritizedWords.add(displayWord)
            }

            nodes.add(
                SyntaxNode(
                    id = idx,
                    word = bareWord,
                    diacritizedWord = displayWord,
                    stemDiacritized = stemDiacritized,
                    caseDiacritic = combineResult.caseDiacritic,
                    headId = head,
                    posTag = posTag,
                    relation = relation,
                    caseName = caseName,
                    caseKey = caseKeyRaw,
                    relationKey = relKey,
                    isDefinite = isDefinite,
                    number = number,
                    gender = gender,
                    isRoot = (idx == 0),
                    diacSource = diacSource
                )
            )
        }

        return ArabicSyntaxTree(
            sentence = originalText,
            diacritizedSentence = diacritizedWords.joinToString(" "),
            nodes = nodes
        )
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
