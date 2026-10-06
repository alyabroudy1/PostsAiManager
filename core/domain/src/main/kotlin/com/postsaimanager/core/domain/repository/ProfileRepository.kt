package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.DocumentProfileLink
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
    /** Every stored document-profile link in one batch, so a list can name people without a query per row. */
    fun observeDocumentLinks(): Flow<List<DocumentProfileLink>>
    /**
     * Stores the model's decision of who [documentId] is for or about: for every profile in [evaluated] a CONCERNS link when it is in
     * [concerned], and none when it is not (a CONCERNS link left by an earlier decision is removed; any other kind of link is kept).
     */
    suspend fun replaceConcernedLinks(documentId: String, evaluated: Set<String>, concerned: Set<String>): PamResult<Unit>
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
