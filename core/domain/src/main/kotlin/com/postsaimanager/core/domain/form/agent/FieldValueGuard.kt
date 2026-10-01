package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.form.AddressComposer
import com.postsaimanager.core.domain.form.AnswerVerifiers
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.FormValueFormatter
import com.postsaimanager.core.domain.form.PersonDataSource
import com.postsaimanager.core.domain.form.PersonValue
import com.postsaimanager.core.domain.form.Rejection
import com.postsaimanager.core.domain.form.Verification
import com.postsaimanager.core.model.CheckboxValue
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormValueKind
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.Profile
import java.time.LocalDate
import java.util.Locale

/** Where the value of a `fill_field` call says it comes from. */
enum class ValueSource(val wire: String) {
    /** A value a person has stored (copied from `get_person_details`, a secret by its `***` token). */
    PROFILE("profile"),

    /** The user's own words in this conversation. */
    USER("user"),

    /** An option printed on the form (or yes/no for a box without options). */
    OPTION("option"),
    ;

    companion object {
        val WIRE: List<String> = entries.map { it.wire }

        fun of(wire: String?): ValueSource? = entries.firstOrNull { it.wire == wire }
    }
}

/** The outcome of checking a value for a field: what is written, with its source, or why it is refused (said to the model). */
sealed interface FieldVerdict {
    data class Accepted(val shown: String, val source: FormValueSource, val profileId: String?) : FieldVerdict

    data class Rejected(val reason: String) : FieldVerdict
}

/**
 * THE guardrail of the form agent: no value is ever invented, by construction. A value the model passes to `fill_field` is
 * written only when it provably comes from one of three places, and then only after the same shape checks the form's questions
 * always had ([AnswerVerifiers]):
 *
 * - **profile**: it is a value a person has stored (the stored text, its date or IBAN as the form writes it, the composed
 *   address, a part of the full name) or the `***key` token of a sensitive one, and a sensitive value goes only into a field
 *   that asks for that very detail;
 * - **user**: it is contained, word for word (case and accents folded), in something the user wrote in this conversation;
 * - **option**: it is one of the form's printed options (or yes/no for a box that prints none, after the user has spoken).
 *
 * Anything else is refused with a reason the model can act on. Nothing here asks a model anything: the check is code over
 * stored data and the transcript.
 */
