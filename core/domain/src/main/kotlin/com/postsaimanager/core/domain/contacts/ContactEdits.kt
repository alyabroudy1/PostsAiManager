package com.postsaimanager.core.domain.contacts

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ProfileKind
import javax.inject.Inject

/**
 * The user edits a contact's details. The name cannot be empty; a blank detail is cleared. What the user typed is the contact's value
 * from then on: reading a letter again only fills what is empty ([LinkSenderContactUseCase]).
 */
class UpdateContactUseCase @Inject constructor(private val contacts: ContactRepository) {
    suspend operator fun invoke(edited: ContactPerson): PamResult<Unit> {
        val name = edited.name.trim()
        if (name.isEmpty()) return PamResult.Error(PamError.ValidationError("name", "a contact needs a name"))
        return contacts.updateContact(
            edited.copy(
                name = name, title = edited.title.cleaned(), department = edited.department.cleaned(), phone = edited.phone.cleaned(),
                email = edited.email.cleaned(), room = edited.room.cleaned(),
            ),
        )
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

/** "No longer responsible" (or responsible again): the contact stays as history and stops being the current one. */
class SetContactActiveUseCase @Inject constructor(private val contacts: ContactRepository) {
    suspend operator fun invoke(contactId: String, active: Boolean): PamResult<Unit> = contacts.setActive(contactId, active)
}

/** The user says two contacts of one organisation are the same person: [mergedId] goes into [keepId] and its letters follow. */
class MergeContactsUseCase @Inject constructor(private val contacts: ContactRepository) {
    suspend operator fun invoke(keepId: String, mergedId: String): PamResult<Unit> = contacts.mergeContacts(keepId, mergedId)
}

/** The user moves a contact to another office (another organisation profile). The contact's letters stay linked to it. */
class MoveContactUseCase @Inject constructor(
    private val contacts: ContactRepository,
    private val profiles: ProfileRepository,
) {
    suspend operator fun invoke(contactId: String, targetOrganisationId: String): PamResult<Unit> {
        val contact = when (val loaded = contacts.getContact(contactId)) {
            is PamResult.Success -> loaded.data
            is PamResult.Error -> return PamResult.Error(loaded.error)
        }
        if (contact.organisationId == targetOrganisationId) return PamResult.Success(Unit)
        val target = when (val loaded = profiles.getProfileById(targetOrganisationId)) {
            is PamResult.Success -> loaded.data
            is PamResult.Error -> return PamResult.Error(loaded.error)
        }
        if (target.kind != ProfileKind.ORGANISATION) {
            return PamResult.Error(PamError.ValidationError("organisation", "a contact can only work at an organisation"))
        }
        return contacts.updateContact(contact.copy(organisationId = target.id))
    }
}

/** Deletes a contact; the letters stay, and reading them again does not bring the contact back. */
class DeleteContactUseCase @Inject constructor(private val contacts: ContactRepository) {
    suspend operator fun invoke(contactId: String): PamResult<Unit> = contacts.deleteContact(contactId)
}
