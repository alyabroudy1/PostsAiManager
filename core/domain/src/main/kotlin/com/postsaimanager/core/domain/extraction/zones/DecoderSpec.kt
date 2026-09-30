package com.postsaimanager.core.domain.extraction.zones

/** Which [SlotDecoder] reads the scores. */
enum class DecoderKind {
    /** Every question takes its own best candidate ([PerSlotArgmax]). */
    ARGMAX,

    /** The best assignment overall: one candidate per question, a value answers one question (see [SharingRules]), none is an option ([JointAssignment]). */
    JOINT,

    /** [JOINT] plus the soft consistency constraints of [PairConstraints], weighted by the spec ([JointAssignment]). */
    JOINT_CONSTRAINED,
}

/** How the scores of a question are brought to one scale before they are added across questions. */
enum class ScoreCalibration {
    /** The log-odds as the model gave them. */
    RAW,

    /** Per question: minus the mean, over the standard deviation, of the question's candidate scores. */
    ZSCORE,

    /** Per question: the candidate's rank among the question's candidates, 0 (worst) to 1 (best). */
    RANK,

    /** Per question: log of the softmax of the scores over the candidates and none, at [DecoderSpec.temperature]. */
    SOFTMAX,

    /** Per candidate: minus its score with the letter's content removed ([ScoredQuestion.prior]); needs a device run, none where absent. */
    CONTENT_FREE,
}

/**
 * The decoder of a [ScoringProfile], as data (tuned on recordings, cross-fitted, like the thresholds).
 *
 * @property temperature the softmax temperature ([ScoreCalibration.SOFTMAX] only).
 * @property abstain the level of "none" in calibrated units, for every question; null keeps each question's own threshold,
 *   carried through the calibration.
 * @property dateOrderPenalty what a date that comes before the date it must not precede ([PairConstraints.DATE_ORDER]) costs.
 * @property tripleBonus what a total that completes a net + VAT = gross triple earns, and a net or VAT part in its place
 *   costs ([PairConstraints.TOTAL_SLOTS]).
 * @property sharing which questions may take the same candidate.
 */
data class DecoderSpec(
    val kind: DecoderKind = DecoderKind.ARGMAX,
    val calibration: ScoreCalibration = ScoreCalibration.RAW,
    val temperature: Double = 1.0,
    val abstain: Double? = null,
    val dateOrderPenalty: Double = 0.0,
    val tripleBonus: Double = 0.0,
    val sharing: SharingRules = SharingRules.DEFAULT,
) {
    fun create(): SlotDecoder = when (kind) {
        DecoderKind.ARGMAX -> PerSlotArgmax
        DecoderKind.JOINT -> JointAssignment(this.copy(dateOrderPenalty = 0.0, tripleBonus = 0.0))
        DecoderKind.JOINT_CONSTRAINED -> JointAssignment(this)
    }
}

/**
 * Which questions may take the same candidate. By default one value answers one question (a person is not at once the sender
 * and the addressee; a number is the reference or the customer number, not both); the exceptions are the pairs where the
 * same value legitimately is both, as data: a letter's date is also its sent date, the total is also the new amount, a
 * specific reference is also the generic one, the organisation a letter was sent to is also an addressee.
 */
data class SharingRules(private val pairs: Set<Set<String>>, private val exempt: Set<String> = emptySet()) {

    fun mayShare(a: String, b: String): Boolean = a in exempt || b in exempt || setOf(a, b) in pairs

    companion object {
        private fun slot(json: String) = QuestionNames.slot(json)

        private fun all(vararg names: String): Set<Set<String>> =
            names.flatMapIndexed { i, a -> names.drop(i + 1).map { b -> setOf(a, b) } }.toSet()

        private fun hub(center: String, vararg others: String): Set<Set<String>> = others.map { setOf(center, it) }.toSet()

        /** Questions in these groups may share one value: the slots that are the same fact under another name in another type. */
        val DEFAULT = SharingRules(
            pairs = all(slot("letter_date"), slot("sent_date"), slot("proof_date")) +
                all(slot("total"), slot("new_amount"), slot("proof_amount")) +
                hub(
                    slot("reference"), slot("invoice_no"), slot("contract_no"), slot("policy_no"), slot("case_no"),
                    slot("tax_no"), slot("receipt_no"), slot("proof_reference"),
                ),
            // A named organisation or person a slot asks for is asked again as a party.
            exempt = setOf(slot("recipient_org"), slot("proof_recipient")),
        )

        /** No exception: one value, one question. */
        val NONE = SharingRules(emptySet())
    }
}

/** The soft consistency constraints, as data: which questions they bind. */
object PairConstraints {

    /** `(earlier, later)`: the later question's date should not come before the earlier one's. */
    val DATE_ORDER: List<Pair<String, String>> = listOf(
        QuestionNames.slot("letter_date") to QuestionNames.slot("due_date"),
        QuestionNames.slot("letter_date") to QuestionNames.slot("objection_deadline"),
        QuestionNames.slot("original_due_date") to QuestionNames.slot("due_date"),
    )

    /** The questions whose answer is the amount to pay: a net or VAT part of a triple is not one, the gross is. */
    val TOTAL_SLOTS: Set<String> = setOf(QuestionNames.slot("total"), QuestionNames.slot("new_amount"), QuestionNames.slot("proof_amount"))
}
