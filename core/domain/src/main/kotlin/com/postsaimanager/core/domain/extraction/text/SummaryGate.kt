package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier

/**
 * Decides whether a summary the model wrote may be kept. Code only verifies; it never decides what the summary says.
 *
 * Three checks, none of them specific to a language (they compare digits, letters and case, which every script has or
 * lacks on its own terms):
 *
 * 1. **Numbers.** Every digit run, amount and date in the answer must occur in the letter's text or in the verified facts.
 *    Both sides are folded by [QuoteVerifier.fold], so Arabic-Indic digits and separators compare equal to Western ones.
 * 2. **Names.** Every span of two or more capitalised words must be made only of words that occur in the letter or in the
 *    verified facts (a span with a word the letter never printed is an invented name). A sentence's
 *    first word is never the start of a span (a capital there says nothing). A script without capitals has no spans,
 *    so this check simply finds nothing to reject.
 * 3. **Anti-copy.** The answer is rejected when verbatim runs of the letter ([MIN_COPY_RUN] words or more in a row) make up at least
 *    [COPY_SHARE] of its words: small models copy a line, and a copied line is not a summary. Overlap alone is no copy: a short
 *    letter's faithful summary reuses most of its words.
 *
 * An empty answer and a runaway are rejected; one a little over [SummaryLimits.MAX_CHARS] is trimmed to its last whole sentence.
 */
class SummaryGate {

    /** Why an answer was rejected. */
    enum class Reason { EMPTY, TOO_LONG, UNVERIFIED_NUMBER, UNVERIFIED_NAME, COPIED }

    sealed interface Verdict {
        data class Accepted(val text: String) : Verdict
        data class Rejected(val reason: Reason, val detail: String = "") : Verdict
    }

    /**
     * @param ocrText the letter's text
     * @param verifiedValues the verified facts' values (names, amounts, dates, subject) the answer may use
     */
    fun check(answer: String, ocrText: String, verifiedValues: List<String>): Verdict {
        val full = answer.trim().replace(WHITESPACE, " ")
        if (full.isEmpty()) return Verdict.Rejected(Reason.EMPTY)
        // A summary a little over the limit that ends at a sentence boundary is cut to its last whole sentence within the limit; a runaway
        // or one with no sentence end inside the limit is refused.
        val text = if (full.length <= SummaryLimits.MAX_CHARS) full else {
            if (full.length > SummaryLimits.MAX_CHARS * SummaryLimits.RUNAWAY_FACTOR) return Verdict.Rejected(Reason.TOO_LONG)
            trimToSentence(full) ?: return Verdict.Rejected(Reason.TOO_LONG)
        }
        val corpus = ocrText + "\n" + verifiedValues.joinToString("\n")
        unverifiedNumber(text, corpus)?.let { return Verdict.Rejected(Reason.UNVERIFIED_NUMBER, it) }
        unverifiedName(text, corpus)?.let { return Verdict.Rejected(Reason.UNVERIFIED_NAME, it) }
        if (copiesLetter(text, ocrText)) return Verdict.Rejected(Reason.COPIED)
        return Verdict.Accepted(text)
    }

    /** [text] up to its last sentence end that lies within [SummaryLimits.MAX_CHARS] (a mark followed by a space or the end); null when none, or too little is left. */
    private fun trimToSentence(text: String): String? {
        val window = text.take(SummaryLimits.MAX_CHARS + 1)
        for (i in window.indices.reversed()) {
            if (window[i] !in SENTENCE_MARKS) continue
            val endsHere = i + 1 >= text.length || text[i + 1] == ' '
            if (endsHere && i + 1 <= SummaryLimits.MAX_CHARS) return text.substring(0, i + 1).takeIf { it.length >= MIN_TRIMMED_CHARS }
        }
        return null
    }

    // ── numbers ──────────────────────────────────────────────────────────────

    private fun unverifiedNumber(answer: String, corpus: String): String? {
        val folded = QuoteVerifier.fold(corpus)
        val runs = DIGITS.findAll(folded).map { it.value }.toSet()
        val joined = SEPARATOR_BETWEEN_DIGITS.replace(folded, "")
        for (token in NUMBER.findAll(QuoteVerifier.fold(answer)).map { it.value }) {
            val ok = if (token.all { it.isDigit() }) token in runs else SEPARATOR_BETWEEN_DIGITS.replace(token, "") in joined
            if (!ok) return token
        }
        return null
    }

    // ── names ────────────────────────────────────────────────────────────────

