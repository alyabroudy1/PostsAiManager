package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for profile operations. A profile has a [ProfileKind] and, when it belongs to the household, a
 * [HouseholdRole]; at most one profile is [HouseholdRole.SELF] ("Me"), and create and update refuse a second one.
 */
interface ProfileRepository {
    fun getProfiles(): Flow<List<Profile>>
    fun getProfilesByKind(kind: ProfileKind): Flow<List<Profile>>
    fun getProfilesByRole(role: HouseholdRole): Flow<List<Profile>>
    fun getProfilesForDocument(documentId: String): Flow<List<Pair<Profile, ProfileRole>>>
    fun searchProfiles(query: String): Flow<List<Profile>>
    suspend fun getProfileById(id: String): PamResult<Profile>
    suspend fun createProfile(profile: Profile): PamResult<Profile>
    suspend fun updateProfile(profile: Profile): PamResult<Unit>
    suspend fun deleteProfile(id: String): PamResult<Unit>

    /**
     * Folds [mergedId] into [keepId]: its document links, contacts, events, matters, suggestions, notes and references move to
     * [keepId] (where [keepId] already has the same link or suggestion, its own stays), then [mergedId] is deleted WITHOUT a
     * tombstone (the entity it came from is the one [keepId] stands for).
     */
    suspend fun mergeProfiles(keepId: String, mergedId: String): PamResult<Unit>

    suspend fun linkProfileToDocument(
        profileId: String,
        documentId: String,
        role: ProfileRole,
    ): PamResult<Unit>
    suspend fun unlinkProfileFromDocument(profileId: String, documentId: String): PamResult<Unit>
    suspend fun findSimilarProfiles(name: String, organization: String?): PamResult<List<Profile>>
}
