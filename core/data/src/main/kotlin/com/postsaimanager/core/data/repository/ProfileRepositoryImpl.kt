package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.ProfileDao
import com.postsaimanager.core.data.database.entity.DocumentProfileLinkEntity
import com.postsaimanager.core.data.database.entity.ProfileEntity
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ProfileRepositoryImpl @Inject constructor(
    private val profileDao: ProfileDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ProfileRepository {

    override fun getProfiles(): Flow<List<Profile>> =
        profileDao.observeAll().map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override fun getProfilesByType(type: ProfileType): Flow<List<Profile>> =
        profileDao.observeByType(type.name).map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override fun getProfilesForDocument(documentId: String): Flow<List<Pair<Profile, ProfileRole>>> =
        profileDao.observeProfilesForDocument(documentId).map { list ->
            list.map { pwr ->
                Pair(
                    Profile(
                        id = pwr.id, type = ProfileType.valueOf(pwr.type), name = pwr.name,
                        organization = pwr.organization, department = pwr.department,
                        street = pwr.street, city = pwr.city, postalCode = pwr.postalCode,
                        country = pwr.country, phone = pwr.phone, email = pwr.email,
                        website = pwr.website, reference = pwr.reference, notes = pwr.notes,
                        completionScore = pwr.completionScore, avatarPath = pwr.avatarPath,
                        createdAt = pwr.createdAt, modifiedAt = pwr.modifiedAt,
                    ),
                    ProfileRole.valueOf(pwr.role),
                )
            }
        }.flowOn(ioDispatcher)

    override fun searchProfiles(query: String): Flow<List<Profile>> =
        profileDao.search(query).map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override suspend fun getProfileById(id: String): PamResult<Profile> = withContext(ioDispatcher) {
        try {
            val entity = profileDao.getById(id)
            if (entity != null) PamResult.Success(toDomain(entity))
            else PamResult.Error(PamError.FileNotFound(path = id))
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    override suspend fun createProfile(profile: Profile): PamResult<Profile> = withContext(ioDispatcher) {
        try {
            profileDao.insert(toEntity(profile))
            PamResult.Success(profile)
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    override suspend fun updateProfile(profile: Profile): PamResult<Unit> = withContext(ioDispatcher) {
        try {
            profileDao.update(toEntity(profile))
            PamResult.Success(Unit)
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    override suspend fun deleteProfile(id: String): PamResult<Unit> = withContext(ioDispatcher) {
        try {
            profileDao.deleteById(id)
            PamResult.Success(Unit)
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    override suspend fun linkProfileToDocument(
        profileId: String, documentId: String, role: ProfileRole,
    ): PamResult<Unit> = withContext(ioDispatcher) {
        try {
            profileDao.insertLink(
                DocumentProfileLinkEntity(documentId, profileId, role.name, System.currentTimeMillis())
            )
            PamResult.Success(Unit)
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    override suspend fun unlinkProfileFromDocument(profileId: String, documentId: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                profileDao.deleteLink(documentId, profileId)
                PamResult.Success(Unit)
            } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
        }

    override suspend fun findSimilarProfiles(name: String, organization: String?): PamResult<List<Profile>> =
        withContext(ioDispatcher) {
            try {
                PamResult.Success(profileDao.findSimilar(name, organization).map(::toDomain))
            } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
        }

    private fun toDomain(entity: ProfileEntity) = Profile(
        id = entity.id, type = ProfileType.valueOf(entity.type), name = entity.name,
        organization = entity.organization, department = entity.department,
        street = entity.street, city = entity.city, postalCode = entity.postalCode,
        country = entity.country, phone = entity.phone, email = entity.email,
        website = entity.website, reference = entity.reference, notes = entity.notes,
        completionScore = entity.completionScore, avatarPath = entity.avatarPath,
        createdAt = entity.createdAt, modifiedAt = entity.modifiedAt,
    )

    private fun toEntity(profile: Profile) = ProfileEntity(
        id = profile.id, type = profile.type.name, name = profile.name,
        organization = profile.organization, department = profile.department,
        street = profile.street, city = profile.city, postalCode = profile.postalCode,
        country = profile.country, phone = profile.phone, email = profile.email,
        website = profile.website, reference = profile.reference, notes = profile.notes,
        completionScore = profile.completionScore, missingFields = null,
        avatarPath = profile.avatarPath, createdAt = profile.createdAt, modifiedAt = profile.modifiedAt,
    )
}
