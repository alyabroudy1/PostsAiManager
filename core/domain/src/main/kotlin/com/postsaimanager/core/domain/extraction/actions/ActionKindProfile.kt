package com.postsaimanager.core.domain.extraction.actions

/**
 * How the scores of the action questions become actions, as data per model (see `ScoringProfile.actions`). A 0.8B model leans Yes on
 * every question about what a letter asks, so the raw sign of a score decides nothing: a kind is chosen by how far it stands above
 * what the model says of the same kind on every letter ([kindBias], fitted on the benchmark recordings) and above the best kind's
 * adjusted score by no more than [margin]. All numbers are log-odds of Yes against No.
 *
 * @property anyThreshold the letter asks nothing at all when the gate ("does it ask the reader to do anything?", asked knowing what the
 *   reading decided the document is) scores less than this above its baseline
 * @property gateBaseline what the model says to the gate question over an empty letter, by family id (key "" for no family): the content-free
 *   baseline the gate's score is measured from, fitted on the recordings; a family without an entry is measured from the "" entry, else 0.0
 * @property doneBaseline the same for the "has it been completed already?" question
 * @property doneThreshold the letter asks nothing when "completed already?" scores more than this above its baseline; infinity: not used
 * @property kindBias subtracted from a kind's score before kinds are compared: the model's habitual lean for that kind; absent is 0.0
 * @property minScore a kind's adjusted score must reach this to be chosen at all
 * @property margin a kind is chosen with the best one when its adjusted score is within this of the best's
 * @property maxActions at most this many kinds are shown
 * @property dateThreshold a stored date is bound to a chosen kind when it scores above this as that kind's date (the best one wins)
 * @property amountThreshold the same for the amount
 * @property scoreEveryBinding score every stored date and amount under every kind, not only under the chosen ones: what a recording
 *   run does, so thresholds can be fitted offline. Never on in the app.
 */
data class ActionKindProfile(
    val anyThreshold: Double = 0.0,
    val gateBaseline: Map<String, Double> = emptyMap(),
    val doneBaseline: Double = 0.0,
    val doneThreshold: Double = Double.POSITIVE_INFINITY,
    val kindBias: Map<String, Double> = emptyMap(),
    val minScore: Double = 0.0,
    val margin: Double = 1.0,
    val maxActions: Int = MAX_ACTIONS,
    val dateThreshold: Double = 0.0,
    val amountThreshold: Double = 0.0,
    val scoreEveryBinding: Boolean = false,
) {
    /** The gate's score as a margin over the content-free baseline of [familyId]. */
    fun gateMargin(raw: Double, familyId: String?): Double = raw - (gateBaseline[familyId.orEmpty()] ?: gateBaseline[""] ?: 0.0)

    fun adjusted(kindId: String, score: Double): Double = score - (kindBias[kindId] ?: 0.0)

    companion object {
        /** A letter's "What you need to do" holds at most this many actions. */
        const val MAX_ACTIONS = 2
    }
}
