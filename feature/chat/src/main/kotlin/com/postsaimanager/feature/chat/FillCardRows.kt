package com.postsaimanager.feature.chat

import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.fill.FormMask
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.ReviewState

/** What a fill card row says about its field. */
internal enum class FillRowStatus {
    /** Has a value. */
    READY,

    /** Has a stored value that waits for "still right?". */
    TO_CONFIRM,

    /** A signature: the user signs on paper. */
    SIGNATURE,

    /** The user chose to write it by hand. */
    BY_HAND,

    /** Was already filled in by hand on the form. */
    ALREADY_FILLED,

    /** Nothing yet: the conversation asks about it. */
    NEEDS_INPUT,
}

/** One row of the fill card, computed from a stored field. */
internal data class FillRow(val field: FormField, val status: FillRowStatus, val sensitive: Boolean) {
    /** What is shown before the user taps to reveal a sensitive value. */
    val masked: String? get() = this.field.value?.takeIf { sensitive }?.let(FormMask::of)
}

internal object FillCardRows {

    fun of(fields: List<FormField>): List<FillRow> = fields.sortedWith(compareBy({ it.page }, { it.orderIndex })).map(::row)

    fun row(field: FormField): FillRow {
        val status = when {
            field.kind == FormFieldKind.SIGNATURE -> FillRowStatus.SIGNATURE
            field.alreadyFilled != null && field.value == null -> FillRowStatus.ALREADY_FILLED
            field.value != null && field.reconfirm && field.reviewState == ReviewState.UNREVIEWED -> FillRowStatus.TO_CONFIRM
            field.value != null -> FillRowStatus.READY
            field.skipped -> FillRowStatus.BY_HAND
            else -> FillRowStatus.NEEDS_INPUT
        }
        return FillRow(field, status, sensitive = FormDataKeys.isSensitive(field.dataKey))
    }

    /** "Label: value" per line for every field that has a value ([shown] turns a stored value into what the user sees). */
    fun plainText(fields: List<FormField>, shown: (FormField) -> String): String =
        of(fields).filter { it.field.value != null }.joinToString("\n") { "${it.field.labelText}: ${shown(it.field)}" }
}
