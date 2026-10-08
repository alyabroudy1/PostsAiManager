package com.postsaimanager.core.domain.organisation

import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * A letter has one sender organisation. When a reading settles it ([senderId]), an organisation an earlier reading linked as the sender
 * of the same letter is no longer its sender: its link is removed, and so are its contacts' links to this letter. Otherwise a letter read
 * again with a better reader (the earlier one took a label for the sender, say) would keep both organisations, and the steps that follow
 * a reading (the contact's link, the organisation's suggested details, the timeline's matter) could pick the old one.
 *
 * The old organisation's profile and contacts stay as they are (a person may have worked with them); only this letter's links go.
 */
class ReplaceStaleSenderUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val contacts: ContactRepository,
) {

    /** Returns how many stale sender organisations were unlinked from [documentId]. */
    suspend operator fun invoke(documentId: String, senderId: String): Int {
        val stale = profiles.getProfilesForDocument(documentId).first()
            .filter { (profile, role) -> role == ProfileRole.SENDER && profile.kind == ProfileKind.ORGANISATION && profile.id != senderId }
            .map { it.first.id }
            .distinct()
        for (organisationId in stale) {
            contacts.observeContacts(organisationId).first()
                .filter { contact -> documentId in contacts.documentIdsOf(contact.id) }
                .forEach { contacts.unlinkContactFromDocument(it.id, documentId) }
            profiles.unlinkProfileFromDocument(organisationId, documentId)
        }
        return stale.size
    }
}
