package com.postsaimanager.core.domain.form

import java.util.Collections
import java.util.IdentityHashMap

/**
 * The AI's yes/no on the candidates the geometry is unsure of: "Is «label …» something the reader must fill in?", scored
 * label-free through the form's [FormScorer]. A structural candidate (a fill run, a box) is a field without a score; a weak one
 * (a label with space after it, an empty table cell) stays only when its score is above [FormScoringProfile.confirmThreshold].
 * At most [FormScoringProfile.maxConfirmScores] weak candidates are scored (in order); the rest are dropped.
 */
class ConfirmFields(
    private val scorer: FormScorer,
    private val profile: FormScoringProfile = FormScoringProfile(),
) {

    /** The candidates that are fields, in their input order. Throws [FormScoringException] when the engine fails. */
    suspend fun confirm(candidates: List<FieldCandidate>): List<FieldCandidate> {
        val weak = candidates.filter { !it.strong }.take(profile.maxConfirmScores)
        val scores = scorer.yesNo(weak.map(::question))
        val keep: MutableSet<FieldCandidate> = Collections.newSetFromMap(IdentityHashMap())
        weak.forEachIndexed { i, c -> if (scores[i] > profile.confirmThreshold) keep += c }
        return candidates.filter { it.strong || it in keep }
    }

    private fun question(c: FieldCandidate) = "Is «${c.labelText} …» something the reader must fill in? Answer:"
}
