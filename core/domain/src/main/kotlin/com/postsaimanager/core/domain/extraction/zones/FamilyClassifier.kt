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
 * @property family the best family, or the abstain family ([ExtractionSchema.FREE_FORM]) when none scored above the threshold.
 * @property familyConfidence the confidence word ([ScoringProfile.confidence]) of the family's margin over the runner-up; `LOW`
 *   for an abstain (the model itself leaned No on every family), `HIGH` for a family the user forced.
 * @property topics the topic ids above the topics threshold, best first; any number of them can hold.
 * @property scores every log-odds score the model gave, keyed `family:<id>` and `topic:<id>`; empty for what was not scored.
 */
data class Classification(
    val family: DocFamily,
    val familyConfidence: String,
    val topics: List<String>,
    val scores: Map<String, Double>,
)

/**
 * The one decision "what kind of document is this, and what is it about": a single batched scoring of "Is this document
 * `<family>`?" for every scored family and "Does this document concern `<topic>`?" for every topic, read from the letter's open
 * [PromptSession] (for a received letter, 10 + 14 = 24 scores, tree-shared).
 *
 * The family is the argmax when it beats [ScoringProfile.threshold] of [ScoringProfile.FAMILY], otherwise the abstain family:
 * [ExtractionSchema.FREE_FORM] is never scored, because a scored "anything else" gets a middling Yes on every letter and wins.
 * The topics are all those above the [ScoringProfile.TOPICS] threshold, so a bill about health is a bill with the topic `health`.
 *
 * Both entry points return null when the engine failed the batch; the caller decides what a failed classification means.
 * The batch is handed to [onRecord] as one [AskRecord] named `score:family`, the way the interpreter records its scored batches.
 *
 * @param schema the families and topics; [ExtractionSchema.DEFAULT] is the registry of extraction-v2-2.
 */
class FamilyClassifier(
    private val session: PromptSession,
    private val profile: ScoringProfile = ScoringProfile(),
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val onRecord: (AskRecord) -> Unit = {},
) {

    /**
     * Scores the families a document of [direction] can be and, when [includeTopics], the topics, in one batch.
     * [tail] is what closes the user turn and opens the assistant's (the interpreter's `closing`).
     */
    suspend fun classify(direction: DocDirection, tail: String = "", includeTopics: Boolean = true): Classification? {
        val families = schema.familiesFor(direction)
        val topics = if (includeTopics) schema.topics else emptyList()
        val scores = scoreBatch(families, topics, tail) ?: return null
        val familyScores = scores.subList(0, families.size)
        val topicScores = scores.subList(families.size, scores.size)
        val order = familyScores.indices.sortedByDescending { familyScores[it] }
        val best = order.firstOrNull()?.takeIf { familyScores[it] > profile.threshold(ScoringProfile.FAMILY) }
        val margin = when {
            best == null -> 0.0
            order.size > 1 -> familyScores[best] - familyScores[order[1]]
            else -> familyScores[best]
        }
        return Classification(
            family = if (best == null) abstainFamily() else families[best],
            familyConfidence = if (best == null) "LOW" else profile.confidence(margin),
            topics = topicsAbove(topics, topicScores),
            scores = keyed(families, familyScores, topics, topicScores),
        )
    }

    /**
     * The same for a family the user chose: the family scores are skipped (nothing decides the family), only the topics are scored.
     * With no topics to score, no batch is sent at all.
     */
    suspend fun classify(forced: DocFamily, tail: String = "", includeTopics: Boolean = true): Classification? {
        val topics = if (includeTopics) schema.topics else emptyList()
        val scores = scoreBatch(emptyList(), topics, tail) ?: return null
        return Classification(
            family = forced,
            familyConfidence = "HIGH",
            topics = topicsAbove(topics, scores),
            scores = keyed(emptyList(), emptyList(), topics, scores),
        )
    }

    /** The topics alone, for the profile that scores them in the second stage ([ModelProfile.topicsInFirstStage] false). */
    suspend fun topics(tail: String = ""): List<String>? {
        val topics = schema.topics
        val scores = scoreBatch(emptyList(), topics, tail) ?: return null
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
        topics: List<Topic>,
        topicScores: List<Double>,
    ): Map<String, Double> = buildMap {
        families.forEachIndexed { i, f -> put("family:${f.id}", familyScores[i]) }
        topics.forEachIndexed { i, t -> put("topic:${t.id}", topicScores[i]) }
    }

    /** One score per question in order: the families, then the topics. Null when the engine failed or there is nothing to ask. */
    private suspend fun scoreBatch(
        families: List<DocFamily>,
        topics: List<Topic>,
        tail: String,
    ): List<Double>? {
        val questions = families.map { "Is this document ${it.description}? Answer:" } +
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

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
