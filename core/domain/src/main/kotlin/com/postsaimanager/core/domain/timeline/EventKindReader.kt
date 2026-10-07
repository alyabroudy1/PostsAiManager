package com.postsaimanager.core.domain.timeline

import java.util.Locale

/**
 * Scores a batch of yes/no questions in the letter's session and gives their log-odds in order, or null when the engine failed. The
 * reading's own scorer (it records and counts every batch like any other) is what is passed in: this is not a second scorer.
 */
fun interface EventScorer {
    suspend fun score(name: String, questions: List<String>): List<Double>?
}

/**
 * How the scores of the event-kind questions become a kind, as data per model (see `ScoringProfile.events`). A small model leans Yes on
 * every question about what a letter says, so the raw sign of a score decides nothing: a kind counts only when its score stands above what
 * the model says of the same kind over an empty letter ([kindBias], the content-free baseline) by more than [margin]. All numbers are
 * log-odds of Yes against No.
 *
 * **Unfitted.** No recording of these questions exists yet (recording is a device run), so [kindBias] is empty (every kind's baseline is
 * 0.0) and [margin] is 0.0. Fitting them is: score the questions over an empty letter once per model, put each score in [kindBias], and
 * choose a [margin] from the scored letters. The questions are asked in one batch (`event:kinds`), so one recording holds all of it.
 *
 * @property kindBias subtracted from a kind's score before kinds are compared and measured: the model's habitual lean for that kind
 *   over an empty letter; absent is 0.0.
 * @property margin a kind must stand above its baseline by more than this to be chosen; none doing so is "information".
 */
data class EventKindProfile(
    val kindBias: Map<String, Double> = emptyMap(),
    val margin: Double = 0.0,
) {
    /** [score] as a margin over the content-free baseline of [kindId]. */
    fun adjusted(kindId: String, score: Double): Double = score - (kindBias[kindId] ?: 0.0)
}

/** What one reading of the kinds decided: the chosen [kindId] (the fallback when none passed) and every kind's adjusted score. */
class EventKindReading(val kindId: String, val adjusted: Map<String, Double>)

/**
 * Chooses what a letter reports from the registry of event kinds ([EventKinds]) by score: ONE batch scores "is the main point of this
 * letter that it <kind>?" for every scored kind, each is measured against its content-free baseline, and the best kind that stands above
 * it by more than the margin wins. When none does, the letter is "information". The model never writes the kind; code only measures.
 */
class EventKindReader(
    private val scorer: EventScorer,
    private val profile: EventKindProfile = EventKindProfile(),
    private val kinds: EventKinds = EventKinds.DEFAULT,
    /** For the reading's trace: ids and scores only, never a word of the letter. */
    private val trace: (String) -> Unit = {},
) {

    /**
     * @param familyDescription what the reading decided the document is (the chosen category's description), as context for the
     *   question; null or blank for none.
     * @return the reading, or null when the kinds could not be scored (the stored events then stay).
     */
    suspend fun read(familyDescription: String? = null): EventKindReading? {
        val scored = kinds.scored
        val scores = scorer.score(BATCH, scored.map { EventQuestions.kind(it, familyDescription) })?.takeIf { it.size == scored.size } ?: return null
        val adjusted = scored.zip(scores).associate { (kind, score) -> kind.id to profile.adjusted(kind.id, score) }
        val best = adjusted.entries.maxByOrNull { it.value }?.takeIf { it.value > profile.margin }
        val chosen = best?.key ?: EventKinds.INFORMATION
        trace(
            String.format(
                Locale.ROOT, "event kinds=[%s] chosen=%s", adjusted.entries.joinToString(" ") { it.key + String.format(Locale.ROOT, "=%+.2f", it.value) }, chosen,
            ),
        )
        return EventKindReading(chosen, adjusted)
    }

    companion object {
        /** The batch's recorded name. */
        const val BATCH = "event:kinds"
    }
}

/**
 * The question the event kinds are scored with, asked in the letter's own session (the letter is the prefix, so a question is only its
 * own few words). English on purpose: the letter may be in any language. Pure text; it contains [MARKER], so a recording or a replay can
 * tell it from the questions of the reading itself.
 */
object EventQuestions {

    const val MARKER = "Is the main point of this letter that it"

    /** Whether [question] is one of this file's. */
    fun isEventQuestion(question: String): Boolean = MARKER in question

    /**
     * Whether the main point of the letter is what [kind] says. [familyDescription] is what the reading itself decided the document is: the
     * model judges the question knowing what it concluded, and code writes nothing of its own about any kind of document.
     */
    fun kind(kind: EventKind, familyDescription: String? = null): String =
        (familyDescription?.trim()?.takeIf { it.isNotEmpty() }?.let { "This document is: $it. " } ?: "") +
            "$MARKER ${kind.description}? Answer:"
}
