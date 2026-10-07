package com.postsaimanager.core.domain.document.contacts

import com.postsaimanager.core.domain.form.agent.FormRefs

/**
 * The structural pre-filter of "same contact person?": which existing names are worth asking the model about. It only narrows; it
 * never says two names are the same person (that is the model's reading) and it never rules a person in.
 *
 * Names are compared as folded tokens (case, accents and punctuation ignored). Two names stay together when any token of one is
 * compatible with any token of the other: equal ("Müller" and "Müller", so "Frau Müller" still meets "Herr Müller", which the model then
 * decides) or an initial of the other ("N." and "Nadine"). A name without a single token narrows nothing. No title, salutation or
 * language is listed anywhere.
 */
object ContactNameFilter {

    /** Whether [candidate] is worth asking the model about for a contact printed as [printed]. */
    fun worthAsking(printed: String, candidate: String): Boolean {
        val wanted = tokens(printed)
        if (wanted.isEmpty()) return true
        val other = tokens(candidate)
        return wanted.any { a -> other.any { b -> compatible(a, b) } }
    }

    private fun compatible(a: String, b: String): Boolean =
        a == b || (a.length == 1 && b.startsWith(a)) || (b.length == 1 && a.startsWith(b))

    private fun tokens(text: String): List<String> = FormRefs.flat(text).split(' ').filter { it.isNotEmpty() }
}
