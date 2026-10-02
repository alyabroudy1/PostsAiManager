package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.ReviewState

/**
 * The one owner of "a field a person reviewed is never overwritten by a re-run" for form fields: the repository and its test
 * double both merge through it.
 */
object FormFieldMerge {

    /** Whether a person has answered, confirmed or skipped [field]. */
    fun isReviewed(field: FormField): Boolean = field.reviewState != ReviewState.UNREVIEWED || field.skipped

    /** [incoming] in its own order, where each field a person reviewed in [existing] keeps its stored state. */
    fun keepReviewed(existing: List<FormField>, incoming: List<FormField>): List<FormField> {
        val byId = existing.associateBy { it.id }
        return incoming.map { field -> byId[field.id]?.takeIf(::isReviewed) ?: field }
    }
}
