package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.actions.ActionKindProfile
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
    /**
     * The least the best family must lead the runner-up by to be taken; a closer call is the abstain family (the neutral "Document"),
     * because a model that cannot tell two kinds apart is not telling which one it is. 0.0 (the default) takes any lead.
     */
    val familyMinMargin: Double = 0.0,
    /**
     * The margin the best category must beat the content-free baseline by: the same scoring question about a made-up kind of document
     * ([ScoringDescriptions.CATEGORY_BASELINE]), asked in the same batch. A category that does not beat it is not taken and the document is the
     * neutral "Document". Null (the default) asks for no baseline, so a profile that never measured one decides as before.
     */
    val categoryBaselineMargin: Double? = null,
    /**
     * The party questions (`sender`, `addressee`, `subject_person`, `contact`, ...) and the reference questions (`slot:invoice_no`, ...) whose
     * answer may be "none of these": the best candidate is taken only when its score beats, by more than this margin, the score of a made-up
     * value that is nowhere in the letter (a name, a reference), asked the same way over the same zones (the content-free baseline). A line that
     * is no party (a greeting, a sentence of a message) or no reference then leaves the field empty instead of being the best of a bad lot.
     * By question name; a question not listed is taken as before (the best above its threshold). Asked of every document, whatever its type.
     */
    val baselineMargins: Map<String, Double> = emptyMap(),
    /**
     * The margin a meaning of a date or an amount ([com.postsaimanager.core.domain.extraction.v2.ValueMeanings]) must beat its content-free
     * baseline by, by meaning id; one not listed uses [defaultMeaningMargin]. A value no meaning beats it by is "other" (no meaning).
     */
    val meaningMargins: Map<String, Double> = emptyMap(),
    val defaultMeaningMargin: Double = 0.0,
    /** The cut points of a slot's or a party's confidence; see [ScoreCuts]. */
    val cuts: ScoreCuts = ScoreCuts(),
    /** How the scores of all questions are combined into the answers ([SlotDecoder]); the per-slot argmax by default. */
    val decoder: DecoderSpec = DecoderSpec(),
    /**
     * The address retry: when the letter's address zone holds no postcode line, the runner-up layout template's address region is read
     * instead if the runner-up's match score is at most this far (0..1, the template matcher's scale) below the chosen template's.
     */
    val addressRetryMargin: Float = 0.1f,
    /**
     * Whether questions that share text are scored through the prefix tree (a zone block, or a value, decoded once and each question
     * rolled back to it: `PromptSession.score`'s shared level and `scoreGrid`) instead of each question read whole. Faster (the shared
     * tokens are paid once), but a split decode is not the same arithmetic as a whole one: on the 16 benchmark letters the recorded
     * scores moved by up to 0.8 log-odds (mean about 0.15) and the best candidate changed in 25 of 138 questions, because the 0.8B
     * model's scores sit within +-1 of zero. Off by default: the reading is then exactly what the recordings hold.
     */
    val prefixTree: Boolean = false,
    /**
     * The questions (by name, `slot:contract_no`, ...) whose honest answer is often none: a number the document may simply not have. Unless
     * the document's family has the slot as its own, the model must lean Yes ([optionalThreshold]) for a value to be taken, so none is a
     * real answer, such a question is never widened to every candidate of the letter, and the value is shown with the words printed before it.
     */
    val optionalUnlessOwn: Set<String> = emptySet(),
    /** The abstain level of an [optionalUnlessOwn] question the family does not own: 0.0 is the model's own indifference between Yes and No. */
    val optionalThreshold: Double = 0.0,
    /** How the scores of the action questions become the actions a letter asks of its reader (see [ActionKindProfile]). */
    val actions: ActionKindProfile = ActionKindProfile(),
) {
    fun threshold(ask: String): Double = thresholds[ask] ?: defaultThreshold

    /** Whether [ask] may be answered with none because the document does not have it: optional and not one of the family's own slots. */
    fun isOptional(ask: String, own: Boolean): Boolean = !own && ask in optionalUnlessOwn

    /** The threshold of a slot question: [optionalThreshold] when it [isOptional], else the question's own ([threshold]). */
    fun slotThreshold(ask: String, own: Boolean): Double = thresholds[ask] ?: if (isOptional(ask, own)) optionalThreshold else defaultThreshold

    /**
     * The family the scores decide, as an index into [scores] (one per scored family), or null for the abstain family: the best score
     * must be above the family threshold and lead the runner-up by at least [familyMinMargin]. A lone candidate leads by its own score. When
     * the content-free [baseline] (a made-up kind of document, scored in the same batch) is given and [categoryBaselineMargin] is set, the best
     * must also beat it by that margin: a document that is no better a letter than a made-up kind of document is the neutral "Document".
     */
    fun familyWinner(scores: List<Double>, baseline: Double? = null): Int? {
        val order = scores.indices.sortedByDescending { scores[it] }
        val best = order.firstOrNull()?.takeIf { scores[it] > threshold(FAMILY) } ?: return null
        val lead = if (order.size > 1) scores[best] - scores[order[1]] else scores[best]
        if (lead < familyMinMargin) return null
        val floor = categoryBaselineMargin?.let { m -> baseline?.plus(m) }
        return best.takeIf { floor == null || scores[it] > floor }
    }

    /** The margin over the content-free baseline the party or reference question [ask] needs, or null when it is not asked against one. */
    fun baselineMargin(ask: String): Double? = baselineMargins[ask]

    /** The margin the meaning [id] of a date or an amount needs over its content-free baseline. */
    fun meaningMargin(id: String): Double = meaningMargins[id] ?: defaultMeaningMargin

    /** The type's confidence from its margin. */
    fun confidence(margin: Double): String = when {
        margin >= highMargin -> "HIGH"
        margin >= mediumMargin -> "MEDIUM"
        else -> "LOW"
    }

    /** The confidence word of a slot or party answer: [ScoreCuts.word] of the winner's margin over the runner-up and its own score. */
    fun confidence(margin: Double, best: Double): String = cuts.word(margin, best)

    /** The best family is taken only when its score is above this; otherwise the document is `free_form`. */
    val familyThreshold: Double get() = threshold(FAMILY)

    /** A topic holds when its score is above this. */
    val topicsThreshold: Double get() = threshold(TOPICS)

    companion object {
        /** The question names of the classification, for [thresholds]. The address asks are named in `LineAsk`. */
        const val FAMILY = "family"
        const val TOPICS = "topics"
    }
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

    /**
     * What a slot is asked as. The reference numbers (invoice, contract, policy, case, tax) keep the plain
     * statement from the slot's label: the device recordings hold their questions word for word, so a new wording would need a new
     * recording before the replays mean anything.
     */
    fun ofSlot(slot: SlotKey): String = SLOTS[slot.json] ?: "the ${slot.label.lowercase()}"

    /** The kind statements of a party, in [com.postsaimanager.core.domain.extraction.v2.StructuredGrammar.PARTY_KINDS] order (OTHER is never chosen). */
    val KINDS: List<Pair<String, String>> = listOf(
        "PERSON" to "the name of a private person",
        "AUTHORITY" to "the name of an authority or public body",
        "COMPANY" to "the name of a company or other organisation",
    )

    const val HOUSEHOLD = "the name of a family or household (several people living together)"

    /**
     * The scoring name of the key-slot batch (the stored slot values scored for whether the reader needs them). It keeps the name of the
     * extras batch it replaced: the recordings hold their scores under it, and a profile's `extras` threshold is still read from there.
     */
    const val EXTRAS_ASK = "extras"

    /**
     * The statement the retired scored extras were asked under. No reading asks it any more (the facts beyond the read fields are
     * generated, see `KeyInfoWriter`); it stays so the recordings, which hold those questions, still replay and the tools that read them compile.
     */
    const val EXTRA = "an important fact of this letter that the reader may need again (an identifier, a number to call, a date or an amount " +
        "that matters), other than the letter's main amount, due date, IBAN, reference or customer number"

    /**
     * The statement the extras are scored under for a document whose family has a [hint][com.postsaimanager.core.domain.extraction.v2.DocFamily.hint]:
     * [EXTRA] followed by the guidance on what matters in this kind of document. What scores above the threshold is the "Key information".
     */
    fun extra(hint: String?): String = hint?.trim()?.takeIf { it.isNotEmpty() }?.let { "$EXTRA. $it" } ?: EXTRA

    /**
     * The scoring name of the stored slot values asked in the same batch as the extras (an invoice number, an amount, an IBAN ...):
     * its threshold is the profile's (`defaultThreshold` unless the profile sets this name). What scores above it is key information too.
     */
    const val KEY_SLOTS_ASK = "keyslots"

    /**
     * The made-up name scored beside a party's candidates as the content-free baseline (see [ScoringProfile.baselineMargins]); it must
     * not be a name that could be printed in a letter.
     */
    const val PARTY_BASELINE_NAME = "Zoltan Quillfeather"

    /** The made-up reference scored beside a reference question's candidates, the same baseline for numbers; not a reference a letter could print. */
    const val REFERENCE_BASELINE_VALUE = "ZQ-0000-QUILLFEATHER"

    /** The made-up date scored beside the meanings of a date ([com.postsaimanager.core.domain.extraction.v2.ValueMeanings]); not a date a letter could print. */
    const val DATE_BASELINE_VALUE = "the 41st of Zoltember"

    /** The made-up amount scored beside the meanings of an amount; not an amount a letter could print. */
    const val AMOUNT_BASELINE_VALUE = "77 Quillfeather coins"

    /**
     * The made-up kind of document scored beside the categories as their content-free baseline (see [ScoringProfile.categoryBaselineMargin]); it
     * names no kind of document that exists. It is asked as "Is this document <this>?" in the same batch as the categories.
     */
    const val CATEGORY_BASELINE = "a quillfeather zoltember, a kind of document that exists nowhere"

    /** Every made-up value a content-free baseline is asked about: a question that holds one is a baseline, never a question about the letter. */
    val BASELINE_PROBES = listOf(PARTY_BASELINE_NAME, REFERENCE_BASELINE_VALUE, DATE_BASELINE_VALUE, AMOUNT_BASELINE_VALUE)

    /** Whether [question] is a content-free baseline (it asks about one of the [BASELINE_PROBES]). */
    fun isBaselineQuestion(question: String): Boolean = BASELINE_PROBES.any { question.contains(it) }

    /** At most this many stored slot values are scored for key information in one reading: the batch stays small. */
    const val MAX_KEY_SLOT_SCORES = 15

    /** At most this many read (stored) slot values are marked as key information, best score first. */
    const val MAX_KEY_INFO = 4

    /**
     * The "Key information" section shows at most this many rows: the marked slot values first, then the generated facts
     * (`KeyInfoFormat.MAX_FACTS`), so it stays short whatever is stored. The rest stay under "All details".
     */
    const val MAX_KEY_INFO_SHOWN = 8
}
