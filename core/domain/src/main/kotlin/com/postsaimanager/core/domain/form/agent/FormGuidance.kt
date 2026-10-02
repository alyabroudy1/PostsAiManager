package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.strings
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.fill.FillProgress
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueKind
import com.postsaimanager.core.model.FormValueSource
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
    suspend fun state(reply: UserReply? = null, answerForFields: Boolean = false): String {
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
            append("suggested next: ").append(suggestedNext(fields, people, reply, answerForFields)).append('.')
        }
    }

    /** The suggestion alone (for the error of a repeated question), for what the user answered to [asked]. */
    suspend fun suggestionFor(answer: String, asked: AgentEntry.Call? = null): String {
        val fields = env.fields()
        return suggestedNext(fields, env.managed(), UserReply(answer, asked), answerForFields = true)
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
        return ToolResult.ok(data).with("state", state(UserReply(text, call), answerForFields = true))
    }

    /**
     * Where the roles of the form stand for [reply] (the user's latest answer, when the turn began with one): the first role still
     * without a person is either ready to be filled from a person, waiting for the subject, waiting for somebody to be named, or was
     * answered with typed text. The state's suggestion and the tool exposure both read it, so they always agree.
     */
    suspend fun roleSituation(fields: List<FormField>, reply: UserReply?): RoleSituation {
        val role = unassignedRoles(fields).firstOrNull() ?: return RoleSituation.None
        val people = env.managed()
        val chosen = env.fill()?.roleProfiles.orEmpty()
        val answered = reply?.let { matchPerson(it.text) }
        val candidate = answered?.takeIf { fitsRole(role, it, chosen, people) } ?: guardianCandidate(role, chosen, people)
        if (candidate != null) return RoleSituation.Ready(role, candidate)
        if (role == FormRole.SUBJECT) return RoleSituation.SubjectUnknown
        // Somebody who is not stored: the "someone else" chip asks for a name; a name typed after the question names them.
        if (reply != null && reply.asked != null && answered == null) {
            if (fold(reply.text) == fold(env.roles.someoneElse())) return RoleSituation.NeedsPerson(role, someoneElse = true)
            if (!reply.isChipOfQuestion()) {
                val nameFields = FormRefs.ordered(FormRefs.open(fields).filter { it.role == role && it.dataKey?.let(FormDataKeys::of)?.valueKind == FormValueKind.NAME })
                return RoleSituation.Typed(role, nameFields.ifEmpty { FormRefs.open(fields).filter { it.role == role } }, reply.text)
            }
        }
        return RoleSituation.NeedsPerson(role, someoneElse = false)
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

    private suspend fun suggestedNext(fields: List<FormField>, people: List<Profile>, reply: UserReply?, answerForFields: Boolean): String {
        val chosen = env.fill()?.roleProfiles.orEmpty()
        val open = FormRefs.open(fields)
        val answer = reply?.text?.takeIf { answerForFields }
        when (val situation = roleSituation(fields, reply)) {
            RoleSituation.None -> Unit
            is RoleSituation.Ready ->
                return "fill_from_profile(person_id=${FormRefs.personAlias(people, situation.person)}, role=${situation.role.name.lowercase()})"
            RoleSituation.SubjectUnknown -> {
                val names = people.joinToString(", ") { it.name }
                return "ask_user who the form is for, with the people ($names) as chips; then fill_from_profile for that person with role=subject"
            }
            is RoleSituation.Typed -> {
                val wording = env.roles.nameIn(fields, situation.role)
                val targets = situation.nameFields.joinToString(", ") { FormRefs.fieldAlias(fields, it) }
                return "the user typed who is \"$wording\": fill_field(field_id=<one of $targets>, value=${situation.text}, source=user) for its name " +
                    "(not a name: skip_field instead); do not ask who it is again"
            }
            is RoleSituation.NeedsPerson -> {
                val wording = env.roles.nameIn(fields, situation.role)
                if (situation.someoneElse) {
                    return "ask_user for the full name of \"$wording\" (a new question in the form's language, no chips; the user types it)"
                }
                val subject = chosen[FormRole.SUBJECT]?.let { id -> people.firstOrNull { it.id == id } }
                val chips = env.roles.candidates(situation.role, subject, people).map { it.name } + env.roles.someoneElse()
                return "ask_user who is \"$wording\" (a full question in the form's language, not the word alone), with the chips " +
                    "${chips.joinToString(", ") { "\"$it\"" }}; then fill_from_profile for that person with role=${situation.role.name.lowercase()}"
            }
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

    /**
     * The roles of the form that still have open fields and nobody chosen, in the order of [FormRole]. A role the user answered by hand
     * (one of its fields holds what they typed, or was skipped) is settled: its other open fields are asked like any field.
     */
    suspend fun unassignedRoles(fields: List<FormField>): List<FormRole> {
        val chosen = env.fill()?.roleProfiles.orEmpty()
        val byHand = fields.filter { it.value != null && it.valueSource == FormValueSource.USER || it.skipped }.mapNotNull { it.role }.toSet()
        return fields.filter { it.role != null && it.role != FormRole.OTHER && FormRefs.status(it) == "open" && it.role !in chosen && it.role !in byHand }
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

/** What the user just wrote ([text]) and the question it answers ([asked], the turn-ending call before it; null for a reply to nothing). */
data class UserReply(val text: String, val asked: AgentEntry.Call?) {

    /** Whether [text] is one of the answer chips of the question (the user tapped it, or typed exactly it). */
    fun isChipOfQuestion(): Boolean = asked?.args?.strings("chips").orEmpty().any { FormRefs.fold(it) == FormRefs.fold(text) }

    companion object {
        /** The user's answer that begins the current turn, with the question it answers; null when the turn did not begin with one. */
        fun of(context: AgentContext): UserReply? =
            if (context.turnStartedByUser) context.userReplies.lastOrNull()?.let { UserReply(it, context.previousTurnEnd) } else null
    }
}

/** Where the form's roles stand (see [FormGuidance.roleSituation]). */
sealed interface RoleSituation {
    /** Every role with open fields has a person (or none of them has a role). */
    data object None : RoleSituation

    /** [person] can be given [role]: its fields can be filled from their stored details. */
    data class Ready(val role: FormRole, val person: Profile) : RoleSituation

    /** Nobody is known as the subject yet. */
    data object SubjectUnknown : RoleSituation

    /** [role] has no person; the user must say who ([someoneElse]: they chose "someone else", so the name is what is missing). */
    data class NeedsPerson(val role: FormRole, val someoneElse: Boolean) : RoleSituation

    /** The user typed [text] as who has [role]: it goes into the role's name fields ([nameFields]). */
    data class Typed(val role: FormRole, val nameFields: List<FormField>, val text: String) : RoleSituation
}
