package com.postsaimanager.core.domain.document.list

import com.postsaimanager.core.domain.form.agent.FormRefs

/**
 * The one owner of "does this printed name stand for that person": the document list's person chips,
 * the Pages tab's card and the Extracted tab's "For" line all ask here, so they cannot disagree.
 *
 * Code only verifies, structurally and in any language. Names are compared as folded tokens (case,
 * accents, spacing and punctuation ignored). A printed line names a person when
 *  - its tokens equal the person's, in any order ("Mustermann, Erika" is "Erika Mustermann"), or
 *  - it holds the person's full name as one unbroken run of tokens ("Frau Erika Mustermann"), when the
 *    person's name has at least two tokens: a lone first name inside a line proves nothing.
 *
 * Nothing splits a line on a conjunction, so a household line ("Max und Erika Mustermann") names Erika
 * only when her full name is contiguous in it, which it is not there.
 */
object PartyNames {

    /** Whether [printed] (a name or an address line as the letter printed it) names the person called [profileName]. */
    fun names(printed: String, profileName: String): Boolean {
        val line = tokens(printed)
        val name = tokens(profileName)
        if (line.isEmpty() || name.isEmpty()) return false
        if (line == name) return true
        if (name.size < 2) return false
        if (line.size == name.size && line.toSet() == name.toSet()) return true
        return containsRun(line, name)
    }

    /** Whether two printed names are the same one by the same folded comparison. */
    fun sameName(a: String, b: String): Boolean {
        val first = tokens(a)
        return first.isNotEmpty() && first == tokens(b)
    }

    /** The folded tokens of [text], for [mentions] to be asked many times of one letter. */
    fun tokenSet(text: String): Set<String> = tokens(text).toSet()

    /**
     * Whether a letter whose tokens are [letter] mentions the person called [profileName]: at least one of the name's tokens (of two
     * letters or more) is a whole token of the letter. The structural check that lets the model name only people the letter mentions.
     * It decides nothing by itself: who a letter is for is the model's reading.
     */
    fun mentions(letter: Set<String>, profileName: String): Boolean =
        tokens(profileName).any { it.length >= MIN_TOKEN && it in letter }

    /** [mentions] for a letter given as text. */
    fun mentions(letterText: String, profileName: String): Boolean = mentions(tokenSet(letterText), profileName)

    /**
     * Whether the letter prints the person's whole name as one unbroken run of tokens (a name of two or more tokens: a lone first name
     * proves nothing), in the order the profile has it or reversed ("Mustermann, Maria" for "Maria Mustermann"). A stricter check than [mentions].
     */
    fun printsFullName(letterText: String, profileName: String): Boolean {
        val letter = tokens(letterText)
        val name = tokens(profileName)
        if (name.size < 2) return false
        return containsRun(letter, name) || containsRun(letter, name.reversed())
    }

    private const val MIN_TOKEN = 2

    private fun tokens(text: String): List<String> =
        FormRefs.flat(text).split(' ').filter { it.isNotEmpty() }

    private fun containsRun(line: List<String>, run: List<String>): Boolean =
        (0..line.size - run.size).any { start -> run.indices.all { line[start + it] == run[it] } }
}
