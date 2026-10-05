package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.data.database.dao.FormFillDao
import com.postsaimanager.core.data.mapper.FormFillMapper
import com.postsaimanager.core.domain.form.FormFieldMerge
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class FormFillRepositoryImpl @Inject constructor(
    private val dao: FormFillDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : FormFillRepository {

    override suspend fun getFill(id: String): FormFill? = withContext(ioDispatcher) {
        dao.getFill(id)?.let(FormFillMapper::toDomain)
    }

    override suspend fun fillForDocument(documentId: String): FormFill? = withContext(ioDispatcher) {
        dao.latestForDocument(documentId)?.let(FormFillMapper::toDomain)
    }

    override fun observeFill(id: String): Flow<FormFill?> =
        dao.observeFill(id).map { it?.let(FormFillMapper::toDomain) }.flowOn(ioDispatcher)

    override fun observeFields(fillId: String): Flow<List<FormField>> =
        dao.observeFields(fillId).map { rows -> rows.map(FormFillMapper::toDomain) }.flowOn(ioDispatcher)

    override suspend fun fields(fillId: String): List<FormField> = withContext(ioDispatcher) {
        dao.getFields(fillId).map(FormFillMapper::toDomain)
    }

    override suspend fun saveFill(fill: FormFill) = withContext(ioDispatcher) {
        dao.upsertFill(FormFillMapper.toEntity(fill))
    }

    override suspend fun saveFields(fillId: String, fields: List<FormField>) = withContext(ioDispatcher) {
        val existing = dao.getFields(fillId).map(FormFillMapper::toDomain)
        val merged = FormFieldMerge.keepReviewed(existing, fields)
        dao.replaceFields(fillId, merged.map(FormFillMapper::toEntity))
    }

    override suspend fun deleteFields(fillId: String) = withContext(ioDispatcher) {
        dao.deleteFields(fillId)
    }

    override suspend fun setValue(
        fieldId: String,
        value: String?,
        source: FormValueSource,
        reviewState: ReviewState,
        profileId: String?,
        nowMs: Long,
    ) = withContext(ioDispatcher) {
        val field = dao.getField(fieldId) ?: return@withContext
        dao.upsertFields(
            listOf(
                field.copy(
                    value = value,
                    valueSource = if (value == null) FormValueSource.NONE.name else source.name,
                    profileId = profileId,
                    reviewState = reviewState.name,
                    reconfirm = false,
                    skipped = false,
                    updatedAt = nowMs,
                ),
            ),
        )
    }

    override suspend fun setSkipped(fieldId: String, skipped: Boolean, nowMs: Long) = withContext(ioDispatcher) {
        val field = dao.getField(fieldId) ?: return@withContext
        dao.upsertFields(listOf(field.copy(skipped = skipped, updatedAt = nowMs)))
    }

    override suspend fun clearValues(fillId: String, nowMs: Long) = withContext(ioDispatcher) {
        dao.upsertFields(
            dao.getFields(fillId).map {
                it.copy(
                    value = null,
                    valueSource = FormValueSource.NONE.name,
                    profileId = null,
                    reviewState = ReviewState.UNREVIEWED.name,
                    reconfirm = false,
                    skipped = false,
                    updatedAt = nowMs,
                )
            },
        )
    }
}
