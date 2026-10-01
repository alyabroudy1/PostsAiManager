package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.form.FormFieldMerge
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [FormFillRepository] for tests; merges re-runs through the same [FormFieldMerge] the real one uses. */
class FakeFormFillRepository : FormFillRepository {

    private val fills = MutableStateFlow<List<FormFill>>(emptyList())
    private val allFields = MutableStateFlow<List<FormField>>(emptyList())

    /** Every [saveFill] in order, so a test can follow the state machine. */
    val savedFills = mutableListOf<FormFill>()

    override suspend fun getFill(id: String): FormFill? = fills.value.firstOrNull { it.id == id }

    override suspend fun fillForDocument(documentId: String): FormFill? =
        fills.value.filter { it.documentId == documentId }.maxByOrNull { it.createdAt }

    override fun observeFill(id: String): Flow<FormFill?> = fills.map { list -> list.firstOrNull { it.id == id } }

    override fun observeFields(fillId: String): Flow<List<FormField>> = allFields.map { list -> sorted(list, fillId) }

    override suspend fun fields(fillId: String): List<FormField> = sorted(allFields.value, fillId)

    override suspend fun saveFill(fill: FormFill) {
        savedFills += fill
        fills.value = fills.value.filterNot { it.id == fill.id } + fill
    }

    override suspend fun saveFields(fillId: String, fields: List<FormField>) {
        val merged = FormFieldMerge.keepReviewed(sorted(allFields.value, fillId), fields)
        allFields.value = allFields.value.filterNot { it.formFillId == fillId } + merged
    }

    override suspend fun setValue(
        fieldId: String,
        value: String?,
        source: FormValueSource,
        reviewState: ReviewState,
        profileId: String?,
        nowMs: Long,
    ) = update(fieldId) {
        it.copy(
            value = value,
            valueSource = if (value == null) FormValueSource.NONE else source,
            profileId = profileId,
            reviewState = reviewState,
            reconfirm = false,
            skipped = false,
            updatedAt = nowMs,
        )
    }

    override suspend fun setSkipped(fieldId: String, skipped: Boolean, nowMs: Long) =
        update(fieldId) { it.copy(skipped = skipped, updatedAt = nowMs) }

    override suspend fun clearValues(fillId: String, nowMs: Long) {
        allFields.value = allFields.value.map {
            if (it.formFillId != fillId) it
            else it.copy(
                value = null, valueSource = FormValueSource.NONE, profileId = null, reviewState = ReviewState.UNREVIEWED,
                reconfirm = false, skipped = false, updatedAt = nowMs,
            )
        }
    }

    private fun update(fieldId: String, change: (FormField) -> FormField) {
        allFields.value = allFields.value.map { if (it.id == fieldId) change(it) else it }
    }

    private fun sorted(list: List<FormField>, fillId: String) =
        list.filter { it.formFillId == fillId }.sortedWith(compareBy({ it.page }, { it.orderIndex }))
}
