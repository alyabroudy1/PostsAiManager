package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.strings
import com.postsaimanager.core.domain.form.fill.FillProgress
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.Profile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * What the form agent is told about where things stand, computed by code from the stored fill: the STATE block that ends every tool
 * result, and the "suggested next" step. The model still decides; the suggestion is only the step the state points to (a role with
 * nobody chosen yet, the next open field, the card and the end), written as the call the model would make. It is also the one place
 * that reads a user's reply to a question: which chip and which person it matches (verified by comparing names, not by meaning).
 */
class FormGuidance(private val env: FormToolEnv) {

    /** The language the form is in, as its English name ("German"): the language the agent writes in. */
    suspend fun languageName(): String = env.formLanguage().getDisplayLanguage(Locale.ENGLISH)

    /**
     * The STATE block: the language, the people chosen per role, how many fields are filled, the first open fields and a
     * "suggested next" line. [answeredPerson] is the person a user's reply just named, so the suggestion can use it.
     */
    suspend fun state(answeredPerson: Profile? = null, answer: String? = null): String {
        val fields = env.fields()
        val language = languageName()
        if (fields.isEmpty()) return "language: $language (write every question in it). The form has not been read yet. suggested next: read_form()."
        val people = env.managed()
        val chosen = env.fill()?.roleProfiles.orEmpty()
        val roles = chosen.mapNotNull { (role, id) ->
            people.firstOrNull { it.id == id }?.let { "${env.roles.nameIn(fields, role)}=${it.name} (${FormRefs.personAlias(people, it)})" }
        }
        val progress = FillProgress.of(fields)
        val open = FormRefs.open(fields)
        return buildString {
            append("language: $language (write every question in it). ")
            append(if (roles.isEmpty()) "No person is chosen yet. " else "People: ${roles.joinToString("; ")}. ")
            append("Filled ${progress.ready} of ${progress.total}. ")
            if (open.isEmpty()) {
                append("Nothing is open. ")
            } else {
                append("Open: ")
                append(open.take(MAX_OPEN_SHOWN).joinToString("; ") { field -> openLine(fields, field) })
                if (open.size > MAX_OPEN_SHOWN) append("; +${open.size - MAX_OPEN_SHOWN} more")
                append(". ")
            }
            append("suggested next: ").append(suggestedNext(fields, people, answeredPerson, answer)).append('.')
        }
    }

    /** The suggestion alone (for the error of a repeated question), for what the user answered. */
    suspend fun suggestionFor(answer: String): String {
        val fields = env.fields()
        return suggestedNext(fields, env.managed(), matchPerson(answer), answer)
    }

    /** The result of a turn-ending question for the user's [text]: what was answered and what it matched, with the state after it. */
    suspend fun replyResult(call: AgentEntry.Call, text: String): ToolResult {
        val chip = call.args.strings("chips").orEmpty().firstOrNull { fold(it) == fold(text) }
        val person = matchPerson(text)
        val people = env.managed()
        val data = buildJsonObject {
            put("answer", text)
            chip?.let { put("matched_chip", it) }
            person?.let { put("matched_person", FormRefs.personAlias(people, it)) }
        }
        return ToolResult.ok(data).with("state", state(person, text))
    }

    /** The managed person whose name [text] is (the whole name or its words, case and accents ignored); null when it names nobody. */
    suspend fun matchPerson(text: String): Profile? {
        val answer = fold(text)
        if (answer.isEmpty()) return null
        val answerWords = answer.split(' ')
        return env.managed().firstOrNull { person ->
            val name = fold(person.name)
            val words = name.split(' ')
            name == answer || answerWords.all { it in words }
        }
    }

    private suspend fun suggestedNext(fields: List<FormField>, people: List<Profile>, answeredPerson: Profile?, answer: String?): String {
        val chosen = env.fill()?.roleProfiles.orEmpty()
        val open = FormRefs.open(fields)
        val role = unassignedRoles(fields).firstOrNull()
        if (role != null) {
            val candidate = answeredPerson?.takeIf { fitsRole(role, it, chosen, people) } ?: guardianCandidate(role, chosen, people)
            if (candidate != null) return "fill_from_profile(person_id=${FormRefs.personAlias(people, candidate)}, role=${role.name.lowercase()})"
            if (role == FormRole.SUBJECT) {
                val names = people.joinToString(", ") { it.name }
                return "ask_user who the form is for, with the people ($names) as chips; then fill_from_profile for that person with role=subject"
            }
            val subject = chosen[FormRole.SUBJECT]?.let { id -> people.firstOrNull { it.id == id } }
            val chips = env.roles.candidates(role, subject, people).map { it.name } + env.roles.someoneElse()
            val wording = env.roles.nameIn(fields, role)
            return "ask_user who is \"$wording\" (a full question in the form's language, not the word alone), with the chips " +
                "${chips.joinToString(", ") { "\"$it\"" }}; then fill_from_profile for that person with role=${role.name.lowercase()}"
        }
        if (open.isEmpty()) return "show_fill_card(), then finish(summary)"
        if (answer != null) {
            val folded = fold(answer)
            val optionField = open.firstOrNull { field -> field.options.any { fold(it) == folded } }
            optionField?.let { field ->
                val option = field.options.first { fold(it) == folded }
                return "fill_field(field_id=${FormRefs.fieldAlias(fields, field)}, value=$option, source=option)"
            }
        }
        val next = open.first()
        val alias = FormRefs.fieldAlias(fields, next)
        val chips = if (next.options.isEmpty()) "" else " with the printed options as chips (${next.options.joinToString(", ")})"
        val first = "ask_user about $alias \"${next.labelText}\"$chips, or skip_field(field_id=$alias)"
        return if (answer == null) first else "if the answer was for an open field, fill_field(field_id=<that field>, value=<the answer>, source=user); otherwise $first"
    }

    /** Who most likely has [role] once the subject is known: the subject's first guardian (for a child). Null when nobody can be named. */
    private suspend fun guardianCandidate(role: FormRole, chosen: Map<FormRole, String>, people: List<Profile>): Profile? {
        if (role == FormRole.SUBJECT) return null
        val subject = chosen[FormRole.SUBJECT]?.let { id -> people.firstOrNull { it.id == id } } ?: return null
        return env.guardiansOf(subject).firstOrNull()
    }

    private fun openLine(fields: List<FormField>, field: FormField): String {
        val section = field.section?.takeIf { it.isNotBlank() }
        return "${FormRefs.fieldAlias(fields, field)} ${field.labelText}" + (section?.let { " ($it)" } ?: "")
    }

    /** The roles of the form that still have open fields and nobody chosen, in the order of [FormRole]. */
    suspend fun unassignedRoles(fields: List<FormField>): List<FormRole> {
        val chosen = env.fill()?.roleProfiles.orEmpty()
        return fields.filter { it.role != null && it.role != FormRole.OTHER && FormRefs.status(it) == "open" && it.role !in chosen }
            .mapNotNull { it.role }.distinct().sortedBy { it.ordinal }
    }

    private fun fitsRole(role: FormRole, person: Profile, chosen: Map<FormRole, String>, people: List<Profile>): Boolean {
        val subject = chosen[FormRole.SUBJECT]?.let { id -> people.firstOrNull { it.id == id } }
        return env.roles.candidates(role, subject, people).any { it.id == person.id }
    }

    private fun fold(text: String): String = FormRefs.fold(text)

    private companion object {
        const val MAX_OPEN_SHOWN = 5
    }
}
