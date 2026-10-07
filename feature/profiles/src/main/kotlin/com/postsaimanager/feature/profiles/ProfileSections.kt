package com.postsaimanager.feature.profiles

import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind

/**
 * The profiles list as the screen shows it, in this order: the household ("Me" first, then members by name), the
 * organisations, then the other persons. Pure grouping of what each profile already is; nothing is decided here.
 */
data class ProfileSections(
    val household: List<Profile>,
    val organisations: List<Profile>,
    val people: List<Profile>,
) {
    companion object {
        fun of(profiles: List<Profile>): ProfileSections {
            val byName = compareBy<Profile>({ it.name.lowercase() }, { it.id })
            return ProfileSections(
                household = profiles.filter { it.isManaged }.sortedWith(compareByDescending<Profile> { it.isSelf }.then(byName)),
                organisations = profiles.filter { !it.isManaged && it.kind == ProfileKind.ORGANISATION }.sortedWith(byName),
                people = profiles.filter { !it.isManaged && it.kind == ProfileKind.PERSON }.sortedWith(byName),
            )
        }
    }
}
