package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.ProfileDao
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.data.database.entity.DocumentProfileLinkEntity
import com.postsaimanager.core.data.database.entity.ProfileEntity
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.CustomDetails
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.Relationship
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ProfileRepositoryImpl @Inject constructor(
    private val profileDao: ProfileDao,
    private val dismissedEntityDao: DismissedEntityDao,
    private val documentDao: DocumentDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ProfileRepository {

    override fun getProfiles(): Flow<List<Profile>> =
        profileDao.observeAll().map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override fun getProfilesByKind(kind: ProfileKind): Flow<List<Profile>> =
        profileDao.observeByKind(kind.name).map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override fun getProfilesByRole(role: HouseholdRole): Flow<List<Profile>> =
        profileDao.observeByRole(role.name).map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override fun getProfilesForDocument(documentId: String): Flow<List<Pair<Profile, ProfileRole>>> =
        profileDao.observeProfilesForDocument(documentId).map { list ->
            list.map { pwr ->
                Pair(
                    Profile(
                        id = pwr.id, kind = kindOf(pwr.kind), householdRole = roleOf(pwr.householdRole), name = pwr.name,
                        organization = pwr.organization, department = pwr.department,
                        street = pwr.street, city = pwr.city, postalCode = pwr.postalCode,
                        country = pwr.country, phone = pwr.phone, email = pwr.email,
                        website = pwr.website, reference = pwr.reference, notes = pwr.notes,
                        completionScore = pwr.completionScore, avatarPath = pwr.avatarPath,
                        relationship = relationshipOf(pwr.relationship), birthDate = pwr.birthDate,
                        sensitive = pwr.sensitive, customDetails = CustomDetails.decode(pwr.customDetails),
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
            secondSelfError(profile)?.let { return@withContext PamResult.Error(it) }
            profileDao.insert(toEntity(profile))
            PamResult.Success(profile)
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    override suspend fun updateProfile(profile: Profile): PamResult<Unit> = withContext(ioDispatcher) {
        try {
            secondSelfError(profile)?.let { return@withContext PamResult.Error(it) }
            profileDao.update(toEntity(profile))
            PamResult.Success(Unit)
        } catch (e: Exception) { PamResult.Error(PamError.DatabaseError(cause = e)) }
    }

    override suspend fun deleteProfile(id: String): PamResult<Unit> = withContext(ioDispatcher) {
        try {
            // A profile the AI created carries where it came from. Tombstoning that origin
            // before the row is gone is what stops the next reprocess of the same document
            // from silently recreating exactly what the user just removed — see
            // DismissedEntityEntity. A profile the user created by hand has no origin, so
            // there is nothing to tombstone: nothing machine-driven can bring it back anyway.
            val entity = profileDao.getById(id)
            val documentId = entity?.sourceDocumentId
            val entityName = entity?.sourceEntityName
            if (documentId != null && entityName != null) {
                dismissedEntityDao.dismiss(
                    DismissedEntityEntity(documentId, entityName, System.currentTimeMillis()),
                )
            }
            profileDao.deleteById(id)
            // The links cascade with the row; the documents' decision of who they concern is a JSON list, so the id leaves it here,
            // in one transaction, whichever screen or use case deleted the profile.
            documentDao.removeConcernedProfile(id)
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

    /** "Me" ([HouseholdRole.SELF]) is unique: a second one is refused rather than silently demoting the first. */
    private suspend fun secondSelfError(profile: Profile): PamError? =
        if (profile.isSelf && profileDao.findOtherSelfId(profile.id) != null) {
            PamError.ValidationError("householdRole", "there is already a \"Me\" profile")
        } else {
            null
        }

    private fun relationshipOf(name: String?): Relationship? =
        Relationship.entries.firstOrNull { it.name == name }

    private fun kindOf(name: String): ProfileKind = ProfileKind.entries.firstOrNull { it.name == name } ?: ProfileKind.PERSON

    private fun roleOf(name: String?): HouseholdRole? = HouseholdRole.entries.firstOrNull { it.name == name }

    private fun toDomain(entity: ProfileEntity) = Profile(
        id = entity.id, kind = kindOf(entity.kind), householdRole = roleOf(entity.householdRole), name = entity.name,
        organization = entity.organization, department = entity.department,
        street = entity.street, city = entity.city, postalCode = entity.postalCode,
        country = entity.country, phone = entity.phone, email = entity.email,
        website = entity.website, reference = entity.reference, notes = entity.notes,
        completionScore = entity.completionScore, avatarPath = entity.avatarPath,
        sourceDocumentId = entity.sourceDocumentId, sourceEntityName = entity.sourceEntityName,
        relationship = relationshipOf(entity.relationship), birthDate = entity.birthDate,
        sensitive = entity.sensitive,
        customDetails = CustomDetails.decode(entity.customDetails),
        createdAt = entity.createdAt, modifiedAt = entity.modifiedAt,
    )

    private fun toEntity(profile: Profile) = ProfileEntity(
        // `type` is still written (derived 1:1) for one version, so a build that reads it keeps working.
        id = profile.id, type = profile.type.name, kind = profile.kind.name, householdRole = profile.householdRole?.name,
        name = profile.name,
        organization = profile.organization, department = profile.department,
        street = profile.street, city = profile.city, postalCode = profile.postalCode,
        country = profile.country, phone = profile.phone, email = profile.email,
        website = profile.website, reference = profile.reference, notes = profile.notes,
        completionScore = profile.completionScore, missingFields = null,
        avatarPath = profile.avatarPath,
        sourceDocumentId = profile.sourceDocumentId, sourceEntityName = profile.sourceEntityName,
        relationship = profile.relationship?.name, birthDate = profile.birthDate,
        sensitive = profile.sensitive,
        customDetails = CustomDetails.encode(profile.customDetails),
        createdAt = profile.createdAt, modifiedAt = profile.modifiedAt,
    )
}
