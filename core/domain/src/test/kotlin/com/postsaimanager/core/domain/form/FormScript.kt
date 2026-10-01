package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormRole

/**
 * The scripted "model" of the form tests: scores every question the form steps ask by what the test says is true of the form,
 * recognising the question by its text (the same text the steps send). Yes is [YES], No is [NO]; anything unknown scores 0.
 */
class FormScript(
    /** Labels (substrings) the model judges not to be fields. */
    private val notFields: Set<String> = emptySet(),
    /** Label to the data key it really asks for. */
    private val keyOf: Map<String, String> = emptyMap(),
    /** Section heading to its role. */
    private val sectionRoles: Map<String, FormRole> = emptyMap(),
    /** Label to the role the field itself asks for. */
    private val fieldRoles: Map<String, FormRole> = emptyMap(),
    /** A profile's name to the score of "this form is for it". */
    private val subjects: Map<String, Double> = emptyMap(),
    /** The line of the form that supports the subject. */
    private val reasonLine: String? = null,
) {
    private val quoted = Regex("«([^»]*)»")

    fun score(continuation: String): Double {
        val q = continuation.substringAfterLast("\n\n")
        val label = quoted.find(q)?.groupValues?.get(1)?.removeSuffix(" …")?.trim()
        return when {
            q.contains("something the reader must fill in") -> if (notFields.any { label!!.contains(it) }) NO else YES / 2
            q.contains("ask for something other than the details above") -> if (label in keyOf) -1.0 else YES / 2
            q.startsWith("Does «") && q.contains("» ") && q.contains(" ask for ") && !q.contains("details about") -> keyScore(q, label)
            q.startsWith("Does the part of the form") -> roleScore(q, sectionRoles[label])
            q.startsWith("Does the field «") -> roleScore(q, fieldRoles[label])
            q.startsWith("Is this form for") -> subjects.entries.firstOrNull { q.contains(it.key) }?.value ?: NO
            q.startsWith("Does the line «") -> if (label == reasonLine) 3.0 else -2.0
            else -> 0.0
        }
    }

    private fun keyScore(q: String, label: String?): Double {
        val asked = FormDataKeys.ALL.firstOrNull { q.contains("ask for ${it.description}?") } ?: return NO
        return if (keyOf[label] == asked.id) YES else NO
    }

    /** A section question names the heading in the first «» when it has one, and the fields in the second. */
    private fun roleScore(q: String, expected: FormRole?): Double {
        val asked = FormRoles.descriptions.entries.firstOrNull { q.contains("details about ${it.value}?") }?.key ?: return NO
        return if (expected == asked) YES else NO
    }

    companion object {
        const val YES = 4.0
        const val NO = -3.0
    }
}
