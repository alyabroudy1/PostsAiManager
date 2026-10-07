package com.postsaimanager.core.domain.contacts

import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.model.ContactPerson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** The contacts of one organisation as the page shows them: the current one, then the earlier ones. */
data class OrganisationContacts(
    val current: ContactPerson?,
    val earlier: List<ContactPerson>,
    /** The contacts made from a reading that was not sure and that nobody has confirmed: the page marks them "to check". */
    val toCheck: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = current == null && earlier.isEmpty()
}

/**
 * The current contact is the active one seen on the newest letter; everyone else, including contacts marked "no longer
 * responsible", is earlier (newest first). Ordering by what was seen is bookkeeping, not a decision about who someone is.
 */
class ObserveOrganisationContactsUseCase @Inject constructor(
    private val contacts: ContactRepository,
) {
    operator fun invoke(organisationId: String): Flow<OrganisationContacts> =
        combine(contacts.observeContacts(organisationId), contacts.observeContactsToCheck(organisationId)) { all, toCheck ->
            group(all).copy(toCheck = toCheck)
        }

    internal fun group(all: List<ContactPerson>): OrganisationContacts {
        val newestFirst = all.sortedWith(compareByDescending<ContactPerson> { it.lastSeen }.thenBy { it.name.lowercase() }.thenBy { it.id })
        val current = newestFirst.firstOrNull { it.active }
        return OrganisationContacts(current, newestFirst.filter { it != current })
    }
}
