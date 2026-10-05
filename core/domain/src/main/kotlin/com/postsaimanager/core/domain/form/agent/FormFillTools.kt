package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.AgentTool
import com.postsaimanager.core.domain.agent.ToolParams
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.flag
import com.postsaimanager.core.domain.agent.string
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.form.AnswerVerifiers
import com.postsaimanager.core.domain.form.FillContext
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.Verification
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.ReviewState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val READ_FORM_FIRST = "no form is read yet: call read_form first"

/** A one-line `f3|Label|value` of a field for a result: a sensitive value is its token. */
private fun resultLine(fields: List<FormField>, field: FormField): JsonPrimitive =
    JsonPrimitive("${FormRefs.fieldAlias(fields, field)}|${field.labelText}|${FormRefs.shownValue(field).orEmpty()}")

/**
 * `fill_field(field_id, value, source, person_id?)`: writes ONE value, only when [FieldValueGuard] proves where it came from
 * (a stored detail, the user's own words, a printed option). Otherwise it returns the reason, and the model fixes the call.
 */
class FillFieldTool(private val env: FormToolEnv, private val guidance: FormGuidance? = null) : AgentTool {
    override val name = NAME
    override val description = "Fills one field. source profile: a stored detail of person_id, copied exactly. source user: the user's " +
        "own words, exactly. source option: a printed option (yes or no for a tick box). Anything else is refused."
    override val parameters: JsonObject = ToolParams.schema(
        ToolParams.string("field_id", ""),
        ToolParams.string("value", ""),
        ToolParams.enumString("source", "", ValueSource.WIRE),
        ToolParams.string("person_id", "", required = false),
    )

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val result = attempt(args, context)
        return if (result.ok) result else typedRoleFallback(result, context)
    }

    /**
     * A refused fill while the user's typed answer waits for the role's name field (the stage ROLE_TYPED): the error repeats the exact
     * call; the second refusal in the turn leaves that field to the user (skipped, [SKIPPED_KEY] tells the chat to say so), so the run
     * never dead-ends there.
     */
    private suspend fun typedRoleFallback(refused: ToolResult, context: AgentContext): ToolResult {
        val typed = guidance?.typedRole(UserReply.of(context)) ?: return refused
        val field = typed.nameFields.first()
        val alias = FormRefs.fieldAlias(env.fields(), field)
        val refusals = context.entries.drop(context.entries.indexOfLast { it is AgentEntry.UserText } + 1)
            .count { it is AgentEntry.Result && it.name == NAME && !it.result.ok }
        if (refusals < 1) return ToolResult.error("${refused.errorMessage}. Call exactly: ${guidance.typedCall(alias, typed.text)}")
        env.fills.setSkipped(field.id, true, env.clock())
        return ToolResult.ok(
            buildJsonObject {
                put(SKIPPED_KEY, field.labelText)
                put("open_fields", FormRefs.open(env.fields()).size)
            },
        )
    }

    private suspend fun attempt(args: JsonObject, context: AgentContext): ToolResult {
        val fields = env.fields()
        if (fields.isEmpty()) return ToolResult.error(READ_FORM_FIRST)
        val field = FormRefs.findField(fields, args.string("field_id").orEmpty()) ?: return ToolResult.error("unknown field_id; use an id from read_form")
        val source = ValueSource.of(args.string("source")) ?: return ToolResult.error("unknown source")
        val person = args.string("person_id")?.let { ref -> env.person(ref) ?: return ToolResult.error("unknown person_id; call list_people") }
        val verdict = env.guard.check(field, args.string("value").orEmpty(), source, person, context, env.locale())
        return when (verdict) {
            is FieldVerdict.Rejected -> ToolResult.error(verdict.reason)
            is FieldVerdict.Accepted -> {
                val review = if (source == ValueSource.PROFILE) ReviewState.CONFIRMED else ReviewState.EDITED
                env.fills.setValue(field.id, verdict.shown, verdict.source, review, verdict.profileId, env.clock())
                val after = env.fields()
                ToolResult.ok(
                    buildJsonObject {
                        put("filled", resultLine(after, after.first { it.id == field.id }))
                        put("open_fields", FormRefs.open(after).size)
                    },
                )
            }
        }
    }

    companion object {
        const val NAME = "fill_field"

        /** The result entry (the field's label) of a fill that left the typed role's name field to the user after two refusals. */
        const val SKIPPED_KEY = "skipped"
    }
}

/**
 * `fill_from_profile(person_id, role)`: fills every field of a role from that person's stored details at once ([FillValues]: key and
 * role known, a value stored, dates and IBANs written as the form does). The model decides WHO is which role; code copies. Values
 * that are old enough to be asked about again are filled and listed as `to_confirm`.
 */
