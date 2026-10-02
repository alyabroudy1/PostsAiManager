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
        for (i in token.indices) {
            val prevDigit = i > 0 && token[i - 1].isDigit()
            val nextDigit = i + 1 < token.length && token[i + 1].isDigit()
            when (token[i]) {
                'o', 'O' -> if (prevDigit || nextDigit) sb.setCharAt(i, '0')
                'I', 'l' -> if (prevDigit && nextDigit) sb.setCharAt(i, '1')
            }
        }
        val out = sb.toString()
        return out.takeIf { it != token }
    }
}
