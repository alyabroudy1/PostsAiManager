package com.postsaimanager.core.domain.document.list

import com.postsaimanager.core.model.PersonTag
import com.postsaimanager.core.model.Profile
import javax.inject.Inject

/**
 * The person chips of one document: the managed profiles (Me and the family members) the model decided the document is for or about,
 * as stored on the document (`Document.concernedProfileIds`, see `DecideConcernedPeopleUseCase`). Nothing here reads a name: a document
 * not asked yet (null) or asked with nobody (empty), an id whose profile is gone, or a profile that is no longer managed shows no chip.
 * "Me" comes first.
 */
class ConcernedPeopleTagsUseCase @Inject constructor() {

    operator fun invoke(profiles: List<Profile>, concernedProfileIds: List<String>?): List<PersonTag> {
        if (concernedProfileIds.isNullOrEmpty()) return emptyList()
        val managed = profiles.filter { it.isManaged }.sortedByDescending { it.isSelf }
        val shortNames = ShortNames.of(managed)
        return managed.filter { it.id in concernedProfileIds }.map { PersonTag(it.id, shortNames.getValue(it.id), it.isSelf) }
    }
}

/** The short name a chip shows: the first name, or the full name when two managed people would read the same. */
internal object ShortNames {

    fun of(managed: List<Profile>): Map<String, String> {
        val firstNames = managed.associate { it.id to it.name.trim().substringBefore(' ').ifEmpty { it.name.trim() } }
        val clashing = firstNames.values.groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }.keys
        return managed.associate { profile ->
            val first = firstNames.getValue(profile.id)
            profile.id to if (first.lowercase() in clashing) profile.name.trim() else first
        }
    }
}
