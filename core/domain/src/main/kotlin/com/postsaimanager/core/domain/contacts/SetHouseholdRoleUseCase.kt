package com.postsaimanager.core.domain.contacts

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.Relationship
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The one place a profile joins or leaves the household, or changes how it belongs.
 *
 * Rules: only a person can have a role; there is exactly one "Me" ([HouseholdRole.SELF]), so a second one is refused and "Me"
 * can never be cleared or turned into a member (it is set up once, by the user); a member's [Relationship] is kept with the role
 * and dropped for anyone else.
 */
class SetHouseholdRoleUseCase @Inject constructor(
    private val profiles: ProfileRepository,
) {
    suspend operator fun invoke(
        profileId: String,
        role: HouseholdRole?,
        relationship: Relationship? = null,
    ): PamResult<Unit> {
        val profile = when (val loaded = profiles.getProfileById(profileId)) {
            is PamResult.Success -> loaded.data
            is PamResult.Error -> return PamResult.Error(loaded.error)
        }
        val wantedRelationship = relationship.takeIf { role == HouseholdRole.MEMBER }

        if (role != null && profile.kind == ProfileKind.ORGANISATION) {
            return refuse("an organisation cannot be part of the household")
        }
        if (profile.isSelf && role != HouseholdRole.SELF) {
            return refuse("\"Me\" cannot be cleared or changed")
        }
        if (role == HouseholdRole.SELF && profiles.getProfiles().first().any { it.isSelf && it.id != profileId }) {
            return refuse("there is already a \"Me\" profile")
        }
        if (profile.householdRole == role && profile.relationship == wantedRelationship) return PamResult.Success(Unit)

        return profiles.updateProfile(
            profile.copy(householdRole = role, relationship = wantedRelationship, modifiedAt = System.currentTimeMillis()),
        )
    }

    private fun refuse(reason: String): PamResult<Unit> = PamResult.Error(PamError.ValidationError("householdRole", reason))
}
