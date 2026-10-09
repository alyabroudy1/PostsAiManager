package com.postsaimanager.core.domain.ai

/**
 * Recognises a greedy decode that has started to repeat itself: the last [RUN] characters of the answer so far already stood earlier in
 * it. A terse labelled answer never repeats a run that long, so a repeat means the model is looping and every further token is wasted.
 *
 * Pure text; no word list, nothing about a language or a kind of letter.
 */
object RepetitionGuard {

    /** How many characters make a repeat (long enough that two legitimate lines never share them). */
    const val RUN = 60

    /** True when the last [RUN] characters of [text] (not all blank) occur earlier in it. */
    fun repeats(text: String): Boolean {
        if (text.length < 2 * RUN) return false
        val tail = text.takeLast(RUN)
        if (tail.isBlank()) return false
        return text.lastIndexOf(tail, text.length - RUN - 1) >= 0
    }

    /** [text] cut after the first occurrence of the repeated run when it loops (what was decoded before the loop is kept), else [text] as it is. */
    fun trimmed(text: String): String {
        if (!repeats(text)) return text
        val tail = text.takeLast(RUN)
        return text.substring(0, text.indexOf(tail) + RUN).trimEnd()
    }
}
