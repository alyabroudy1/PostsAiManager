package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.Relationship
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The guardians of a person: for a CHILD, "Me" and the PARTNER (if any), "Me" first; for anyone else, none.
 * One owner of that rule, shown to the form agent by `list_people` so it can pick the person of the GUARDIAN role (the AI decides).
 */
class GuardiansOfUseCase @Inject constructor(
    private val profiles: ProfileRepository,
) {
    suspend operator fun invoke(profileId: String): List<Profile> {
        val all = profiles.getProfiles().first()
        val person = all.firstOrNull { it.id == profileId } ?: return emptyList()
        if (person.relationship != Relationship.CHILD) return emptyList()
        val me = all.firstOrNull { it.isSelf }
        val partner = all.firstOrNull { it.relationship == Relationship.PARTNER }
        return listOfNotNull(me, partner).filter { it.id != person.id }
    }
}
