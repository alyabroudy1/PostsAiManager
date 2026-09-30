package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.v2.SlotKey

/**
 * How a log-odds score becomes a decision, as data, per model (see [ModelProfile]): the abstain threshold per
 * question (the best candidate is taken only when its score is above it, otherwise the answer is NONE), how far
 * above it the type's margin counts as MEDIUM or HIGH, and the cut points that turn a scored answer into the
 * confidence word the verifier reads (see [confidence]). All of it is tuned on benchmark recordings.
 */
data class ScoringProfile(
    val defaultThreshold: Double = 0.0,
    /** By question name (`sender`, `addressee`, `slot:total`, ...). */
    val thresholds: Map<String, Double> = emptyMap(),
    /** The document type's margin over the next type: MEDIUM from here, HIGH from [highMargin]. */
    val mediumMargin: Double = 1.0,
    val highMargin: Double = 3.0,
    /** The cut points of a slot's or a party's confidence; see [ScoreCuts]. */
    val cuts: ScoreCuts = ScoreCuts(),
    /** How the scores of all questions are combined into the answers ([SlotDecoder]); the per-slot argmax by default. */
    val decoder: DecoderSpec = DecoderSpec(),
    /**
     * The address retry: when the letter's address zone holds no postcode line, the runner-up layout template's address region is read
     * instead if the runner-up's match score is at most this far (0..1, the template matcher's scale) below the chosen template's.
     */
    val addressRetryMargin: Float = 0.1f,
) {
    fun threshold(ask: String): Double = thresholds[ask] ?: defaultThreshold

    /** The type's confidence from its margin. */
    fun confidence(margin: Double): String = when {
        margin >= highMargin -> "HIGH"
        margin >= mediumMargin -> "MEDIUM"
        else -> "LOW"
    }

    /** The confidence word of a slot or party answer: [ScoreCuts.word] of the winner's margin over the runner-up and its own score. */
    fun confidence(margin: Double, best: Double): String = cuts.word(margin, best)
}

/**
 * Where a scored answer is LOW, MEDIUM or HIGH, from two numbers the model's own scores give: the [margin] (the winner's
 * log-odds of Yes minus the runner-up's; with a single candidate, minus 0.0, the model's indifference between Yes and No)
 * and the winner's absolute score [best]. HIGH needs both [highMargin] and [highBest]; MEDIUM both [mediumMargin] and
 * [mediumBest]; everything else is LOW. Fitted on recordings with cross-fitting (see the calibration benchmark); the
 * defaults are a first guess that calls everything with a clear margin MEDIUM and nothing HIGH.
 */
data class ScoreCuts(
    val mediumMargin: Double = 0.1,
    val mediumBest: Double = Double.NEGATIVE_INFINITY,
    val highMargin: Double = Double.POSITIVE_INFINITY,
    val highBest: Double = Double.NEGATIVE_INFINITY,
) {
    fun word(margin: Double, best: Double): String = when {
        margin >= highMargin && best >= highBest -> "HIGH"
        margin >= mediumMargin && best >= mediumBest -> "MEDIUM"
        else -> "LOW"
    }
}

/**
 * What each question is called in a scoring question ("Is «X» <what>?"). Data: a party role or a slot has
 * a statement, written by what it does in a letter, in English. A slot with no statement of its own is
 * described by its label.
 */
object ScoringDescriptions {

    private val ROLES = mapOf(
        QuestionNames.SENDER to "the sender: the party that wrote and sent this letter",
        QuestionNames.ADDRESSEE to "the addressee: the party this letter is addressed to",
        QuestionNames.CONTACT to "a contact person named as the one who handles the matter",
        QuestionNames.CARE_OF to "a party in whose care the letter is sent (a mailbox for the addressee)",
        QuestionNames.SUBJECT_PERSON to "a person the letter is about, other than the addressee",
    )

    private val SLOTS = mapOf(
        "letter_date" to "the date of the letter itself (when it was written or issued)",
        "total" to "the main amount of this document: what the reader has to pay, or the total",
        "due_date" to "the date by which the reader must pay or act",
        "iban" to "the IBAN of the account the reader should pay to",
        "reference" to "a reference the letter cites for this matter (a file, case, transaction or payment reference)",
        "customer_no" to "the number that identifies the reader as a customer, member or account holder",
    )

    fun ofRole(name: String): String = ROLES[name] ?: "a party of this letter"

    fun ofSlot(slot: SlotKey): String = SLOTS[slot.json] ?: "the ${slot.label.lowercase()}"

    /** The kind statements of a party, in [com.postsaimanager.core.domain.extraction.v2.StructuredGrammar.PARTY_KINDS] order (OTHER is never chosen). */
    val KINDS: List<Pair<String, String>> = listOf(
        "PERSON" to "the name of a private person",
        "AUTHORITY" to "the name of an authority or public body",
        "COMPANY" to "the name of a company or other organisation",
    )

    const val HOUSEHOLD = "the name of a family or household (several people living together)"

    /** The scoring name of the extras batch: the threshold and the statement ([EXTRA]) of a value no slot or party took. */
    const val EXTRAS_ASK = "extras"

    const val EXTRA = "an important fact of this letter that the reader may need again (an identifier, a number to call, a date or an amount " +
        "that matters), other than the letter's main amount, due date, IBAN, reference or customer number"
}
