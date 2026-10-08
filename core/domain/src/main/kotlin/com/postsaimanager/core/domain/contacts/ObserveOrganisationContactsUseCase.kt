package com.postsaimanager.core.domain.contacts

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.ContactPerson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The contacts of one organisation as the page shows them: the current one, then the earlier ones. */
data class OrganisationContacts(
    val current: ContactPerson?,
    val earlier: List<ContactPerson>,
    /** The contacts a reading suggested and nobody has answered: the page shows them as "Suggested from <letter>". */
    val toCheck: Set<String> = emptySet(),
    /** For each suggested contact, the title of the letter it was found on (blank when that letter is gone). */
    val suggestedFrom: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = current == null && earlier.isEmpty()
}

/**
 * The current contact is the active one seen on the newest letter; everyone else, including contacts marked "no longer
 * responsible", is earlier (newest first). Ordering by what was seen is bookkeeping, not a decision about who someone is. A suggested
 * contact carries the title of its source letter.
 */
class ObserveOrganisationContactsUseCase @Inject constructor(
    private val contacts: ContactRepository,
    private val documents: DocumentRepository,
) {
    operator fun invoke(organisationId: String): Flow<OrganisationContacts> =
        combine(contacts.observeContacts(organisationId), contacts.observeContactsToCheck(organisationId)) { all, toCheck -> all to toCheck }
            .map { (all, toCheck) ->
                val titles = toCheck.mapValues { (_, documentId) ->
                    (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.title.orEmpty()
                }
                group(all).copy(toCheck = toCheck.keys, suggestedFrom = titles)
            }

    internal fun group(all: List<ContactPerson>): OrganisationContacts {
        val newestFirst = all.sortedWith(compareByDescending<ContactPerson> { it.lastSeen }.thenBy { it.name.lowercase() }.thenBy { it.id })
        val current = newestFirst.firstOrNull { it.active }
        return OrganisationContacts(current, newestFirst.filter { it != current })
    }
}
