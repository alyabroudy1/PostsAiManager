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
 * 2. **Names.** Every span of two or more capitalised words must be a verified name or a quote of the letter. A sentence's
 *    first word is never the start of a span (a capital there says nothing). A script without capitals has no spans,
 *    so this check simply finds nothing to reject.
 * 3. **Anti-copy.** The answer is rejected when one line of the letter holds at least [COPY_SHARE] of its words: small
 *    models copy the first line, and a copied line is not a summary.
 *
 * An empty answer and one far over the asked length are rejected too.
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
        val text = answer.trim().replace(WHITESPACE, " ")
        if (text.isEmpty()) return Verdict.Rejected(Reason.EMPTY)
        if (text.split(' ').size > MAX_WORDS) return Verdict.Rejected(Reason.TOO_LONG)
        val corpus = ocrText + "\n" + verifiedValues.joinToString("\n")
        unverifiedNumber(text, corpus)?.let { return Verdict.Rejected(Reason.UNVERIFIED_NUMBER, it) }
        unverifiedName(text, corpus)?.let { return Verdict.Rejected(Reason.UNVERIFIED_NAME, it) }
        if (copiesOneLine(text, ocrText)) return Verdict.Rejected(Reason.COPIED)
        return Verdict.Accepted(text)
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
        for (sentence in answer.split(SENTENCE_END)) {
            for (span in capitalisedSpans(sentence)) {
                if (QuoteVerifier.verify(span, corpus) == null) return span
            }
        }
        return null
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

    private fun copiesOneLine(answer: String, ocrText: String): Boolean {
        val words = tokens(answer)
        if (words.isEmpty()) return false
        return ocrText.lineSequence().any { line ->
            val lineWords = tokens(line).toSet()
            lineWords.isNotEmpty() && words.count { it in lineWords }.toDouble() / words.size >= COPY_SHARE
        }
    }

    private fun tokens(s: String): List<String> = TOKEN.findAll(QuoteVerifier.fold(s)).map { it.value }.toList()

    companion object {
        /** Reject when one line of the letter holds this share of the answer's words. */
        const val COPY_SHARE = 0.7

        /** The ask says at most 30 words; the gate only refuses a runaway. */
        const val MAX_WORDS = 45

        private val WHITESPACE = Regex("\\s+")
        private val DIGITS = Regex("\\d+")
        private val NUMBER = Regex("\\d+(?:[.,:/\\-]\\d+)*")
        private val SEPARATOR_BETWEEN_DIGITS = Regex("(?<=\\d)[.,:/\\-](?=\\d)")
        private val TOKEN = Regex("[\\p{L}\\p{Nd}]+")
        private val SENTENCE_END = Regex("(?<=[.!?؟。])\\s+")
    }
}
