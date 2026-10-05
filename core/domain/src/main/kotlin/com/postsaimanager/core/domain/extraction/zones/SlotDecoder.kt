package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind

/**
 * What a decoder knows of one candidate: its identity and value, never the words it was printed in. Pure data, so a decoder
 * is testable without an engine, a letter or a recording.
 *
 * @property normalized the canonical value (ISO date, `1284.50 EUR`, compact IBAN): what a consistency constraint compares.
 */
class CandidateFacts(
    val id: String,
    val kind: CandidateKind,
    val normalized: String,
    val cents: Long? = null,
    val currency: String? = null,
)

/** A candidate with the model's score for it on one question (log-odds of Yes over No). */
class ScoredCandidate(val facts: CandidateFacts, val score: Double) {
    val id: String get() = facts.id
}

/**
 * One scored question (a slot or a party): every candidate the model scored, in the order it scored them.
 *
 * @property name the question's name ([QuestionNames]): what a constraint refers to.
 * @property threshold the abstain level: a candidate is taken only when it scores above it, otherwise the answer is none.
 * @property excludesWinnerOf the question whose winner this one may not take (the sender is not also the addressee), in
 *   the order the questions were asked: the per-slot decoder honours it only as far as that question was decided first.
 * @property prior the score the same candidate gets on the same question with the letter's content removed (a content-free
 *   baseline), per candidate; null until the device has recorded one. Only [ScoreCalibration.CONTENT_FREE] reads it.
 */
class ScoredQuestion(
    val name: String,
    val candidates: List<ScoredCandidate>,
    val threshold: Double,
    val excludesWinnerOf: String? = null,
    val prior: List<Double>? = null,
)

/**
 * Turns the model's scores for every (question, candidate) pair of a letter into the answer of each question: the id of the
 * candidate it takes, or null for none. The scores are the AI's judgement; a decoder only combines them with constraints that
 * do not depend on any language (one value answers one question, a due date is not before the letter date, net plus VAT is
 * gross), so the same letter read in another language decodes the same way.
 */
interface SlotDecoder {

    /**
     * @param questions in the order they were asked.
     * @param pool every candidate offered for the letter, scored or not (a consistency check needs the amounts that were
     *   not scored for this question).
     * @return every question's name, each mapped to its chosen candidate id or null.
     */
    fun decode(questions: List<ScoredQuestion>, pool: List<CandidateFacts>): Map<String, String?>
}

/**
 * Today's reading: each question takes its own best candidate above its threshold, whatever the other questions took, except
 * that a question with [ScoredQuestion.excludesWinnerOf] leaves out the candidate that question already took.
 */
object PerSlotArgmax : SlotDecoder {
    override fun decode(questions: List<ScoredQuestion>, pool: List<CandidateFacts>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (q in questions) {
            val barred = q.excludesWinnerOf?.let { out[it] }
            val allowed = q.candidates.filter { it.id != barred }
            val best = allowed.maxByOrNull { it.score }
            out[q.name] = if (best == null || best.score <= q.threshold) null else best.id
        }
        return out
    }
}
