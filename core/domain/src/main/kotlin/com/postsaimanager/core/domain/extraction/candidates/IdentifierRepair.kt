package com.postsaimanager.core.domain.extraction.candidates

/**
 * OCR character-confusion repair for identifiers (reference and meter numbers): inside a long,
 * digit-heavy token, `O`/`o` next to a digit is a 0 and `I`/`l` between two digits is a 1. Only the
 * normalised value changes; the raw text stays as the evidence. Tokens with fewer than
 * [MIN_DIGITS] digits are never touched, so ordinary letter-and-number codes stay as printed.
 */
object IdentifierRepair {
    private const val MIN_LENGTH = 9
    private const val MIN_DIGITS = 8

    /** The repaired token, or null when nothing would change. */
    fun repair(token: String): String? {
        if (token.length < MIN_LENGTH || token.count { it.isDigit() } < MIN_DIGITS) return null
        val sb = StringBuilder(token)
        // Repeated until nothing changes: a run of confused characters ("OO07777") is repaired from the digit it touches outwards, so
        // the neighbour of a repaired character counts as a digit too. Real letters ("BG") touch no digit-run and stay.
        var changed = true
        while (changed) {
            changed = false
            for (i in token.indices) {
                val prevDigit = i > 0 && sb[i - 1].isDigit()
                val nextDigit = i + 1 < sb.length && sb[i + 1].isDigit()
                val fixed = when (sb[i]) {
                    'o', 'O' -> if (prevDigit || nextDigit) '0' else null
                    'I', 'l' -> if (prevDigit && nextDigit) '1' else null
                    else -> null
                }
                if (fixed != null) {
                    sb.setCharAt(i, fixed)
                    changed = true
                }
            }
        }
        val out = sb.toString()
        return out.takeIf { it != token }
    }
}
