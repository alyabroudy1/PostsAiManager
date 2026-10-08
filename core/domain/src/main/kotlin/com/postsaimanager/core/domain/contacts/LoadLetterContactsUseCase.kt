package com.postsaimanager.core.domain.contacts

import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * The contacts that matter for one letter.
 *
 * @property letterContact the person this letter names, or null when none is linked
 * @property current the organisation's current contact (the newest active one), or null; may be the same person as [letterContact]
 * @property organisationId the sender organisation, or null when the letter has no resolved one
 * @property organisationName the organisation's name as the user sees it
 */
data class LetterContacts(
    val letterContact: ContactPerson? = null,
    val current: ContactPerson? = null,
    val organisationId: String? = null,
    val organisationName: String? = null,
    /** The letter's contact was suggested by a reading and nobody has confirmed, edited or discarded it yet. */
    val letterContactSuggested: Boolean = false,
) {
    /** The details of both people, to be offered as values (a skill's recipient, a call), without repeating a person. */
    val people: List<ContactPerson> get() = listOfNotNull(letterContact, current).distinctBy { it.id }
}

/** Reads [LetterContacts] of a letter: its contact, and the sender organisation's current contact. One owner of that lookup. */
class LoadLetterContactsUseCase @Inject constructor(
    private val contacts: ContactRepository,
    private val profiles: ProfileRepository,
    private val organisationContacts: ObserveOrganisationContactsUseCase,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(documentId: String): Flow<LetterContacts> =
        combine(contacts.observeContactsForDocument(documentId), profiles.getProfilesForDocument(documentId)) { linked, parties ->
            val letterContact = linked.firstOrNull()
            val sender = parties.firstOrNull { (p, role) -> role == ProfileRole.SENDER && p.kind == ProfileKind.ORGANISATION }?.first
            val organisationId = letterContact?.organisationId ?: sender?.id
            Triple(letterContact, organisationId, sender?.let { it.organization ?: it.name })
        }.flatMapLatest { (letterContact, organisationId, senderName) ->
            if (organisationId == null) {
                flowOf(LetterContacts(letterContact = letterContact))
            } else {
                combine(organisationContacts(organisationId), profiles.getProfiles()) { grouped, all ->
                    val organisation = all.firstOrNull { it.id == organisationId }
                    LetterContacts(
                        letterContact = letterContact,
                        current = grouped.current,
                        organisationId = organisationId,
                        organisationName = organisation?.let { it.organization ?: it.name } ?: senderName,
                        letterContactSuggested = letterContact != null && letterContact.id in grouped.toCheck,
                    )
                }
            }
        }

    suspend operator fun invoke(documentId: String): LetterContacts = observe(documentId).first()
}
