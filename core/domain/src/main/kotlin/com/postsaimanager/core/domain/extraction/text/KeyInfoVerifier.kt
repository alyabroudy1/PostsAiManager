package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier

/**
 * Decides which of the facts the model listed as "key information" may be kept. Code only verifies: it never picks, words or ranks a fact,
 * and the labels are kept as the model wrote them (they are shown, never interpreted).
 *
 * A fact is dropped when
 * - its label or value is empty;
 * - its value is not in the letter: [QuoteVerifier] (the same check every quoted value passes, folded for case, spacing, punctuation,
 *   accents and Arabic spelling variants), a one-word value only exactly, and every digit run of the value must occur in the letter, so
 *   an amount, date or number the model changed by a digit is not "roughly there";
 * - its value is one the reading already holds (compared the same folded way, and with the separators inside a number ignored, so
 *   `1.284,50` and `1284.50` are one amount), or one an earlier fact already states;
 * - the list is already full ([KeyInfoFormat.MAX_FACTS]).
 *
 * Pure. Nothing here knows a language, a document type or a kind of fact.
 */
class KeyInfoVerifier {

    /** A fact that passed: the label as written and the value as the model wrote it (its text is in the letter). */
    data class Kept(val label: String, val value: String)

    /**
     * @param ocrText the letter's text, the grounding reference
     * @param readValues the values the reading already holds (the read fields), whose duplicates are dropped
     */
    fun verify(facts: List<KeyInfoFormat.Fact>, ocrText: String, readValues: Collection<String>): List<Kept> {
        val taken = readValues.mapTo(mutableSetOf()) { key(it) }.apply { remove("") }
        val letterDigits = digitRuns(ocrText)
        val kept = mutableListOf<Kept>()
        for (fact in facts) {
            if (kept.size >= KeyInfoFormat.MAX_FACTS) break
            val label = fact.label.trim()
            val value = fact.value.trim()
            if (label.isEmpty() || value.isEmpty()) continue
            if (!grounded(value, ocrText, letterDigits)) continue
            if (!taken.add(key(value))) continue
            kept += Kept(label, value)
        }
        return kept
    }

    private fun grounded(value: String, ocrText: String, letterDigits: Set<String>): Boolean {
        QuoteVerifier.verifyCopiedLine(value, ocrText) ?: return false
        return digitRuns(value).all { it in letterDigits }
    }

    /** The folded value as letters and digits only, with Arabic-Indic digits equal to Western ones (see [QuoteVerifier.fold]). */
    private fun key(value: String): String = NOT_ALNUM.replace(QuoteVerifier.fold(value), "")

    private fun digitRuns(text: String): Set<String> = DIGITS.findAll(QuoteVerifier.fold(text)).mapTo(mutableSetOf()) { it.value }

    private companion object {
        val NOT_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")
        val DIGITS = Regex("\\p{Nd}+")
    }
}
