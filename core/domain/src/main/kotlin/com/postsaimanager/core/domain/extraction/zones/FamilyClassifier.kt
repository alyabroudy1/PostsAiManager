package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.v2.AskRecord
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.DocFamily
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.Topic

/**
 * What the classifier decided about a document.
 *
 * @property family the family that stands for the best category, or the abstain family ([ExtractionSchema.FREE_FORM], the neutral
 *   "Document") when none passed.
 * @property familyConfidence the confidence word ([ScoringProfile.confidence]) of the category's margin over the runner-up; `LOW` for an
 *   abstain (the model itself leaned No on every category).
 * @property topics the topic ids above the topics threshold, best first; any number of them can hold.
 * @property scores every log-odds score the model gave, keyed `family:<id>`, `baseline:family` and `topic:<id>`; empty for what was not scored.
 */
data class Classification(
    val family: DocFamily,
    val familyConfidence: String,
    val topics: List<String>,
    val scores: Map<String, Double>,
)

/**
 * The one decision "what broad kind of document is this, and what is it about", made AFTER the reading: a single batched scoring of
 * "Is this document `<category>`?" for the schema's categories ([ExtractionSchema.categoryFamilies]: one per [com.postsaimanager.core.domain.extraction.v2.DocCategory],
 * the statement being its stored family's description), and "Does this document concern `<topic>`?" for the topics, read from the letter's open
 * [PromptSession]. Every category question is preceded by what the reading found ([read]: the sender, the dates and amounts with their
 * meanings), so the model scores a document it has already understood instead of one it has only skimmed.
 *
 * A category is the winner when it beats [ScoringProfile.threshold] of [ScoringProfile.FAMILY], the runner-up by [ScoringProfile.familyMinMargin]
 * and, when the profile asks for one, a made-up kind of document scored in the same batch ([ScoringDescriptions.CATEGORY_BASELINE], the
 * content-free baseline: [ScoringProfile.categoryBaselineMargin]); otherwise the abstain family (the neutral "Document"). See
 * [ScoringProfile.familyWinner]. [ExtractionSchema.FREE_FORM] is never scored, because a scored "anything else" gets a middling Yes on every
 * letter and wins. The topics are all those above the [ScoringProfile.TOPICS] threshold.
 *
 * Both entry points return null when the engine failed the batch; the caller decides what a failed classification means.
 * The batch is handed to [onRecord] as one [AskRecord] named `score:family`, the way the interpreter records its scored batches.
 *
 * @param schema the categories and topics; [ExtractionSchema.DEFAULT] is the registry.
 */
class FamilyClassifier(
    private val session: PromptSession,
    private val profile: ScoringProfile = ScoringProfile(),
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val onRecord: (AskRecord) -> Unit = {},
) {

    /**
     * Scores the categories a document of [direction] can be and, when [includeTopics], the topics, in one batch.
     * [tail] is what closes the user turn and opens the assistant's (the interpreter's `closing`). [read] is what the reading found, put
     * before each category question (empty: the questions stand alone, as the first classifier asked them).
     */
    suspend fun classify(direction: DocDirection, tail: String = "", includeTopics: Boolean = true, read: String = ""): Classification? {
        val families = schema.categoryFamilies(direction)
        val topics = if (includeTopics) schema.topics else emptyList()
        val withBaseline = profile.categoryBaselineMargin != null && families.isNotEmpty()
        val scores = scoreBatch(families, withBaseline, topics, tail, read) ?: return null
        val familyScores = scores.subList(0, families.size)
        val baseline = if (withBaseline) scores[families.size] else null
        val topicScores = scores.subList(families.size + (if (withBaseline) 1 else 0), scores.size)
        val order = familyScores.indices.sortedByDescending { familyScores[it] }
        val best = profile.familyWinner(familyScores, baseline)
        val margin = when {
            best == null -> 0.0
            order.size > 1 -> familyScores[best] - familyScores[order[1]]
            else -> familyScores[best]
        }
        return Classification(
            family = if (best == null) abstainFamily() else families[best],
            familyConfidence = if (best == null) "LOW" else profile.confidence(margin),
            topics = topicsAbove(topics, topicScores),
            scores = keyed(families, familyScores, baseline, topics, topicScores),
        )
    }

    /** The topics alone, for the profile that scores them in the second stage ([ModelProfile.topicsInFirstStage] false). */
    suspend fun topics(tail: String = ""): List<String>? {
        val topics = schema.topics
        val scores = scoreBatch(emptyList(), false, topics, tail, "") ?: return null
        return topicsAbove(topics, scores)
    }

    private fun abstainFamily(): DocFamily = schema.abstain ?: error("the schema has no abstain family")

    private fun topicsAbove(topics: List<Topic>, scores: List<Double>): List<String> {
        val threshold = profile.threshold(ScoringProfile.TOPICS)
        return topics.indices.filter { scores[it] > threshold }.sortedByDescending { scores[it] }.map { topics[it].id }
    }

    private fun keyed(
        families: List<DocFamily>,
        familyScores: List<Double>,
        baseline: Double?,
        topics: List<Topic>,
        topicScores: List<Double>,
    ): Map<String, Double> = buildMap {
        families.forEachIndexed { i, f -> put("family:${f.id}", familyScores[i]) }
        if (baseline != null) put(BASELINE_KEY, baseline)
        topics.forEachIndexed { i, t -> put("topic:${t.id}", topicScores[i]) }
    }

    /** One score per question in order: the categories, the baseline when asked, then the topics. Null when the engine failed or there is nothing to ask. */
    private suspend fun scoreBatch(
        families: List<DocFamily>,
        withBaseline: Boolean,
        topics: List<Topic>,
        tail: String,
        read: String,
    ): List<Double>? {
        val questions = families.map { read + "Is this document ${it.description}? Answer:" } +
            (if (withBaseline) listOf(read + "Is this document ${ScoringDescriptions.CATEGORY_BASELINE}? Answer:") else emptyList()) +
            topics.map { "Does this document concern ${it.description}? Answer:" }
        if (questions.isEmpty()) return emptyList()
        val started = System.nanoTime()
        val result = session.score(questions.map { "\n\n$it$tail" }, ZoneScoringInterpreter.YES, ZoneScoringInterpreter.NO)
        val scores = (result as? PamResult.Success)?.data?.takeIf { it.size == questions.size }
        onRecord(
            AskRecord(
                name = "score:family",
                question = questions.joinToString(ZoneScoringInterpreter.BATCH_SEPARATOR),
                answer = scores?.joinToString(","),
                ms = (System.nanoTime() - started) / NANOS_PER_MS,
            ),
        )
        return scores
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L

        /** The key of the content-free baseline in [Classification.scores]. */
        const val BASELINE_KEY = "baseline:family"
    }
}
