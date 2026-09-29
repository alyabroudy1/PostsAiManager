package com.postsaimanager.core.domain.extraction.v2

/**
 * How the shown confidence is made from what the model said and what the code checked.
 *
 * The rule is one line: **final = min(the model's confidence, every failed check's cap)**. A check
 * that passes changes nothing, so code can never make the model look more sure than it said it was;
 * a check that fails caps the value low, and a *blocking* one also flags it ([SlotValue.blocked]).
 * A value is shown as "worth checking" when it is blocked or its final confidence is below
 * [REVIEW_BELOW].
 *
 * The model's own word is kept next to the final number ([SlotValue.aiConfidence]) and so are the
 * reasons for each cap ([SlotValue.notes]). That is on purpose: the benchmark can compare "the model
 * said HIGH" with "it was right" over many letters and recalibrate the three numbers below without
 * touching the checks. A sampler log-probability may later join them ([SlotValue.idLogProb]).
 *
 * Pure and free of any document or language knowledge, so it is unit-tested on its own; the checks
 * themselves are made in [SelectionVerifier].
 */
object ConfidenceCombiner {

    const val LOW = 0.4f
    const val MEDIUM = 0.7f
    const val HIGH = 0.9f

    /** Used when the model wrote no usable word. */
    const val UNKNOWN = 0.5f

    /** Below this a value is shown as worth checking (the app's auto-link threshold). */
    const val REVIEW_BELOW = 0.75f

    /** Maps the grammar's word to a number. */
    fun aiScore(word: String?): Float = when (word?.trim()?.uppercase()) {
        "HIGH" -> HIGH
        "MEDIUM" -> MEDIUM
        "LOW" -> LOW
        else -> UNKNOWN
    }

    /** The result of one code check on one value. */
    sealed interface Check {
        /** The check passed (or did not apply). It never raises the confidence. */
        data object Pass : Check

        /**
         * The check failed: the confidence may not exceed [limit]. A [blocking] cap also flags the
         * value for the user; a non-blocking one only lowers it (a quote is never fully trusted, but
         * an exact quote is not a problem to look at).
         */
        data class Cap(val limit: Float, val reason: String, val blocking: Boolean = true) : Check
    }

    /** Caps used by [SelectionVerifier]; named so the tests and the benchmark can refer to them. */
    object Caps {
        const val INVALID = 0.3f
        const val CONFLICT = 0.3f
        const val ROLE_MISMATCH = 0.4f
        const val INCONSISTENT = 0.4f
        const val ZONE_MISMATCH = 0.4f
        const val QUOTE_FUZZY = 0.45f
        const val DATE_ORDER = 0.5f
        const val GENERATED = 0.5f
        const val QUOTE_NORMALIZED = 0.55f
        const val QUOTE_EXACT = 0.6f
        const val UNCHECKED = 0.6f

        /** A value read with OCR character-confusion repair (o/O for 0, I/l for 1): plausible, not printed as such. */
        const val REPAIRED = 0.6f
        const val GUESS = 0.65f
    }

    data class Combined(
        val ai: Float,
        val final: Float,
        val blocked: Boolean,
        val notes: List<String>,
    )

    fun combine(ai: Float, checks: List<Check>): Combined {
        val caps = checks.filterIsInstance<Check.Cap>()
        val final = (caps.minOfOrNull { it.limit }?.let { minOf(ai, it) } ?: ai).coerceIn(0f, 1f)
        return Combined(
            ai = ai,
            final = final,
            blocked = caps.any { it.blocking },
            notes = caps.map { it.reason },
        )
    }
}
