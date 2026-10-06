package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.OcrText
import java.text.Normalizer

/**
 * Checks that text the model quoted is really in the letter.
 *
 * Three tiers, strictest first: the exact characters; the same words once case, spacing,
 * punctuation and accents are ignored; and a fuzzy match that forgives OCR slips (a word one edit
 * away, a short quote a couple of edits away) or a name assembled from two lines ("Max und Erika
 * Mustermann" answered as "Erika Mustermann"). A quote that passes none of them is dropped: the model
 * wrote something that is not in the text.
 *
 * Both sides are first normalised exactly as the candidate extractor normalises the OCR text
 * ([OcrText.normalizeChars]). Nothing here is specific to a language: it compares the letters and
 * digits of any script, with Unicode decomposition for accents and a small fold for Arabic spelling
 * variants (alef forms, teh marbuta, alef maksura, tatweel).
 */
object QuoteVerifier {

    /** A quote that was found, and how well. */
    data class Verified(val match: QuoteMatch)

    /** Fuzzy tier: at least this share of the quote's words must sit close together in the text. */
    const val MIN_TOKEN_OVERLAP = 0.8

    /** Fuzzy tier for long-ish quotes (up to [LONG_QUOTE_CHARS]): at most this edit distance relative to the length. */
    const val MAX_EDIT_RATIO = 0.10

    /** Quotes up to this length may differ by [SHORT_MIN_EDITS] or [SHORT_EDIT_RATIO], whichever is more. */
    const val SHORT_QUOTE_CHARS = 20
    const val SHORT_MIN_EDITS = 2
    const val SHORT_EDIT_RATIO = 0.15

    private const val LONG_QUOTE_CHARS = 60

    /** A word this long may be one edit away and still count as the same word. */
    private const val TOKEN_FUZZY_MIN_LENGTH = 5

    private val TOKEN = Regex("[\\p{L}\\p{Nd}]+")
    private val MARKS = Regex("\\p{Mn}+")

    /**
     * [verify] for a line the model was asked to copy as it is printed (the subject line): a single word that matched only fuzzily is not
     * accepted. A one-word quote two edits from some word of a whole letter proves nothing (a model answering with the kind of document,
     * "LETTER", matched a six-letter word of the German text); an OCR slip is forgiven in a phrase, where several words agree.
     */
    fun verifyCopiedLine(quote: String, ocrText: String): Verified? =
        verify(quote, ocrText)?.takeUnless { it.match == QuoteMatch.FUZZY && tokens(quote).size < 2 }

    fun verify(quote: String, ocrText: String): Verified? {
        val q = OcrText.normalizeChars(quote).trim()
        val text = OcrText.normalizeChars(ocrText)
        if (q.length < 2 || text.isBlank()) return null
        if (text.contains(q)) return Verified(QuoteMatch.EXACT)

        val quoteTokens = tokens(q)
        if (quoteTokens.isEmpty()) return null
        val textTokens = tokens(text)
        if (textTokens.isEmpty()) return null

        // Same words, same order, ignoring case, spacing, punctuation and accents.
        if (containsRun(textTokens, quoteTokens) { a, b -> a == b }) return Verified(QuoteMatch.NORMALIZED)

        if (tokenOverlap(textTokens, quoteTokens)) return Verified(QuoteMatch.FUZZY)
        if (q.length <= LONG_QUOTE_CHARS && closeByEditDistance(textTokens, quoteTokens)) return Verified(QuoteMatch.FUZZY)
        return null
    }

    /** Lowercased, accents removed, Arabic spelling variants unified. */
    internal fun fold(s: String): String {
        val lower = OcrText.normalizeChars(s).lowercase().replace("ß", "ss")
        val stripped = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        val sb = StringBuilder(stripped.length)
        for (ch in stripped) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا') // alef forms -> alef
                'ة' -> sb.append('ه') // teh marbuta -> heh
                'ى' -> sb.append('ي') // alef maksura -> yeh
                'ـ' -> Unit // tatweel
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun tokens(s: String): List<String> = TOKEN.findAll(fold(s)).map { it.value }.toList()

    private fun containsRun(text: List<String>, run: List<String>, same: (String, String) -> Boolean): Boolean {
        if (run.size > text.size) return false
        outer@ for (i in 0..text.size - run.size) {
            for (j in run.indices) if (!same(text[i + j], run[j])) continue@outer
            return true
        }
        return false
    }

    /** Two words are the same, or (both long enough) one edit apart, which is what an OCR slip costs. */
    private fun tokenMatch(a: String, b: String): Boolean =
        a == b || (a.length >= TOKEN_FUZZY_MIN_LENGTH && b.length >= TOKEN_FUZZY_MIN_LENGTH && levenshtein(a, b, 1) <= 1)

    /** Most of the quote's words appear inside one short stretch of the text. */
    private fun tokenOverlap(text: List<String>, quote: List<String>): Boolean {
        val wanted = quote.distinct()
        val window = quote.size + 2
        val need = kotlin.math.ceil(MIN_TOKEN_OVERLAP * wanted.size).toInt()
        for (start in 0 until maxOf(1, text.size - window + 1)) {
            val end = minOf(text.size, start + window)
            val stretch = text.subList(start, end)
            val hit = wanted.count { w -> stretch.any { tokenMatch(it, w) } }
            if (hit >= need) return true
        }
        return false
    }

    private fun closeByEditDistance(text: List<String>, quote: List<String>): Boolean {
        val target = quote.joinToString(" ")
        val allowed = if (target.length <= SHORT_QUOTE_CHARS) {
            maxOf(SHORT_MIN_EDITS, (target.length * SHORT_EDIT_RATIO).toInt())
        } else {
            (target.length * MAX_EDIT_RATIO).toInt()
        }
        for (size in maxOf(1, quote.size - 1)..quote.size + 1) {
            if (size > text.size) continue
            for (start in 0..text.size - size) {
                val candidate = text.subList(start, start + size).joinToString(" ")
                if (kotlin.math.abs(candidate.length - target.length) > allowed) continue
                if (levenshtein(candidate, target, allowed) <= allowed) return true
            }
        }
        return false
    }

    /** Edit distance, or [cap] + 1 as soon as it is certain to exceed [cap]. */
    private fun levenshtein(a: String, b: String, cap: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > cap) return cap + 1
            prev = cur
        }
        return prev[b.length]
    }
}
