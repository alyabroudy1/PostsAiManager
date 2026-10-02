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
class FormGuidance(private val env: FormToolEnv, private val policy: ToolPolicy = ToolPolicy()) {

    /** The [FormStage] the fill stands at for [reply]: the one place the stage is computed (the exposure and the suggestion both read it). */
    suspend fun stage(fields: List<FormField>, reply: UserReply?): FormStage =
        if (fields.isEmpty()) FormStage.NOT_READ else FormStage.of(roleSituation(fields, reply), FormRefs.open(fields).isNotEmpty())

    /**
     * The "suggested next" text for [reply], guaranteed to name only tools the [policy] exposes at the current stage: a suggestion that would
     * name another tool is replaced by the list of what may be called, so the guidance and the tool exposure can never contradict.
     */
    private suspend fun suggestion(fields: List<FormField>, people: List<Profile>, reply: UserReply?, answerForFields: Boolean): String {
        val text = suggestedNext(fields, people, reply, answerForFields)
        val allowed = policy.allowed(stage(fields, reply), rememberPending = true)
        return if (allowed.containsAll(policy.named(text))) text else "call one of: ${allowed.joinToString(", ")}"
    }

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
            append("suggested next: ").append(suggestion(fields, people, reply, answerForFields)).append('.')
        }
    }

    /** The suggestion alone (for the error of a repeated question), for the user's latest [reply] (what the tool exposure reads too). */
    suspend fun suggestionFor(reply: UserReply?): String {
        val fields = env.fields()
        return suggestion(fields, env.managed(), reply, answerForFields = true)
    }

    /** The result of a turn-ending question for the user's [text]: what was answered and what it matched, with the state after it. */
    suspend fun replyResult(call: AgentEntry.Call, text: String): ToolResult {
        val chip = call.args.strings("chips").orEmpty().firstOrNull { fold(it) == fold(text) }
        val person = matchPerson(text)
        val people = env.managed()
        // An answer that names more than one person is not registered: the model gets the raw answer and the candidates and decides.
        val candidates = if (person == null) ambiguousPeople(text) else emptyList()
        val data = buildJsonObject {
            put("answer", text)
            chip?.let { put("matched_chip", it) }
            person?.let { put("matched_person", FormRefs.personAlias(people, it)) }
            if (candidates.isNotEmpty()) put("candidates", candidates.joinToString(", ") { it.name })
            nameNeeded(UserReply(text, call))?.let { put("note", it) }
        }
        return ToolResult.ok(data).with("state", state(UserReply(text, call), answerForFields = true))
    }

    /**
     * What the model is told, plainly, when [reply] chose "someone else" for a role (the stage ROLE_NAME_NEEDED): the user will give the
     * name, so the next step is asking for it, not asking who it is again. Null in every other situation.
     */
    suspend fun nameNeeded(reply: UserReply): String? {
        val fields = env.fields()
        val situation = roleSituation(fields, reply) as? RoleSituation.NeedsPerson ?: return null
        if (!situation.someoneElse) return null
        return "The user will give the name of the \"${env.roles.nameIn(fields, situation.role)}\" person. Ask for their name now (no chips)."
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
        val fromAnswer = answered?.takeIf { fitsRole(role, it, chosen, people) }
        val candidate = fromAnswer ?: guardianCandidate(role, chosen, people)
        if (candidate != null) return RoleSituation.Ready(role, candidate, fromAnswer = fromAnswer != null)
        // Somebody who is not stored: the "someone else" chip asks for a name; a name typed after the question names them. For the
        // subject this only reads a reply to a question that offered people (the who-question), and not one that names several of them.
        if (reply != null && reply.asked != null && answered == null && ambiguousPeople(reply.text).isEmpty() && (role != FormRole.SUBJECT || offeredPeople(reply.asked, people))) {
            // "Me" with no profile of the user is somebody who is not stored too (with a profile it matched a person above).
            val answer = fold(reply.text)
            if (answer == fold(env.roles.someoneElse()) || answer == fold(env.roles.me())) return RoleSituation.NeedsPerson(role, someoneElse = true)
            if (!reply.isChipOfQuestion()) {
                val nameFields = FormRefs.ordered(FormRefs.open(fields).filter { it.role == role && it.dataKey?.let(FormDataKeys::of)?.valueKind == FormValueKind.NAME })
                return RoleSituation.Typed(role, nameFields.ifEmpty { FormRefs.open(fields).filter { it.role == role } }, reply.text)
            }
        }
        return if (role == FormRole.SUBJECT) RoleSituation.SubjectUnknown else RoleSituation.NeedsPerson(role, someoneElse = false)
    }

    /** Whether [asked] offered the managed people as its chips (so it was a question about who, and a typed reply names somebody). */
    private fun offeredPeople(asked: AgentEntry.Call, people: List<Profile>): Boolean =
        asked.args.strings("chips").orEmpty().any { chip -> people.any { fold(it.name) == fold(chip) } }

    /**
     * The managed person whose name [text] is: the whole name (case, accents and spacing ignored) of exactly one person, else (when no
     * name is the whole text) the one person whose name has all the words of [text]. Null when it names nobody or more than one.
     */
    suspend fun matchPerson(text: String): Profile? {
        val answer = fold(text)
        if (answer.isEmpty()) return null
        val people = env.managed()
        people.filter { fold(it.name) == answer }.let { exact -> if (exact.isNotEmpty()) return exact.singleOrNull() }
        // The chip for the user themself ("Ich") names the profile of the user, whatever its name.
        if (answer == fold(env.roles.me())) env.selfPerson()?.let { return it }
        return wordMatches(answer, people).singleOrNull()
    }

    /** The managed people whose names [text] could mean (several of them): empty when it matches one person, or nobody. */
    suspend fun ambiguousPeople(text: String): List<Profile> {
        val answer = fold(text)
        if (answer.isEmpty()) return emptyList()
        val people = env.managed()
        val exact = people.filter { fold(it.name) == answer }
        val found = exact.ifEmpty { wordMatches(answer, people) }
        return if (found.size > 1) found else emptyList()
    }

    private fun wordMatches(answer: String, people: List<Profile>): List<Profile> {
        val answerWords = answer.split(' ')
        return people.filter { person -> answerWords.all { it in fold(person.name).split(' ') } }
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
                return "ask_user who the form is for, with the people ($names) as chips (their names, never p1 or f1)"
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
                    chips.joinToString(", ") { "\"$it\"" } + ", or skip_field(field_id=<one of its fields>) to leave it"
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
        /**
         * The user's answer that begins the current turn, with the question it answers; null when the turn did not begin with one, or when
         * it was already used to fill a role from a person (one answer names the person of one role, never the next role's as well).
         */
        fun of(context: AgentContext): UserReply? {
            if (!context.turnStartedByUser) return null
            val sinceReply = context.entries.drop(context.entries.indexOfLast { it is AgentEntry.UserText } + 1)
            if (sinceReply.any { it is AgentEntry.Result && it.name == FillFromProfileTool.NAME && it.result.ok }) return null
            return context.userReplies.lastOrNull()?.let { UserReply(it, context.previousTurnEnd) }
        }
    }
}

/** Where the form's roles stand (see [FormGuidance.roleSituation]). */
sealed interface RoleSituation {
    /** Every role with open fields has a person (or none of them has a role). */
    data object None : RoleSituation

    /** [person] can be given [role]: its fields can be filled from their stored details. */
    data class Ready(val role: FormRole, val person: Profile, val fromAnswer: Boolean = false) : RoleSituation

    /** Nobody is known as the subject yet. */
    data object SubjectUnknown : RoleSituation

    /** [role] has no person; the user must say who ([someoneElse]: they chose "someone else", so the name is what is missing). */
    data class NeedsPerson(val role: FormRole, val someoneElse: Boolean) : RoleSituation

    /** The user typed [text] as who has [role]: it goes into the role's name fields ([nameFields]). */
    data class Typed(val role: FormRole, val nameFields: List<FormField>, val text: String) : RoleSituation
}
