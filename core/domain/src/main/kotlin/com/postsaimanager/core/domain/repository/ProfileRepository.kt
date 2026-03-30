package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for profile operations.
 */
interface ProfileRepository {
    fun getProfiles(): Flow<List<Profile>>
    fun getProfilesByType(type: ProfileType): Flow<List<Profile>>
    fun getProfilesForDocument(documentId: String): Flow<List<Pair<Profile, ProfileRole>>>
    fun searchProfiles(query: String): Flow<List<Profile>>
    suspend fun getProfileById(id: String): PamResult<Profile>
    suspend fun createProfile(profile: Profile): PamResult<Profile>
    suspend fun updateProfile(profile: Profile): PamResult<Unit>
    suspend fun deleteProfile(id: String): PamResult<Unit>
    suspend fun linkProfileToDocument(
        profileId: String,
        documentId: String,
        role: ProfileRole,
    ): PamResult<Unit>
    suspend fun unlinkProfileFromDocument(profileId: String, documentId: String): PamResult<Unit>
    suspend fun findSimilarProfiles(name: String, organization: String?): PamResult<List<Profile>>
}
