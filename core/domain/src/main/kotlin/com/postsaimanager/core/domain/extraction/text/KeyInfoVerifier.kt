package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier

/**
 * Decides which of the facts the model listed as "key information" may be kept. Code only verifies: it never picks, words or ranks a fact,
 * and the labels are kept as the model wrote them (they are shown, never interpreted).
 *
 * A fact is dropped when
 * - its label or value is empty, or the label is not a label by shape ([KeyInfoFormat.isLabelShape]: 1..4 words, at most 30 characters, no
 *   digit, no sentence punctuation), so a sentence fragment never becomes one;
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
    fun verify(facts: List<KeyInfoFormat.Fact>, ocrText: String, readValues: Collection<String>): List<Kept> =
        report(facts, ocrText, readValues).kept

    /** Why a fact was dropped; logged so a reading with no key facts can be told from a model that listed none. */
    enum class DropReason { EMPTY, LABEL_SHAPE, NOT_IN_LETTER, SAME_AS_READ_VALUE, SAME_AS_EARLIER_FACT, LIST_FULL }

    /** A fact that was dropped, by its label (never the value: it is a line of the letter) and why. */
    data class Dropped(val label: String, val reason: DropReason)

    /** What [report] decided: the facts kept, and each dropped one with its reason. */
    data class Report(val kept: List<Kept>, val dropped: List<Dropped>)

    /** [verify] with the reason of every drop. */
    fun report(facts: List<KeyInfoFormat.Fact>, ocrText: String, readValues: Collection<String>): Report {
        val read = readValues.mapTo(mutableSetOf()) { key(it) }.apply { remove("") }
        val earlier = mutableSetOf<String>()
        val letterDigits = digitRuns(ocrText)
        val kept = mutableListOf<Kept>()
        val dropped = mutableListOf<Dropped>()
        for (fact in facts) {
            val label = fact.label.trim()
            val value = fact.value.trim()
            val reason = when {
                kept.size >= KeyInfoFormat.MAX_FACTS -> DropReason.LIST_FULL
                label.isEmpty() || value.isEmpty() -> DropReason.EMPTY
                !KeyInfoFormat.isLabelShape(label) -> DropReason.LABEL_SHAPE
                !grounded(value, ocrText, letterDigits) -> DropReason.NOT_IN_LETTER
                key(value) in read -> DropReason.SAME_AS_READ_VALUE
                !earlier.add(key(value)) -> DropReason.SAME_AS_EARLIER_FACT
                else -> null
            }
            if (reason == null) kept += Kept(label, value) else dropped += Dropped(label, reason)
        }
        return Report(kept, dropped)
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
