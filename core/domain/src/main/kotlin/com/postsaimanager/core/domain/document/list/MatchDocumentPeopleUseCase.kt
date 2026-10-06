package com.postsaimanager.core.domain.document.list

import com.postsaimanager.core.model.DocumentProfileLink
import com.postsaimanager.core.model.PersonRole
import com.postsaimanager.core.model.PersonTag
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import javax.inject.Inject

/** The names a document printed for the people it concerns, as stored (null when the letter had none). */
data class DocumentParties(
    val addressee: String?,
    val subjectPerson: String?,
)

/**
 * Which managed people (Me and the family members) a document is for or about. The one owner of that
 * decision: the list rows ask here for every row, in memory, from the profiles and links the caller
 * already holds.
 *
 * A stored link (written when extraction linked a recipient or a mentioned person to a profile) wins
 * over a name: when a document has a link of a kind, that kind is not matched by name at all. Without
 * one, the printed addressee names the people it is FOR and the printed subject person the people it
 * is ABOUT, by [PartyNames]. A person who is both is only FOR. "Me" comes first.
 *
 * Misses by design: a household line that does not spell a person's full name contiguously, a name
 * written in another script than the profile's, and a nickname, because the profile stores one name.
 */
class MatchDocumentPeopleUseCase @Inject constructor() {

    operator fun invoke(parties: DocumentParties, profiles: List<Profile>, links: List<DocumentProfileLink>): List<PersonTag> {
        val managed = profiles.filter { it.isManaged }.sortedByDescending { it.isSelf }
        if (managed.isEmpty()) return emptyList()
        val linkedFor = linked(managed, links) { it == ProfileRole.RECEIVER }
        val linkedAbout = linked(managed, links) { it == ProfileRole.SUBJECT || it == ProfileRole.RELATED }
        val forPeople = linkedFor.ifEmpty { named(managed, parties.addressee) }
        val aboutPeople = linkedAbout.ifEmpty { named(managed, parties.subjectPerson) }.filterNot { it in forPeople }
        val shortNames = ShortNames.of(managed)
        fun tag(profile: Profile, role: PersonRole) = PersonTag(profile.id, shortNames.getValue(profile.id), role, profile.isSelf)
        return forPeople.map { tag(it, PersonRole.FOR) } + aboutPeople.map { tag(it, PersonRole.ABOUT) }
    }

    private fun linked(managed: List<Profile>, links: List<DocumentProfileLink>, wanted: (ProfileRole) -> Boolean): List<Profile> {
        val ids = links.filter { wanted(it.role) }.map { it.profileId }.toSet()
        return managed.filter { it.id in ids }
    }

    private fun named(managed: List<Profile>, printed: String?): List<Profile> {
        val line = printed?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return managed.filter { PartyNames.names(line, it.name) }
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
