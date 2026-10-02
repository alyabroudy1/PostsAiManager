package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormDataKey
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueKind
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.ReviewState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * What a fill is made for.
 *
 * @property roleProfiles the profile chosen for each role (a role missing here is not filled).
 * @property confirmedRoles the roles the user confirmed (the subject in "Who is this form for?"); a sensitive value is only
 *   written for a confirmed role, never for one the app inferred.
 * @property locale the form's language (dates are written in its short format).
 * @property countryIso2 the form's country for the address order, when known.
 */
data class FillContext(
    val roleProfiles: Map<FormRole, String>,
    val confirmedRoles: Set<FormRole>,
    val locale: Locale,
    val countryIso2: String? = null,
    /** "Me": the person whose city answers a "place of signing" field; null when unknown (the field is then asked). */
    val todayPlaceProfileId: String? = null,
    /** Whose address a person without one of their own shares, in order (the guardian, then "Me"). */
    val addressFallbacks: List<String> = emptyList(),
    val nowMs: Long = System.currentTimeMillis(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** The fields after filling, and the ids of those whose value is old enough to be asked about again. */
data class FillResult(val fields: List<FormField>, val needsReconfirm: Set<String>)

/**
 * Fills a form's fields from people's stored details: CODE ONLY. Its only inputs are the fields, the [PersonDataSource] and the
 * [FillContext]: nothing here can reach the model, so a value can only be one a person stored, copied and formatted.
 *
 * A field is filled when its data key and its role are known, the role has a profile, and that profile holds a value for the key.
 * - Dates are written in the form's locale (short pattern, four-digit year); the address as one line in the country's order;
 *   an IBAN in groups of four.
 * - A choice is filled only when the stored value is exactly one of the printed options (the option as printed).
 * - A value older than the key's `reconfirmAfterMonths` is filled and flagged in [FillResult.needsReconfirm].
 * - A sensitive value is never written for a role the user did not confirm.
 * - Signatures, boxes without options, fields already filled by hand and fields a person reviewed or typed are left alone.
 */
class FillValues(
    private val source: PersonDataSource,
    private val addresses: AddressComposer = AddressComposer(),
) {

    suspend fun fill(fields: List<FormField>, context: FillContext): FillResult {
        val stored = HashMap<String, Map<String, PersonValue>>()
        val reconfirm = LinkedHashSet<String>()
        val filled = fields.map { field ->
            val profileId = field.role?.let(context.roleProfiles::get)
            val key = FormDataKeys.of(field.dataKey)
            if (key != null && fillable(field)) {
                today(key, context)?.let { return@map field.copy(value = it, valueSource = FormValueSource.TODAY) }
            }
            if (profileId == null || key == null || !fillable(field)) return@map field
            if (key.sensitive && field.role !in context.confirmedRoles) return@map field
            val facts = stored.getOrPut(profileId) { source.allOf(profileId) }
            var owner = profileId
            var found = lookup(key, facts, context)
            if (found == null && key.id in HOUSEHOLD_KEYS) {
                // The person has no address of their own: the household's is used (the guardian's, then "Me"), whole or not at all.
                for (other in context.addressFallbacks.filter { it != profileId }) {
                    val theirs = stored.getOrPut(other) { source.allOf(other) }
                    found = lookup(key, theirs, context)
                    if (found != null) {
                        owner = other
                        break
                    }
                }
            }
            val text = found?.let { write(key.valueKind, it.value, field, context) }
            if (found == null || text == null) return@map field
            if (stale(key.reconfirmAfterMonths, found.updatedAt, context)) reconfirm += field.id
            field.copy(value = text, valueSource = found.source, profileId = owner)
        }
        return FillResult(filled, reconfirm)
    }

    /**
     * The stored value of [key], or the one derived from stored ones: the address composed from its parts, the given or family name
     * from the full name (the family name is the last word, the given name the rest: a split of the stored text, not a reading of it).
     */
    private fun lookup(key: FormDataKey, facts: Map<String, PersonValue>, context: FillContext): PersonValue? {
        facts[key.id]?.let { return it }
        return when (key.id) {
            FormDataKeys.ADDRESS.id -> addresses.compose(facts, context.countryIso2)
            FormDataKeys.GIVEN_NAME.id, FormDataKeys.FAMILY_NAME.id -> facts[FormDataKeys.FULL_NAME.id]?.let { full ->
                val name = full.value.trim()
                val at = name.lastIndexOf(' ')
                val part = if (at <= 0) null else if (key.id == FormDataKeys.GIVEN_NAME.id) name.substring(0, at) else name.substring(at + 1)
                part?.trim()?.takeIf { it.isNotEmpty() }?.let { full.copy(value = it) }
            }
            else -> null
        }
    }

    /** The value of a "today" key: the date of filling in (the form's format) or the "Me" city; null for any other key or when unknown. */
    private suspend fun today(key: FormDataKey, context: FillContext): String? = when (key.id) {
        FormDataKeys.TODAY_DATE.id ->
            FormValueFormatter.date(Instant.ofEpochMilli(context.nowMs).atZone(context.zone).toLocalDate(), context.locale)
        FormDataKeys.TODAY_PLACE.id ->
            context.todayPlaceProfileId?.let { source.valueOf(it, FormDataKeys.CITY.id)?.value }
        else -> null
    }

    private fun fillable(f: FormField) =
        f.kind != FormFieldKind.SIGNATURE && !(f.kind == FormFieldKind.CHECKBOX && f.options.isEmpty()) &&
            f.alreadyFilled == null && f.reviewState == ReviewState.UNREVIEWED && f.value == null

    /** The stored [value] as written on this field; null when it cannot be (a choice whose options do not include it). */
    private fun write(kind: FormValueKind, value: String, field: FormField, context: FillContext): String? {
        if (field.options.isNotEmpty()) {
            return (AnswerVerifiers.verifyChoice(value, field.options) as? Verification.Accepted)?.value
        }
        return when (kind) {
            FormValueKind.DATE -> FormValueFormatter.date(value, context.locale)
            FormValueKind.IBAN -> FormValueFormatter.iban(value)
            else -> value
        }
    }

    private companion object {
        /** The keys of a postal address: shared by a household. */
        val HOUSEHOLD_KEYS: Set<String> = setOf(
            FormDataKeys.ADDRESS.id, FormDataKeys.STREET.id, FormDataKeys.POSTCODE.id, FormDataKeys.CITY.id, FormDataKeys.COUNTRY.id,
        )
    }

    private fun stale(months: Int?, updatedAt: Long, context: FillContext): Boolean {
        if (months == null) return false
        val due = Instant.ofEpochMilli(updatedAt).atZone(context.zone).plusMonths(months.toLong())
        return due.toInstant().toEpochMilli() < context.nowMs
    }
}
