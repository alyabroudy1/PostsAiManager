package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueKind
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
            if (profileId == null || key == null || !fillable(field)) return@map field
            if (key.sensitive && field.role !in context.confirmedRoles) return@map field
            val facts = stored.getOrPut(profileId) { source.allOf(profileId) }
            val found = facts[key.id] ?: if (key.valueKind == FormValueKind.ADDRESS) addresses.compose(facts, context.countryIso2) else null
            val text = found?.let { write(key.valueKind, it.value, field, context) }
            if (found == null || text == null) return@map field
            if (stale(key.reconfirmAfterMonths, found.updatedAt, context)) reconfirm += field.id
            field.copy(value = text, valueSource = found.source, profileId = profileId)
        }
        return FillResult(filled, reconfirm)
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

    private fun stale(months: Int?, updatedAt: Long, context: FillContext): Boolean {
        if (months == null) return false
        val due = Instant.ofEpochMilli(updatedAt).atZone(context.zone).plusMonths(months.toLong())
        return due.toInstant().toEpochMilli() < context.nowMs
    }
}