class FillFromProfileTool(private val env: FormToolEnv) : AgentTool {
    override val name = NAME
    override val description = "Fills every field of a role from a person's stored details, once you know who has that role."
    override val parameters: JsonObject = ToolParams.schema(
        ToolParams.string("person_id", ""),
        ToolParams.enumString("role", "", ROLES),
    )

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val before = env.fields()
        val fill = env.fill()
        if (before.isEmpty() || fill == null) return ToolResult.error(READ_FORM_FIRST)
        val person = env.person(args.string("person_id").orEmpty()) ?: return ToolResult.error("unknown person_id; call list_people")
        val role = FormRole.entries.firstOrNull { it.name.lowercase() == args.string("role") } ?: return ToolResult.error("unknown role")
        // A secret detail goes only for a role the user has had a say in: the user must have replied at least once.
        val roles = fill.roleProfiles + (role to person.id)
        val confirmed = if (context.hasUserReply) fill.confirmedRoles + role else fill.confirmedRoles
        val self = env.selfId()
        val result = env.fillValues.fill(
            before,
            FillContext(
                roleProfiles = roles, confirmedRoles = confirmed, locale = env.locale(), todayPlaceProfileId = self,
                addressFallbacks = listOfNotNull(roles[FormRole.GUARDIAN], self).distinct(), nowMs = env.clock(),
            ),
        )
        env.fills.saveFields(env.fillId, result.fields.map { it.copy(reconfirm = it.reconfirm || it.id in result.needsReconfirm) })
        env.fills.saveFill(fill.copy(roleProfiles = roles, confirmedRoles = confirmed, updatedAt = env.clock()))
        val after = env.fields()
        val newlyFilled = after.filter { now -> now.value != null && before.firstOrNull { it.id == now.id }?.value == null }
        val missing = after.filter { it.role == role && FormRefs.status(it) == "open" }
        return ToolResult.ok(
            buildJsonObject {
                put("filled_count", newlyFilled.size)
                put("filled", JsonArray(newlyFilled.map { resultLine(after, it) }))
                put("to_confirm_with_user", JsonArray(newlyFilled.filter { it.reconfirm }.map { JsonPrimitive(FormRefs.fieldAlias(after, it)) }))
                put("no_stored_detail_for", JsonArray(missing.map { JsonPrimitive("${FormRefs.fieldAlias(after, it)} ${it.labelText}") }))
                put("open_fields", FormRefs.open(after).size)
            },
        )
    }

    companion object {
        const val NAME = "fill_from_profile"
        val ROLES: List<String> = FormRole.entries.map { it.name.lowercase() }
    }
}

/** `skip_field(field_id)`: the user does not want to answer; the field is left to write by hand. */
class SkipFieldTool(private val env: FormToolEnv) : AgentTool {
    override val name = NAME
    override val description = "Leaves a field for the user to write by hand (skipped or unknown)."
    override val parameters: JsonObject = ToolParams.schema(ToolParams.string("field_id", ""))

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val fields = env.fields()
        if (fields.isEmpty()) return ToolResult.error(READ_FORM_FIRST)
        val field = FormRefs.findField(fields, args.string("field_id").orEmpty()) ?: return ToolResult.error("unknown field_id; use an id from read_form")
        env.fills.setSkipped(field.id, true, env.clock())
        return ToolResult.ok("open_fields" to JsonPrimitive(FormRefs.open(env.fields()).size))
    }

    companion object {
        const val NAME = "skip_field"
    }
}

/**
 * `remember_detail(person_id, key, value, user_agreed)`: stores a detail on a person's profile, so the next form fills it alone.
 * Only after the user agreed: the previous step must have been an `ask_user` and the user's reply this turn's start, the model
 * must say they agreed, and the value must be what the user wrote or entered in this conversation, never a value of the model's.
 */
class RememberDetailTool(private val env: FormToolEnv) : AgentTool {
    override val name = NAME
    override val description = "Remembers a detail for a person, only after ask_user asked and the user said yes (call it in the turn after their reply)."
    override val parameters: JsonObject = ToolParams.schema(
        ToolParams.string("person_id", ""),
        ToolParams.enumString("key", "", KEYS),
        ToolParams.string("value", "As the user gave it."),
        ToolParams.boolean("user_agreed", "True only if the user said yes."),
    )

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        if (args.flag("user_agreed") != true) return ToolResult.error("the user has not agreed: ask them first, and only remember when they said yes")
        if (!context.turnStartedByUser || context.previousTurnEnd?.name != AskUserTool.NAME) {
            return ToolResult.error("ask the user with ask_user whether to remember it, and call remember_detail only in the turn after their answer")
        }
        val person = env.person(args.string("person_id").orEmpty()) ?: return ToolResult.error("unknown person_id; call list_people")
        val key = FormDataKeys.of(args.string("key")) ?: return ToolResult.error("unknown key")
        val value = args.string("value").orEmpty()
        val target = fold(value)
        val written = context.userReplies.any { fold(it).contains(target) } ||
            env.fields().any { it.valueSource == FormValueSource.USER && it.value != null && fold(it.value!!) == target }
        if (target.isEmpty() || !written) return ToolResult.error("only a value the user gave in this conversation can be remembered")
        val normalized = when (val verified = AnswerVerifiers.verify(key.valueKind, value, env.locale(), today = env.today())) {
            is Verification.Accepted -> verified.value
            is Verification.Rejected -> return ToolResult.error("\"$value\" does not look like a valid ${key.id}")
        }
        return when (val saved = env.remember(person.id, key.id, normalized, FactSource.FORM_ANSWER, env.documentId)) {
            is PamResult.Error -> ToolResult.error("could not remember it: ${saved.error.userMessage}")
            is PamResult.Success -> ToolResult.ok("remembered" to JsonPrimitive("${key.id} for ${person.name}"))
        }
    }

    private fun fold(text: String): String = QuoteVerifier.fold(text).trim().replace(Regex("\\s+"), " ")

    companion object {
        const val NAME = "remember_detail"

        /** The details that belong to a person (not the date or place of filling in, not the signature). */
        val KEYS: List<String> = FormDataKeys.ALL
            .filterNot { it.id in setOf(FormDataKeys.TODAY_DATE.id, FormDataKeys.TODAY_PLACE.id, FormDataKeys.SIGNATURE.id) }
            .map { it.id }
    }
}
