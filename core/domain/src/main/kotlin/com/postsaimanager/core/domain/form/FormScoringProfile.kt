package com.postsaimanager.core.domain.form

import kotlin.math.exp

/**
 * How the form-understanding log-odds scores become decisions, as data (the same idea as the extraction's
 * `ScoringProfile`): thresholds per question kind, how many candidates a step looks at, and the budget of scores each step
 * may spend. Defaults are 0.0 (the model's own Yes/No boundary); tune them on recordings.
 *
 * Budget per 2-page, 20-field form (typical): confirm about 6 (weak candidates only), classify 80 (3 keys + "none" per
 * field), roles about 33 (6 per section and 6 per bank/signature field), subject about 10: roughly 130. The `max*` limits cap
 * each step; what is over the cap is left undecided (an unconfirmed weak candidate is dropped, an unclassified field has no key).
 */
data class FormScoringProfile(
    /** A weak candidate is a field when its score is above this. */
    val confirmThreshold: Double = 0.0,
    /** The best data key is taken only when its score is above this and above "none". */
    val keyThreshold: Double = 0.0,
    /** The best key must beat the runner-up (another key or "none") by at least this. */
    val keyMinMargin: Double = 0.0,
    /** How many keys the embedding ranker passes to the model. */
    val keyCandidates: Int = 3,
    /** A role is taken only when its score is above this. */
    val roleThreshold: Double = 0.0,
    /** A field's own role replaces its section's when its score beats the section role's by this much. */
    val roleOverrideMargin: Double = 1.0,
    /** A profile is a plausible subject when its score is above this. */
    val subjectThreshold: Double = 0.0,
    val maxConfirmScores: Int = 40,
    val maxClassifyScores: Int = 96,
    /** Role scores for sections (one per role per section) and for role-bearing fields (one per role per field). */
    val maxSectionScores: Int = 48,
    val maxFieldRoleScores: Int = 24,
    /** At most this many managed profiles are scored as the form's subject. */
    val maxSubjects: Int = 6,
    /** At most this many intro lines are scored as the quoted reason for the suggested subject. */
    val reasonLines: Int = 6,
) {
    /** A confidence in 0.5..1 from a margin in log-odds (0 = a tie). */
    fun confidence(margin: Double): Float = (1.0 / (1.0 + exp(-margin))).toFloat()
}
