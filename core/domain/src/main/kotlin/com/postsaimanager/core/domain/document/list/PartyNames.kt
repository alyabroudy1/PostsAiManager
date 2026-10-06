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

    private fun tokens(text: String): List<String> =
        FormRefs.flat(text).split(' ').filter { it.isNotEmpty() }

    private fun containsRun(line: List<String>, run: List<String>): Boolean =
        (0..line.size - run.size).any { start -> run.indices.all { line[start + it] == run[it] } }
}
