package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ProfileRepository] for tests.
 *
 * Records link calls so tests can assert on side effects, and lets
 * [similarProfilesOverride] pin exactly what `findSimilarProfiles` returns —
 * the real implementation's ordering is a Room query detail that unit tests
 * must not depend on.
 */
class FakeProfileRepository : ProfileRepository {

    private val profiles = MutableStateFlow<List<Profile>>(emptyList())

    /** Links recorded as (profileId, documentId, role). */
    val links = mutableListOf<Triple<String, String, ProfileRole>>()

    /** Profiles updated via [updateProfile]. */
    val updated = mutableListOf<Profile>()

    /** When set, `findSimilarProfiles` returns this verbatim, ignoring its arguments. */
    var similarProfilesOverride: List<Profile>? = null

    /** When set, every suspend call fails with this error. */
    var failWith: PamError? = null

    fun seed(vararg items: Profile) {
        profiles.value = profiles.value + items
    }

    override fun getProfiles(): Flow<List<Profile>> = profiles

    override fun getProfilesByType(type: ProfileType): Flow<List<Profile>> =
        profiles.map { list -> list.filter { it.type == type } }

    override fun getProfilesForDocument(documentId: String): Flow<List<Pair<Profile, ProfileRole>>> =
        profiles.map { list ->
            links.filter { it.second == documentId }
                .mapNotNull { (pid, _, role) ->
                    list.firstOrNull { it.id == pid }?.let { it to role }
                }
        }

    override fun searchProfiles(query: String): Flow<List<Profile>> =
        profiles.map { list ->
            list.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.organization?.contains(query, ignoreCase = true) == true
            }
        }

    override suspend fun getProfileById(id: String): PamResult<Profile> {
        failWith?.let { return PamResult.Error(it) }
        return profiles.value.firstOrNull { it.id == id }
            ?.let { PamResult.Success(it) }
            ?: PamResult.Error(PamError.FileNotFound("No profile $id"))
    }

    override suspend fun createProfile(profile: Profile): PamResult<Profile> {
        failWith?.let { return PamResult.Error(it) }
        profiles.value = profiles.value + profile
        return PamResult.Success(profile)
    }

    override suspend fun updateProfile(profile: Profile): PamResult<Unit> {
        failWith?.let { return PamResult.Error(it) }
        updated += profile
        profiles.value = profiles.value.map { if (it.id == profile.id) profile else it }
        return PamResult.Success(Unit)
    }

    override suspend fun deleteProfile(id: String): PamResult<Unit> {
        failWith?.let { return PamResult.Error(it) }
        profiles.value = profiles.value.filterNot { it.id == id }
        return PamResult.Success(Unit)
    }

    override suspend fun linkProfileToDocument(
        profileId: String,
        documentId: String,
        role: ProfileRole,
    ): PamResult<Unit> {
        failWith?.let { return PamResult.Error(it) }
        links += Triple(profileId, documentId, role)
        return PamResult.Success(Unit)
    }

    override suspend fun unlinkProfileFromDocument(
        profileId: String,
        documentId: String,
    ): PamResult<Unit> {
        failWith?.let { return PamResult.Error(it) }
        links.removeAll { it.first == profileId && it.second == documentId }
        return PamResult.Success(Unit)
    }

    override suspend fun findSimilarProfiles(
        name: String,
        organization: String?,
    ): PamResult<List<Profile>> {
        failWith?.let { return PamResult.Error(it) }
        similarProfilesOverride?.let { return PamResult.Success(it) }
        return PamResult.Success(
            profiles.value.filter {
                it.name.contains(name, ignoreCase = true) ||
                    name.contains(it.name, ignoreCase = true) ||
                    (organization != null && it.organization?.contains(organization, true) == true)
            },
        )
    }
}

/** Convenience builder for test profiles. */
fun testProfile(
    id: String = "p1",
    name: String = "Test Profile",
    organization: String? = null,
    email: String? = null,
    phone: String? = null,
    street: String? = null,
    type: ProfileType = ProfileType.AUTHORITY,
) = Profile(
    id = id,
    type = type,
    name = name,
    organization = organization,
    email = email,
    phone = phone,
    street = street,
    createdAt = 0L,
    modifiedAt = 0L,
)
