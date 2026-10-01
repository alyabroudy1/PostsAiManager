package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.flow.Flow

/**
 * The persisted state of the form-filling conversation: one [FormFill] per document and its [FormField]s. The conversation
 * reads and writes only through this port, so it survives process death and the chat's fill card re-renders from the fields.
 *
 * Reviewed means a person answered, confirmed or skipped the field: a re-run ([saveFields]) never overwrites it
 * (ReviewState semantics, see [com.postsaimanager.core.domain.form.FormFieldMerge]).
 */
interface FormFillRepository {

    suspend fun getFill(id: String): FormFill?

    /** The document's fill, or null when it was never started. */
    suspend fun fillForDocument(documentId: String): FormFill?

    fun observeFill(id: String): Flow<FormFill?>

    /** The fields of [fillId] in page and reading order. */
    fun observeFields(fillId: String): Flow<List<FormField>>

    suspend fun fields(fillId: String): List<FormField>

    /** Inserts or replaces the fill (its status, roles, current field, what it waits for). */
    suspend fun saveFill(fill: FormFill)

    /** Stores [fields] as the fields of [fillId]; a field a person reviewed keeps its value, source and state. */
    suspend fun saveFields(fillId: String, fields: List<FormField>)

    /** Deletes every field of [fillId], reviewed or not (the reading they came from is out of date). */
    suspend fun deleteFields(fillId: String)

    /**
     * Writes a value a person gave or confirmed ([reviewState] EDITED or CONFIRMED, source USER or the field's own),
     * clearing "skipped" and "reconfirm". A null [value] empties the field.
     */
    suspend fun setValue(
        fieldId: String,
        value: String?,
        source: FormValueSource,
        reviewState: ReviewState,
        profileId: String?,
        nowMs: Long,
    )

    /** Marks the field as left for the user to write by hand (or takes the mark back). */
    suspend fun setSkipped(fieldId: String, skipped: Boolean, nowMs: Long)

    /** Empties every field's value and review state (the answers were about another person). */
    suspend fun clearValues(fillId: String, nowMs: Long)
}
