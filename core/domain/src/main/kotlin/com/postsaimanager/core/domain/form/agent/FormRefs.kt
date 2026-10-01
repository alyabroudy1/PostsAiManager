package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.fill.FillProgress
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.Profile
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeParseException

/**
 * Short names the model can copy exactly: a small model garbles a UUID, so fields are `f1`, `f2`... (page and reading order, the
 * same on every run of the same fill) and people are `p1`, `p2`... Both resolve back to the stored id; the real id is accepted too.
 */
object FormRefs {

    private val FIELD = Regex("f(\\d+)")
    private val PERSON = Regex("p(\\d+)")

    fun ordered(fields: List<FormField>): List<FormField> = fields.sortedWith(compareBy({ it.page }, { it.orderIndex }, { it.id }))

    fun fieldAlias(fields: List<FormField>, field: FormField): String = "f${ordered(fields).indexOfFirst { it.id == field.id } + 1}"

    fun findField(fields: List<FormField>, ref: String): FormField? {
        val text = ref.trim()
        FIELD.matchEntire(text.lowercase())?.let { m -> return ordered(fields).getOrNull(m.groupValues[1].toInt() - 1) }
        return fields.firstOrNull { it.id == text }
    }

    /** The managed people in a stable order: "Me" first, then by name. */
    fun orderedPeople(profiles: List<Profile>): List<Profile> =
        profiles.filter { it.isManaged }.sortedWith(compareByDescending<Profile> { it.isSelf }.thenBy { it.name.lowercase() }.thenBy { it.id })

    fun personAlias(people: List<Profile>, person: Profile): String = "p${people.indexOfFirst { it.id == person.id } + 1}"

    fun findPerson(people: List<Profile>, ref: String): Profile? {
        val text = ref.trim()
        PERSON.matchEntire(text.lowercase())?.let { m -> return people.getOrNull(m.groupValues[1].toInt() - 1) }
        return people.firstOrNull { it.id == text }
    }

    fun ageOf(person: Profile, today: LocalDate): Int? = person.birthDate?.let {
        try {
            Period.between(LocalDate.parse(it), today).years.takeIf { years -> years >= 0 }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** What a field stands at: `open`, `filled`, `to_confirm`, `signature`, `by_hand` or `already_filled`. */
    fun status(field: FormField): String = when {
        field.kind == FormFieldKind.SIGNATURE -> "signature"
        field.value != null && field.reconfirm && field.reviewState == com.postsaimanager.core.model.ReviewState.UNREVIEWED -> "to_confirm"
        field.value != null -> "filled"
        field.alreadyFilled != null -> "already_filled"
        field.skipped -> "by_hand"
        else -> "open"
    }

    /** The secret token of a sensitive stored value: usable in `fill_field`, never the value itself. */
    fun token(keyId: String): String = "***$keyId"

    /** A field's value as the model may see it: a sensitive one as its token. */
    fun shownValue(field: FormField): String? = field.value?.let { if (FormDataKeys.isSensitive(field.dataKey)) token(field.dataKey!!) else it }

    /** One line per field, short: `f3|Geburtsdatum|p1|Angaben zum Kind|date|options=Ja/Nein|key=birth_date|role=subject|filled=12.03.2019`. */
    fun line(fields: List<FormField>, field: FormField): String = buildList {
        add(fieldAlias(fields, field))
        add(field.labelText)
        add("p${field.page}")
        add(field.section.orEmpty())
        add(field.kind.name.lowercase())
        if (field.options.isNotEmpty()) add("options=" + field.options.joinToString("/"))
        field.dataKey?.let { add("key=$it") }
        field.role?.let { add("role=${it.name.lowercase()}") }
        val status = status(field)
        add(if (status == "filled" || status == "to_confirm") "$status=${shownValue(field)}" else status)
    }.joinToString("|")

    /** The fields that still need an answer, in order (the form's own rule, see [FillProgress.openFields]). */
    fun open(fields: List<FormField>): List<FormField> = FillProgress.openFields(fields)
}
