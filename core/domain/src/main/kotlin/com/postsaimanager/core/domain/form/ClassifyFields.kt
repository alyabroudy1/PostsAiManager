package com.postsaimanager.core.domain.form

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.ai.VectorMath
import com.postsaimanager.core.domain.form.fill.FormFillTrace
import com.postsaimanager.core.model.FormDataKey
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormValueKind

/** What a field asks for: a [FormDataKey] id (null when none is clear), how sure, and the kind refined from the key. */
data class KeyDecision(val dataKey: String?, val confidence: Float, val kind: FormFieldKind)

/**
 * Decides which personal detail each field asks for, in two cheap stages:
 * 1. the app's multilingual embedding model ranks the [keys] against the field's label (milliseconds, any language);
 * 2. the model scores only the top [FormScoringProfile.keyCandidates] keys and "none": "Does «label» ask for <key description>?".
 *
 * The best key is taken when its score is above the threshold and above "none"; its confidence comes from its margin over the
 * runner-up. A signature, a date or a yes/no key then refines the field's kind. The model only chooses among the registry's keys:
 * it never writes a value. Without an embedding model the model scores every key per field instead ([classifyByModel]).
 */
class ClassifyFields(
    private val scorer: FormScorer,
    private val embedder: EmbeddingService,
    private val profile: FormScoringProfile = FormScoringProfile(),
    private val keys: List<FormDataKey> = FormDataKeys.ALL,
    private val trace: FormFillTrace = FormFillTrace.NONE,
) {

    private var keyVectors: List<FloatArray>? = null

    /** One decision per candidate, in order. Throws [FormScoringException] when the engine fails. */
    suspend fun classify(candidates: List<FieldCandidate>): List<KeyDecision> {
        val decisions = classifyAll(candidates)
        trace.event("classify", "fields=${candidates.size} keyed=${decisions.count { it.dataKey != null }}")
        return decisions
    }

    private suspend fun classifyAll(candidates: List<FieldCandidate>): List<KeyDecision> {
        val none = candidates.map { KeyDecision(null, 0f, it.kind) }
        if (candidates.isEmpty() || keys.isEmpty()) return none
        val ready = embedder.checkReady()
        trace.event("classify_embeddings", "ready=$ready")
        val vectors = (if (ready) keyVectors ?: embedKeys() else null) ?: return classifyByModel(candidates)
        val labelVectors = (embedder.embedAll(candidates.map { it.labelText }) as? PamResult.Success)?.data
            ?.takeIf { it.size == candidates.size } ?: return classifyByModel(candidates)

        val top = labelVectors.map { v ->
            keys.indices.sortedByDescending { VectorMath.cosineSimilarity(v, vectors[it]) }.take(profile.keyCandidates.coerceAtLeast(1))
        }
        val perField = (top.firstOrNull()?.size ?: 0) + 1
        val scored = (profile.maxClassifyScores / perField).coerceAtMost(candidates.size)
        val questions = (0 until scored).flatMap { i -> questionsFor(candidates[i], top[i]) }
        val scores = scorer.yesNo(questions)

        return candidates.indices.map { i ->
            if (i >= scored) none[i] else decide(candidates[i], top[i], scores.subList(i * perField, (i + 1) * perField))
        }
    }

    /**
     * The fallback without an embedding model: the model scores every key for each field, the field's label read once as the shared
     * prefix of that field's questions (so a field costs its label once plus one short question per key). At most
     * [FormScoringProfile.maxFallbackClassifyScores] scores are spent, over the fields in order; the rest get no key.
     */
    private suspend fun classifyByModel(candidates: List<FieldCandidate>): List<KeyDecision> {
        val all = keys.indices.toList()
        val perField = all.size + 1
        val scored = (profile.maxFallbackClassifyScores / perField).coerceAtMost(candidates.size)
        return candidates.mapIndexed { i, c ->
            if (i >= scored) return@mapIndexed KeyDecision(null, 0f, c.kind)
            val where = c.section?.let { " (in the part «$it»)" }.orEmpty()
            val shared = "Field «${c.labelText}»$where."
            val questions = all.map { "Does the field above ask for ${keys[it].description}? Answer:" } +
                "Does the field above ask for something other than the details listed? Answer:"
            decide(c, all, scorer.yesNo(questions, shared))
        }
    }

    private suspend fun embedKeys(): List<FloatArray>? {
        val r = embedder.embedAll(keys.map { it.description }) as? PamResult.Success ?: return null
        return r.data.takeIf { it.size == keys.size }?.also { keyVectors = it }
    }

    private fun questionsFor(c: FieldCandidate, ranked: List<Int>): List<String> {
        val where = c.section?.let { " (in the part «$it»)" }.orEmpty()
        return ranked.map { "Does «${c.labelText}»$where ask for ${keys[it].description}? Answer:" } +
            "Does «${c.labelText}»$where ask for something other than the details above? Answer:"
    }

    /** [scores] are the ranked keys' scores, then "none". */
    private fun decide(c: FieldCandidate, ranked: List<Int>, scores: List<Double>): KeyDecision {
        val keyScores = scores.subList(0, ranked.size)
        val noneScore = scores.last()
        val best = keyScores.indices.maxByOrNull { keyScores[it] } ?: return KeyDecision(null, 0f, c.kind)
        val runnerUp = (keyScores.filterIndexed { i, _ -> i != best } + noneScore).max()
        val margin = keyScores[best] - runnerUp
        if (keyScores[best] <= profile.keyThreshold || keyScores[best] <= noneScore || margin < profile.keyMinMargin) {
            return KeyDecision(null, profile.confidence(margin.coerceAtMost(0.0)), c.kind)
        }
        val key = keys[ranked[best]]
        return KeyDecision(key.id, profile.confidence(margin), refineKind(c.kind, key))
    }

    private fun refineKind(kind: FormFieldKind, key: FormDataKey): FormFieldKind = when {
        kind == FormFieldKind.CHOICE -> kind
        key.id == FormDataKeys.SIGNATURE.id -> FormFieldKind.SIGNATURE
        key.valueKind == FormValueKind.DATE && kind != FormFieldKind.CHECKBOX -> FormFieldKind.DATE
        key.valueKind == FormValueKind.BOOLEAN && kind == FormFieldKind.TEXT -> FormFieldKind.CHECKBOX
        else -> kind
    }
}
