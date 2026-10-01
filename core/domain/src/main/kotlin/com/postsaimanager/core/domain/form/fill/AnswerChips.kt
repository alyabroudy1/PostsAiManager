package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.PersonDataSource
import com.postsaimanager.core.model.CheckboxValue
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormValueKind

/** How a sensitive value is shown before the user reveals it: bullets and its last four characters. */
object FormMask {
    fun of(value: String): String = "••••" + value.filter(Char::isLetterOrDigit).takeLast(4)
}

/**
 * The answer chips under a question, every one a value that already exists: an option printed on the form, yes or no for a tick
 * box, a value the person has stored (two phone numbers), the stored value to confirm, or the parts of the person's full name.
 * Nothing here comes from the model.
 */
class AnswerChips(
    private val people: PersonDataSource,
    private val profile: FormFillProfile = FormFillProfile(),
) {

    /** The chips for [field], whose role's person is [personId] (null when no profile answers for it). */
    suspend fun forField(field: FormField, personId: String?): List<FormChip> {
        field.value?.takeIf { field.reconfirm }?.let { stored ->
            val shown = if (FormDataKeys.isSensitive(field.dataKey)) FormMask.of(stored) else stored
            return listOf(FormChip(FormChipAction.ANSWER, label = shown, arg = stored))
        }
        if (field.options.isNotEmpty()) return field.options.take(profile.maxChips).map(::answer)
        if (field.kind == FormFieldKind.CHECKBOX) {
            return listOf(
                FormChip(FormChipAction.ANSWER, labelCode = FormChipLabel.YES, arg = CheckboxValue.YES),
                FormChip(FormChipAction.ANSWER, labelCode = FormChipLabel.NO, arg = CheckboxValue.NO),
            )
        }
        if (personId == null) return emptyList()
        val key = FormDataKeys.of(field.dataKey) ?: return emptyList()
        return when {
            key.id == FormDataKeys.GIVEN_NAME.id || key.id == FormDataKeys.FAMILY_NAME.id -> nameParts(key.id, personId)
            key.valueKind == FormValueKind.PHONE || key.valueKind == FormValueKind.EMAIL -> sameKindValues(key.valueKind, personId)
            else -> emptyList()
        }
    }

    /** The given name (before the last space) or the family name (after it) of the person's full name, for the user to confirm. */
    private suspend fun nameParts(keyId: String, personId: String): List<FormChip> {
        val full = people.valueOf(personId, FormDataKeys.FULL_NAME.id)?.value?.trim() ?: return emptyList()
        val at = full.lastIndexOf(' ')
        if (at <= 0) return emptyList()
        val part = if (keyId == FormDataKeys.GIVEN_NAME.id) full.substring(0, at) else full.substring(at + 1)
        return listOf(answer(part.trim()))
    }

    /** Every distinct value the person stores under a key of the same kind (their phone and their mobile). */
    private suspend fun sameKindValues(kind: FormValueKind, personId: String): List<FormChip> {
        val stored = people.allOf(personId)
        return FormDataKeys.ALL.filter { it.valueKind == kind }
            .mapNotNull { stored[it.id]?.value }
            .distinct()
            .take(profile.maxChips)
            .map(::answer)
    }

    private fun answer(value: String) = FormChip(FormChipAction.ANSWER, label = value, arg = value)
}
