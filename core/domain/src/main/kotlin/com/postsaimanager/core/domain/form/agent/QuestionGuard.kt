package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.Profile

/**
 * Refuses an `ask_user` that plainly is not a question for the user, with a hint the model can act on. It never judges meaning: it only
 * compares texts with the form (labels, sections, printed options) and the stored people.
 *
 * 1. A label or section title (punctuation aside) is not a question.
 * 2. For the role of a person who acts for the subject (guardian, payer, signer) the chips are not the subject when the subject is a
 *    minor, and for a guardian never the subject at all.
 * 3. A question about an open field offers that field's printed options, a stored person value for its key, or no chips: a person's
 *    name is never the chip of a field that is not about a person.
 */
class QuestionGuard(private val env: FormToolEnv, private val guidance: FormGuidance) {

    /** The error for the model, or null when the question and its chips may be shown. */
    suspend fun check(question: String, chips: List<String>): String? {
        val fields = env.fields()
        if (fields.isEmpty()) return null
        return bareLabel(question, fields) ?: fieldChips(question, chips, fields) ?: roleChips(question, chips, fields)
    }

    private fun bareLabel(question: String, fields: List<FormField>): String? {
        val flat = FormRefs.flat(question)
        val texts = fields.flatMap { listOfNotNull(it.labelText, it.section) }.map(FormRefs::flat).filter { it.isNotEmpty() }.toSet()
        if (flat !in texts) return null
        return "\"${question.trim()}\" is only a label or heading printed on the form, not a question. " +
            "Write a full question for the user in the form's language that ends with a question mark."
    }

    private suspend fun roleChips(question: String, chips: List<String>, fields: List<FormField>): String? {
        val chosen = env.fill()?.roleProfiles.orEmpty()
        val people = env.managed()
        val subject = chosen[FormRole.SUBJECT]?.let { id -> people.firstOrNull { it.id == id } } ?: return null
        val shown = chips.mapNotNull { guidance.matchPerson(it) }
        if (shown.isEmpty()) return null
        val role = roleAskedAbout(question, fields) ?: return null
        val allowed = env.roles.candidates(role, subject, people).map { it.id }.toSet()
        val wrong = shown.firstOrNull { it.id !in allowed } ?: return null
        val others = env.roles.candidates(role, subject, people).map { it.name } + env.roles.someoneElse()
        val why = if (role == FormRole.GUARDIAN) "the person the form is for" else "a minor"
        return "${wrong.name} is $why and cannot be \"${env.roles.nameIn(fields, role)}\": " +
            "use these chips instead: ${others.joinToString(", ") { "\"$it\"" }}"
    }

    /** The role a question is about: the one whose name (the form's words) it contains, else the first role still without a person. */
    private suspend fun roleAskedAbout(question: String, fields: List<FormField>): FormRole? {
        val flat = " ${FormRefs.flat(question)} "
        val named = RoleWording.PRESENTED_BY_ADULT.sortedBy { it.ordinal }.firstOrNull { role ->
            env.roles.phrases(fields, role).any { " $it " in flat }
        }
        val role = named ?: guidance.unassignedRoles(fields).firstOrNull()
        return role?.takeIf { it in RoleWording.PRESENTED_BY_ADULT }
    }

    private suspend fun fieldChips(question: String, chips: List<String>, fields: List<FormField>): String? {
        if (chips.isEmpty()) return null
        val flat = " ${FormRefs.flat(question)} "
        val chosen = env.fill()?.roleProfiles.orEmpty()
        // A field whose role has nobody yet is asked about as a role ("who is the account holder?"): the people are its chips.
        val about = FormRefs.open(fields).filter { field ->
            val label = FormRefs.flat(field.labelText)
            val roleOpen = field.role != null && field.role != FormRole.OTHER && field.role !in chosen
            !roleOpen && label.length >= MIN_LABEL_CHARS && " $label " in flat
        }
        if (about.isEmpty()) return null
        val people = env.managed()
        val allowed = about.map { allowedChips(it, people) }
        val wrong = chips.firstOrNull { chip -> allowed.none { FormRefs.fold(chip) in it } } ?: return null
        val options = about.firstOrNull { it.options.isNotEmpty() }?.options
        val offer = if (options != null) "offer its printed options (${options.joinToString(", ")}) or no chips" else "use no chips: the user types the answer"
        return "the chip \"$wrong\" does not belong to the question about \"${about.first().labelText}\": $offer"
    }

    /** The folded chips a field may show: its printed options and the stored values of its key for each person. */
    private suspend fun allowedChips(field: FormField, people: List<Profile>): Set<String> {
        val values = field.dataKey?.let { key ->
            people.mapNotNull { env.people.allOf(it.id)[key]?.takeIf { value -> !value.sensitive }?.value }
        }.orEmpty()
        return (field.options + values).map(FormRefs::fold).toSet()
    }

    private companion object {
        const val MIN_LABEL_CHARS = 3
    }
}
