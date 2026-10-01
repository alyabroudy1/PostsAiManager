package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.ReviewState

/**
 * Where a fill stands, counted from its fields: "8 of 11 ready, 2 need you, 1 signature". [ready] counts fields that have a value
 * (or were already filled by hand); a value still waiting to be confirmed ("still right?") counts as ready, it is filled.
 */
data class FillProgress(val total: Int, val ready: Int, val needYou: Int, val signatures: Int, val firstSignaturePage: Int?) {

    companion object {
        fun of(fields: List<FormField>): FillProgress {
            val signatures = fields.filter { it.kind == FormFieldKind.SIGNATURE }
            val rest = fields - signatures.toSet()
            val ready = rest.count { it.value != null || it.alreadyFilled != null }
            return FillProgress(
                total = fields.size,
                ready = ready,
                needYou = rest.size - ready,
                signatures = signatures.size,
                firstSignaturePage = signatures.minOfOrNull { it.page },
            )
        }

        /**
         * The fields the conversation still has to ask about, in page and reading order: not a signature, not already filled by
         * hand, not skipped, and either empty or a stored value that waits to be confirmed. Signatures are never asked (the
         * user signs on paper), which also puts them last in every list.
         */
        fun openFields(fields: List<FormField>): List<FormField> = fields
            .filter { f ->
                f.kind != FormFieldKind.SIGNATURE && f.alreadyFilled == null && !f.skipped &&
                    (f.value == null || (f.reconfirm && f.reviewState == ReviewState.UNREVIEWED))
            }
            .sortedWith(compareBy({ it.page }, { it.orderIndex }))
    }
}
