package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.data.database.dao.ProfileSuggestionDao
import com.postsaimanager.core.data.database.entity.ProfileSuggestionEntity
import com.postsaimanager.core.domain.repository.ProfileSuggestionRepository
import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.SuggestionField
import com.postsaimanager.core.model.SuggestionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Room implementation of [ProfileSuggestionRepository]. */
class ProfileSuggestionRepositoryImpl @Inject constructor(
    private val dao: ProfileSuggestionDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ProfileSuggestionRepository {

    override fun observePending(profileId: String): Flow<List<ProfileSuggestion>> =
        dao.observePending(profileId).map { rows -> rows.mapNotNull(::toDomain) }.flowOn(ioDispatcher)

    override suspend fun all(profileId: String): List<ProfileSuggestion> =
        withContext(ioDispatcher) { dao.getAll(profileId).mapNotNull(::toDomain) }

    override suspend fun get(id: String): ProfileSuggestion? = withContext(ioDispatcher) { dao.getById(id)?.let(::toDomain) }

    override suspend fun offer(suggestion: ProfileSuggestion) = withContext(ioDispatcher) { dao.insert(toEntity(suggestion)) }

    override suspend fun dismiss(id: String) = withContext(ioDispatcher) { dao.setStatus(id, SuggestionStatus.DISMISSED.name) }

    override suspend fun clearPending(profileId: String, field: SuggestionField) =
        withContext(ioDispatcher) { dao.deletePending(profileId, field.name) }

    private fun toDomain(entity: ProfileSuggestionEntity): ProfileSuggestion? {
        val field = SuggestionField.entries.firstOrNull { it.name == entity.field } ?: return null
        return ProfileSuggestion(
            id = entity.id, profileId = entity.profileId, field = field, value = entity.value, sourceDocumentId = entity.sourceDocumentId,
            createdAt = entity.createdAt, status = SuggestionStatus.entries.firstOrNull { it.name == entity.status } ?: SuggestionStatus.PENDING,
        )
    }

    private fun toEntity(s: ProfileSuggestion) = ProfileSuggestionEntity(
        id = s.id, profileId = s.profileId, field = s.field.name, value = s.value, sourceDocumentId = s.sourceDocumentId,
        createdAt = s.createdAt, status = s.status.name,
    )
}
