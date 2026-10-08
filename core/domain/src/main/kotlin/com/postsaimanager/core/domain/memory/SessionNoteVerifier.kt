package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import javax.inject.Inject

/**
 * Decides which of the notes the model wrote at the end of a session may be kept. Code only verifies: it never words, picks or ranks
 * a note, and a kept note is exactly the text the model wrote (whitespace tidied).
 *
 * A note is dropped when
 * - it is empty, or longer than [SessionNotesFormat.MAX_NOTE_CHARS];
 * - it holds a number the session never had: every digit run of the note (a date or an amount is digit runs) must occur as a digit run
 *   in the user's own messages or in a tool result of the session, compared as numbers (`05` is `5`, Arabic-Indic digits are Western
 *   ones). The model's own earlier replies do not count, so it cannot ground a note in what it said itself;
 * - it repeats what is already known: an existing note, the document card, or an earlier kept note (compared folded for case,
 *   accents and punctuation, and as a containment in either direction for the notes); a note that mostly restates a note of a
 *   confirmed action (most of the shorter one's words are shared, at least three) is dropped too;
 * - [SessionNotesFormat.MAX_NOTES] notes are already kept, or the document already holds [MAX_NOTES_PER_DOCUMENT].
 *
 * Pure. Nothing here knows a language or a kind of fact.
 */
class SessionNoteVerifier @Inject constructor() {

    /**
     * @param candidates the model's notes, in order
     * @param grounding the user's messages and the session's tool results: where every number must come from
     * @param cardText the document card as text (the read values), whose repeats are dropped
     * @param existing the notes the document already holds
     * @param actionNotes the notes written by confirmed actions (already in [existing]): a candidate that mostly restates one is
     *   dropped, as the action already recorded it
     */
    fun verify(
        candidates: List<String>,
        grounding: List<String>,
        cardText: String,
        existing: List<String>,
        actionNotes: List<String> = emptyList(),
    ): List<String> {
        val actionWords = actionNotes.map(::words).filter { it.isNotEmpty() }
        val groundingNumbers = grounding.flatMapTo(mutableSetOf()) { numbers(it) }
        val cardKey = key(cardText)
        val known = existing.map(::key).filter { it.isNotEmpty() }.toMutableList()
        val room = (MAX_NOTES_PER_DOCUMENT - existing.size).coerceAtMost(SessionNotesFormat.MAX_NOTES)
        val kept = mutableListOf<String>()
        for (candidate in candidates) {
            if (kept.size >= room) break
            val note = candidate.replace(WHITESPACE, " ").trim()
            if (note.isEmpty() || note.length > SessionNotesFormat.MAX_NOTE_CHARS) continue
            if (!groundingNumbers.containsAll(numbers(note))) continue
            val noteKey = key(note)
            if (noteKey.isEmpty()) continue
            if (cardKey.contains(noteKey)) continue
            if (known.any { it.contains(noteKey) || noteKey.contains(it) }) continue
            val noteWords = words(note)
            if (actionWords.any { restates(noteWords, it) }) continue
            kept += note
            known += noteKey
        }
        return kept
    }

    /** The distinct words of [text], folded: letters and digits only. */
    private fun words(text: String): Set<String> = WORD.findAll(QuoteVerifier.fold(text)).mapTo(mutableSetOf()) { it.value }

    /** True when most of the shorter text's words are in the other: the two say the same thing in nearly the same words. */
    private fun restates(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a.count { it in b }
        return shared >= MIN_SHARED_WORDS && shared.toDouble() / minOf(a.size, b.size) >= RESTATE_RATIO
    }

    /** The digit runs of [text] as numbers: folded (Arabic-Indic digits are Western ones), leading zeros dropped. */
    private fun numbers(text: String): Set<String> =
        DIGITS.findAll(QuoteVerifier.fold(text)).mapTo(mutableSetOf()) { it.value.trimStart('0').ifEmpty { "0" } }

    /** Letters and digits only, folded: two texts that differ by case, spacing, accents or punctuation have one key. */
    private fun key(text: String): String = NOT_ALNUM.replace(QuoteVerifier.fold(text), "")

    companion object {
        /** The most notes one document holds from the sessions: a runaway never fills the memory. */
        const val MAX_NOTES_PER_DOCUMENT = 50

        private const val MIN_SHARED_WORDS = 3
        private const val RESTATE_RATIO = 0.6

        private val WORD = Regex("[\\p{L}\\p{Nd}]+")
        private val WHITESPACE = Regex("\\s+")
        private val NOT_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")
        private val DIGITS = Regex("\\p{Nd}+")
    }
}