    private fun unverifiedName(answer: String, corpus: String): String? {
        val known = tokens(corpus).toSet()
        for (sentence in answer.split(SENTENCE_END)) {
            for (span in capitalisedSpans(sentence)) {
                // A span is a name the letter never gave only when one of its words is not in the letter at all. A run of
                // words that are all there ("Sie Ihre Rechnung": German capitalises its nouns and polite pronouns) is not a name.
                if (tokens(span).any { !isKnown(it, known) }) return span
            }
        }
        return null
    }

    /**
     * A word the letter prints, or one that is only another form of such a word (an ending added or dropped: "Ihren" for the letter's
     * "Ihr", "Antrags" for "Antrag"): a faithful summary inflects the letter's words, and an inflected word is no invented name. Letter
     * by letter, no language: the shorter word is at least [MIN_STEM] letters and is the start of the longer, which is at most [MAX_ENDING] longer.
     */
    private fun isKnown(token: String, known: Set<String>): Boolean =
        token in known || known.any { w ->
            minOf(w.length, token.length) >= MIN_STEM && kotlin.math.abs(w.length - token.length) <= MAX_ENDING && (token.startsWith(w) || w.startsWith(token))
        }

    /** Runs of two or more capitalised words; a trailing punctuation mark ends a run, the sentence's first word never starts one. */
    private fun capitalisedSpans(sentence: String): List<String> {
        val spans = mutableListOf<String>()
        var run = mutableListOf<String>()
        fun flush() {
            if (run.size >= 2) spans += run.joinToString(" ")
            run = mutableListOf()
        }
        val words = sentence.trim().split(' ').filter { it.isNotEmpty() }
        words.forEachIndexed { i, raw ->
            val word = raw.trim { !it.isLetterOrDigit() }
            val capitalised = i > 0 && word.isNotEmpty() && word.first().isUpperCase()
            if (capitalised) run.add(word) else flush()
            if (raw.isNotEmpty() && !raw.last().isLetterOrDigit()) flush()
        }
        flush()
        return spans
    }

    // ── anti-copy ────────────────────────────────────────────────────────────

    /**
     * Copying is long verbatim runs, not overlap: a short letter's faithful summary shares most of its words with the letter (the
     * same names, numbers and nouns), but a copy repeats the letter's own wording for words in a row. The answer's words that stand in
     * a run of at least [MIN_COPY_RUN] words found in the letter in the same order (across the scan's line breaks: a printed sentence is
     * wrapped by the page, and a copied sentence is a copy whichever way it was broken) are counted; the answer is a copy when they are
     * [COPY_SHARE] of it.
     */
    private fun copiesLetter(answer: String, ocrText: String): Boolean = copiedShare(answer, ocrText) >= COPY_SHARE

    /** The share (0..1) of [answer]'s words that stand in a verbatim run of [MIN_COPY_RUN] or more words of [ocrText]; 0 for no words. */
    fun copiedShare(answer: String, ocrText: String): Double {
        val words = tokens(answer)
        if (words.isEmpty()) return 0.0
        val letter = tokens(ocrText)
        val covered = BooleanArray(words.size)
        for (i in words.indices) {
            var longest = 0
            for (j in letter.indices) {
                if (letter[j] != words[i]) continue
                var k = 0
                while (i + k < words.size && j + k < letter.size && words[i + k] == letter[j + k]) k++
                if (k > longest) longest = k
            }
            if (longest >= MIN_COPY_RUN) for (k in 0 until longest) covered[i + k] = true
        }
        return covered.count { it }.toDouble() / words.size
    }

    private fun tokens(s: String): List<String> = TOKEN.findAll(QuoteVerifier.fold(s)).map { it.value }.toList()

    companion object {
        /** Reject when verbatim runs of the letter make up this share of the answer's words. */
        const val COPY_SHARE = 0.7

        /** The shortest word that can be the start of another form of a word of the letter, and the most letters an ending adds. */
        const val MIN_STEM = 3
        const val MAX_ENDING = 3

        /** The fewest words in a row, found in the letter in the same order, that count as copied wording. */
        const val MIN_COPY_RUN = 5

        /** A trimmed summary shorter than this is no summary: the answer is refused instead. */
        const val MIN_TRIMMED_CHARS = 30

        private val SENTENCE_MARKS = charArrayOf('.', '!', '?', '؟', '。')

        private val WHITESPACE = Regex("\\s+")
        private val DIGITS = Regex("\\d+")
        private val NUMBER = Regex("\\d+(?:[.,:/\\-]\\d+)*")
        private val SEPARATOR_BETWEEN_DIGITS = Regex("(?<=\\d)[.,:/\\-](?=\\d)")
        private val TOKEN = Regex("[\\p{L}\\p{Nd}]+")
        private val SENTENCE_END = Regex("(?<=[.!?؟。])\\s+")
    }
}
