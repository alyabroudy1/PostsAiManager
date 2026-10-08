package com.postsaimanager.core.domain.extraction.candidates

/**
 * Identifier-shaped tokens: digits mixed with letters or separators, or a long run of digits.
 *
 * Such a token is a candidate whatever the words next to it say. The text to its left is kept as a
 * hint only, so a label in any language, or none, makes no difference to whether the value is offered;
 * the labelled rules of [ReferenceFinder] only add a subtype.
 */
internal object IdentifierFinder : CandidateFinder {

    /** A run of letters and digits, 4 to 30 characters, that may contain `- / _ .` inside. */
    private val IDENTIFIER = Regex(
        "(?<![\\p{L}\\p{Nd}])[\\p{L}\\p{Nd}][\\p{L}\\p{Nd}\\-/_.]{2,28}[\\p{L}\\p{Nd}](?![\\p{L}\\p{Nd}])",
    )
    private val WHITE = Regex("\\s+")
    private val DECIMAL = Regex("^\\d+[.,]\\d+$")
    private val CLOCK = Regex("^\\d{1,2}[:.]\\d{2}$")
    private val NUMERIC_DATE = Regex("^\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}$")
    private val YEAR_MONTH = Regex("^\\d{4}-\\d{2}(-\\d{2})?$")
    private val DIGIT_GROUPS = Regex("^\\d+([./-]\\d+){3,}$")
    private val YEAR_PAIR = Regex("^(\\d{4})[/-](\\d{4})$")

    private const val FIRST_YEAR = 1900
    private const val LAST_YEAR = 2100
    private const val MAX_PERIOD_YEARS = 10

    /**
     * "dddd/dddd" (or "dddd-dddd") whose two parts are both plausible years, the second after the first and at most [MAX_PERIOD_YEARS] later,
     * is a period ("2025/2026", a billing or school year), not an identifier. A shape rule: no word near it is read.
     */
    internal fun isYearRange(token: String): Boolean {
        val m = YEAR_PAIR.matchEntire(token) ?: return false
        val from = m.groupValues[1].toInt()
        val to = m.groupValues[2].toInt()
        return from in FIRST_YEAR..LAST_YEAR && to in FIRST_YEAR..LAST_YEAR && to > from && to - from <= MAX_PERIOD_YEARS
    }

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        for (line in ctx.activeLines) findIn(ctx, line, out)
        return out
    }

    private fun findIn(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>) {
        val text = line.text
        val mask = ctx.mask(line)
        for (m in IDENTIFIER.findAll(text)) {
            if (!mask.free(m.range)) continue
            val token = m.value
            if (!looksLikeIdentifier(token)) continue
            val before = text.substring(0, m.range.first).trim().trimEnd(':', '.', ' ')
            val hint = before.split(WHITE).filter { it.isNotEmpty() }.takeLast(3).joinToString(" ").ifEmpty {
                if (m.range.first == 0) ctx.rowNeighbour(line, left = true)?.takeLast(40)?.trim()?.trimEnd(':', '.', ' ').orEmpty() else ""
            }
            mask.add(m.range)
            val repaired = IdentifierRepair.repair(token)
            out += ctx.draft(
                line, m.range, CandidateKind.REFERENCE, token, repaired ?: token, label = hint,
                subtype = ReferenceSubtype.OTHER,
                attrs = if (repaired != null) mapOf("shape" to "true", "repaired" to "o/O->0, I/l->1") else mapOf("shape" to "true"),
            )
        }
    }

    private fun looksLikeIdentifier(token: String): Boolean {
        val digits = token.count { it.isDigit() }
        if (digits < 3 || token.length !in 4..30) return false
        val pureDigits = digits == token.length
        val hasLetter = token.any { it.isLetter() }
        val hasSeparator = token.any { it in "-/_." }
        // a run of digits alone must be long: a postcode, a year or a house number is not an identifier
        if (pureDigits && token.length < 6) return false
        if (!pureDigits && !hasLetter && !hasSeparator) return false
        // decimals, times, dates and year-month pairs are values of their own
        if (DECIMAL.matches(token) || CLOCK.matches(token)) return false
        if (NUMERIC_DATE.matches(token) || YEAR_MONTH.matches(token) || isYearRange(token)) return false
        // a token of only separators between digit groups, such as a phone number, is not one identifier
        if (!hasLetter && DIGIT_GROUPS.matches(token)) return false
        return true
    }
}
