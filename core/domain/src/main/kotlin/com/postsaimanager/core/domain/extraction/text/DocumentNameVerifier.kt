package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier

/**
 * Decides whether the specific name the model wrote for a document may be kept. Code only verifies: it never words or edits the name.
 *
 * The name is generated (in the document's language: it describes the document, it is not a copy of a line), so it is checked loosely, and
 * only for what it must not do, which is claim a fact the document does not give. It is dropped when
 * - it is empty, holds a line break, or is longer than [DocumentNameFormat.MAX_CHARS];
 * - a number in it (any run of digits: a year, an amount, a policy number) does not occur in the letter, so a changed or invented figure is
 *   never "roughly there" ([QuoteVerifier.fold]: Arabic-Indic and Western digits are the same);
 * - a word in it that starts with a capital letter, other than the first word (the first word is where a name puts the kind of document, in
 *   any language), does not occur in the letter, folded for case, accents and spelling variants. A word counts as occurring when the letter
 *   holds its first half (at least [MIN_PREFIX] letters), so an inflected or compounded form of a printed word passes ("Beitragsrechnung"
 *   for a letter that prints "Beitragsservice"). Words are the letter runs of the name: "Kfz-Versicherung" is two.
 *
 * It knows no language, no kind of document and no list of words: the capital letter is only the mark of a name in the scripts that have
 * one, and a script without capitals (Arabic) is checked by its numbers alone.
 */
class DocumentNameVerifier {

    /** The name as it may be shown, or null when it fails a check. */
    fun verify(name: String, ocrText: String): String? {
        if (name.contains('\n') || name.contains('\r')) return null
        val clean = DocumentNameFormat.clean(name)
        if (clean.isEmpty() || clean.length > DocumentNameFormat.MAX_CHARS) return null
        val letter = QuoteVerifier.fold(ocrText)
        if (letter.isBlank()) return null
        val letterDigits = DIGITS.findAll(letter).mapTo(mutableSetOf()) { it.value }
        if (!DIGITS.findAll(QuoteVerifier.fold(clean)).all { it.value in letterDigits }) return null
        val words = WORD.findAll(clean).map { it.value }.toList()
        for (word in words.drop(1)) {
            if (!word.first().isUpperCase() || word.length < MIN_NAME_WORD) continue
            if (!occurs(QuoteVerifier.fold(word), letter)) return null
        }
        return clean
    }

    private fun occurs(word: String, letter: String): Boolean {
        if (letter.contains(word)) return true
        val prefix = maxOf(MIN_PREFIX, (word.length + 1) / 2)
        return word.length > prefix && letter.contains(word.take(prefix))
    }

    private companion object {
        val DIGITS = Regex("\\p{Nd}+")
        val WORD = Regex("\\p{L}+")

        /** A word shorter than this is no name (an article, a preposition, a title such as "Dr"). */
        const val MIN_NAME_WORD = 3

        /** The fewest letters of a word the letter must hold for the word to count as printed. */
        const val MIN_PREFIX = 4
    }
}
