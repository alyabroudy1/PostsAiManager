package com.postsaimanager.core.domain.extraction.actions

/**
 * Chooses the action kinds from their scores. Pure: the model scored, this decides, by numbers that are data ([ActionKindProfile]).
 *
 * Nothing is chosen when the letter's "does it ask anything?" score is below [ActionKindProfile.anyThreshold]. Otherwise a kind's score
 * is adjusted by the model's habitual lean for that kind, the best kind is found, and a kind is chosen when it reaches
 * [ActionKindProfile.minScore] and is within [ActionKindProfile.margin] of the best, best first, at most [ActionKindProfile.maxActions].
 */
object ActionKindSelector {

    /** A kind with its raw score and the adjusted one the choice was made on. */
    class Scored(val kind: ActionKind, val raw: Double, val adjusted: Double)

    /** @param anyScore the gate's margin over its baseline; @param doneMargin the "completed already?" margin over its baseline (negative infinity: not asked) */
    fun choose(anyScore: Double, scores: List<Pair<ActionKind, Double>>, profile: ActionKindProfile, doneMargin: Double = Double.NEGATIVE_INFINITY): List<Scored> {
        if (anyScore < profile.anyThreshold || doneMargin > profile.doneThreshold) return emptyList()
        val adjusted = scores.map { (kind, raw) -> Scored(kind, raw, profile.adjusted(kind.id, raw)) }.sortedByDescending { it.adjusted }
        val best = adjusted.firstOrNull() ?: return emptyList()
        return adjusted.filter { it.adjusted >= profile.minScore && it.adjusted >= best.adjusted - profile.margin }.take(profile.maxActions)
    }
}
