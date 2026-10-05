package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.ProfileFactDao
import com.postsaimanager.core.data.database.entity.ProfileFactEntity
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.repository.ProfileFactRepository
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.ProfileFact
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class ProfileFactRepositoryImpl @Inject constructor(
    private val dao: ProfileFactDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ProfileFactRepository {

    override fun observeFacts(profileId: String): Flow<List<ProfileFact>> =
        dao.observeByProfile(profileId).map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override suspend fun facts(profileId: String): List<ProfileFact> = withContext(ioDispatcher) {
        dao.getByProfile(profileId).map(::toDomain)
    }

    override suspend fun upsert(
        profileId: String,
        key: String,
        value: String,
        source: FactSource,
        sourceDocumentId: String?,
    ): PamResult<ProfileFact> = withContext(ioDispatcher) {
        try {
            val now = System.currentTimeMillis()
            val existing = dao.get(profileId, key)
            val entity = ProfileFactEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                profileId = profileId,
                key = key,
                value = value,
                source = source.name,
                sourceDocumentId = sourceDocumentId,
                sensitive = FormDataKeys.isSensitive(key),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            dao.upsert(entity)
            PamResult.Success(toDomain(entity))
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    override suspend fun delete(profileId: String, key: String): PamResult<Unit> = withContext(ioDispatcher) {
        try {
            dao.delete(profileId, key)
            PamResult.Success(Unit)
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    private fun toDomain(entity: ProfileFactEntity) = ProfileFact(
        id = entity.id, profileId = entity.profileId, key = entity.key, value = entity.value,
        source = FactSource.entries.firstOrNull { it.name == entity.source } ?: FactSource.USER,
        sourceDocumentId = entity.sourceDocumentId, sensitive = entity.sensitive,
        createdAt = entity.createdAt, updatedAt = entity.updatedAt,
    )
}