class FieldValueGuard(
    private val people: PersonDataSource,
    private val addresses: AddressComposer = AddressComposer(),
    private val today: () -> LocalDate = LocalDate::now,
) {

    suspend fun check(
        field: FormField,
        value: String,
        source: ValueSource,
        person: Profile?,
        context: AgentContext,
        locale: Locale,
    ): FieldVerdict {
        if (field.kind == FormFieldKind.SIGNATURE) return reject("a signature is written by hand on the paper: do not fill it")
        if (value.isBlank()) return reject("the value is empty")
        return when (source) {
            ValueSource.PROFILE -> fromProfile(field, value, person, locale)
            ValueSource.USER -> fromUser(field, value, person, context, locale)
            ValueSource.OPTION -> fromOption(field, value, person, context)
        }
    }

    // ── profile ──

    private suspend fun fromProfile(field: FormField, value: String, person: Profile?, locale: Locale): FieldVerdict {
        if (person == null) return reject("source profile needs a person_id (see list_people)")
        val stored = people.allOf(person.id)
        val match = match(value.trim(), stored, locale)
            ?: return reject(
                "\"${value.trim()}\" is not a stored detail of ${person.name}. Copy a value exactly from get_person_details, " +
                    "or ask the user and use source user.",
            )
        val (keyId, found) = match
        if ((found.sensitive || FormDataKeys.isSensitive(keyId)) && field.dataKey != keyId) {
            return reject("the secret detail \"$keyId\" can only go into a field that asks for it (this field asks for ${field.dataKey ?: "something else"})")
        }
        if (field.options.isNotEmpty()) {
            return when (val choice = AnswerVerifiers.verifyChoice(found.value, field.options)) {
                is Verification.Accepted -> FieldVerdict.Accepted(choice.value, found.source, person.id)
                is Verification.Rejected -> reject("this field prints options (${field.options.joinToString(" / ")}) and the stored value is not one of them: ask the user and use source option")
            }
        }
        return when (val verified = AnswerVerifiers.verify(kindOf(field, keyId), found.value, locale, today = today())) {
            is Verification.Accepted -> FieldVerdict.Accepted(formatted(kindOf(field, keyId), verified.value, locale), found.source, person.id)
            is Verification.Rejected -> reject(rejection(verified.reason, field))
        }
    }

    /** The stored detail [value] stands for: the stored text itself or one of its forms on the paper; null when it is none. */
    private fun match(value: String, stored: Map<String, PersonValue>, locale: Locale): Pair<String, PersonValue>? {
        TOKEN.matchEntire(value)?.let { m -> return stored[m.groupValues[1]]?.let { m.groupValues[1] to it } }
        val target = fold(value)
        val candidates = LinkedHashMap<String, PersonValue>().apply { putAll(stored) }
        addresses.compose(stored, null)?.let { candidates.putIfAbsent(FormDataKeys.ADDRESS.id, it) }
        stored[FormDataKeys.FULL_NAME.id]?.let { full ->
            val name = full.value.trim()
            val at = name.lastIndexOf(' ')
            if (at > 0) {
                candidates.putIfAbsent(FormDataKeys.GIVEN_NAME.id, full.copy(value = name.substring(0, at).trim()))
                candidates.putIfAbsent(FormDataKeys.FAMILY_NAME.id, full.copy(value = name.substring(at + 1).trim()))
            }
        }
        for ((keyId, found) in candidates) {
            val kind = FormDataKeys.of(keyId)?.valueKind
            val renderings = buildList {
                add(found.value)
                if (kind == FormValueKind.DATE) add(FormValueFormatter.date(found.value, locale))
                if (kind == FormValueKind.IBAN) add(FormValueFormatter.iban(found.value))
            }
            if (renderings.any { fold(it) == target }) return keyId to found
        }
        return null
    }

    // ── user ──

    private fun fromUser(field: FormField, value: String, person: Profile?, context: AgentContext, locale: Locale): FieldVerdict {
        if (!context.hasUserReply) return reject("the user has not said anything yet: ask_user first, then fill from their reply")
        if (field.options.isNotEmpty()) return reject("this field prints options (${field.options.joinToString(" / ")}): use source option with the printed option that fits the reply")
        if (field.kind == FormFieldKind.CHECKBOX) return reject("a tick box is filled with source option and the value yes or no")
        val target = fold(value)
        if (context.userReplies.none { fold(it).contains(target) }) {
            return reject("\"${value.trim()}\" is not what the user wrote. Use the user's own words exactly as they wrote them, or ask again.")
        }
        val kind = kindOf(field, field.dataKey)
        return when (val verified = AnswerVerifiers.verify(kind, value, locale, today = today())) {
            is Verification.Accepted -> FieldVerdict.Accepted(formatted(kind, verified.value, locale), FormValueSource.USER, person?.id)
            is Verification.Rejected -> reject(rejection(verified.reason, field))
        }
    }

    // ── option ──

    private fun fromOption(field: FormField, value: String, person: Profile?, context: AgentContext): FieldVerdict {
        if (field.options.isNotEmpty()) {
            return when (val choice = AnswerVerifiers.verifyChoice(value, field.options)) {
                is Verification.Accepted -> FieldVerdict.Accepted(choice.value, FormValueSource.FORM_OPTION, person?.id)
                is Verification.Rejected -> reject("\"${value.trim()}\" is not one of the printed options: ${field.options.joinToString(" / ")}")
            }
        }
        if (field.kind == FormFieldKind.CHECKBOX) {
            if (!context.hasUserReply) return reject("the user has not said anything yet: ask_user whether to tick this box")
            val answer = value.trim().lowercase(Locale.ROOT)
            return if (answer == CheckboxValue.YES || answer == CheckboxValue.NO) {
                FieldVerdict.Accepted(answer, FormValueSource.USER, person?.id)
            } else {
                reject("a tick box takes the value yes or no")
            }
        }
        return reject("this field has no printed options: use source user with the user's own words, or source profile")
    }

    // ── shared ──

    private fun kindOf(field: FormField, keyId: String?): FormValueKind =
        FormDataKeys.of(keyId)?.valueKind ?: FormDataKeys.of(field.dataKey)?.valueKind ?: if (field.kind == FormFieldKind.DATE) FormValueKind.DATE else FormValueKind.TEXT

    private fun formatted(kind: FormValueKind, normalized: String, locale: Locale): String = when (kind) {
        FormValueKind.DATE -> FormValueFormatter.date(normalized, locale)
        FormValueKind.IBAN -> FormValueFormatter.iban(normalized)
        else -> normalized
    }

    private fun rejection(reason: Rejection, field: FormField): String = when (reason) {
        Rejection.EMPTY -> "the value is empty"
        Rejection.NOT_AN_OPTION, Rejection.AMBIGUOUS_OPTION -> "the value is not one of the printed options"
        Rejection.NOT_A_DATE -> "\"${field.labelText}\" needs a date (for example 12.03.2019)"
        Rejection.NOT_A_PHONE -> "\"${field.labelText}\" needs a phone number"
        Rejection.NOT_AN_EMAIL -> "\"${field.labelText}\" needs an e-mail address"
        Rejection.NOT_AN_IBAN, Rejection.BAD_IBAN_CHECKSUM -> "\"${field.labelText}\" needs a valid IBAN"
        Rejection.NOT_A_POSTCODE -> "\"${field.labelText}\" needs a postcode"
    }

    private fun reject(reason: String) = FieldVerdict.Rejected(reason)

    private fun fold(text: String): String = QuoteVerifier.fold(text).trim().replace(WHITESPACE, " ")

    private companion object {
        val TOKEN = Regex("\\*\\*\\*([a-z_]+)")
        val WHITESPACE = Regex("\\s+")
    }
}
