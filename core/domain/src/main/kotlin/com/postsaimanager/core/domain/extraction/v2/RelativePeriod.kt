package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.OcrText

/**
 * A period given in words ("within 14 days of receipt", "innerhalb eines Monats", "خلال 30 يوما"),
 * read from the rule the model quoted.
 *
 * The model decides that a rule is a deadline and writes it as a quote; code only verifies the quote
 * against the letter ([QuoteVerifier]) and, when the quote holds a number, reads it. The number is
 * the first run of digits (western or Arabic-Indic, normalised like all OCR text), so it does not
 * depend on the language. The unit is read from a short list of words per language that is only a
 * **hint**: with no known unit the rule is kept as the quote and nothing else is claimed. A period
 * written without digits ("one month") is likewise kept as the quote.
 */
object RelativePeriod {

    /** @property unit `D`, `W`, `M` or `Y` (ISO 8601 duration designators). */
    data class Parsed(val n: Int, val unit: Char) {
        /** ISO 8601 duration, "P14D". */
        val iso: String get() = "P$n$unit"

        /** Whether the number is a period a letter could plausibly set (one day to two years). */
        val plausible: Boolean get() = n in 1..MAX_DAYS
    }

    private const val MAX_DAYS = 730

    private val NUMBER = Regex("(?<![\\d.,])(\\d{1,4})(?![\\d])")

    /** Unit hints, matched as the start of a word. Not exhaustive; extending it never changes what is valid. */
    private val UNIT_HINTS: List<Pair<Char, List<String>>> = listOf(
        'D' to listOf("tag", "werktag", "arbeitstag", "day", "working day", "business day", "jour", "día", "gun", "gün", "يوم", "أيام", "ايام"),
        'W' to listOf("woche", "week", "semaine", "semana", "hafta", "أسبوع", "اسبوع", "أسابيع"),
        'M' to listOf("monat", "month", "mois", "mes", "ay", "شهر", "أشهر"),
        'Y' to listOf("jahr", "year", "année", "año", "yil", "yıl", "سنة", "عام", "سنوات"),
    )

    /** The number and unit of [quote], or null when it has no digits or no known unit word. */
    fun parse(quote: String): Parsed? {
        val text = OcrText.normalizeChars(quote).lowercase()
        val m = NUMBER.find(text) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val rest = text.substring(m.range.last + 1)
        val words = rest.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        for (word in words.take(4)) {
            for ((unit, hints) in UNIT_HINTS) {
                if (hints.any { hint -> word == hint || (hint.length >= 3 && word.startsWith(hint)) }) return Parsed(n, unit)
            }
        }
        return null
    }
}
