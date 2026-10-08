package com.postsaimanager.core.domain.contacts

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.CustomDetail
import com.postsaimanager.core.model.CustomDetails
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.ValueSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The user types a contact into an organisation ("Add contact"): a name and whatever else they know. The contact is the user's own
 * value from the start: no letter suggested it, so it is never "to check", and it is the organisation's newest contact until a newer
 * letter names someone.
 */
class AddContactUseCase @Inject constructor(
    private val contacts: ContactRepository,
    private val profiles: ProfileRepository,
) {
    suspend operator fun invoke(
        organisationId: String,
        name: String,
        title: String? = null,
        department: String? = null,
        phone: String? = null,
        email: String? = null,
        customDetails: List<CustomDetail> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): PamResult<ContactPerson> {
        val clean = name.trim()
        if (clean.isEmpty()) return PamResult.Error(PamError.ValidationError("name", "a contact needs a name"))
        val organisation = when (val loaded = profiles.getProfileById(organisationId)) {
            is PamResult.Success -> loaded.data
            is PamResult.Error -> return PamResult.Error(loaded.error)
        }
        if (organisation.kind != ProfileKind.ORGANISATION) {
            return PamResult.Error(PamError.ValidationError("organisation", "a contact can only work at an organisation"))
        }
        return contacts.addContact(
            ContactPerson(
                id = UuidGenerator.generate(), organisationId = organisation.id, name = clean, title = title.cleaned(),
                department = department.cleaned(), phone = phone.cleaned(), email = email.cleaned(), firstSeen = now, lastSeen = now,
                customDetails = CustomDetails.cleaned(customDetails),
            ),
        )
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The user edits a contact's details. The name cannot be empty; a blank detail is cleared, and an own detail needs a label and a value.
 * What the user typed is the contact's value from then on: reading a letter again only fills what is empty ([LinkSenderContactUseCase]).
 *
 * Editing a contact a reading suggested answers the suggestion: the letters' "Contact Person" fields that name it are confirmed, or
 * renamed (and so edited) when the name changed, so the Extracted tab and the organisation page always say the same.
 */
class UpdateContactUseCase @Inject constructor(
    private val contacts: ContactRepository,
    private val documents: DocumentRepository,
) {
    suspend operator fun invoke(edited: ContactPerson): PamResult<Unit> {
        val name = edited.name.trim()
        if (name.isEmpty()) return PamResult.Error(PamError.ValidationError("name", "a contact needs a name"))
        val before = (contacts.getContact(edited.id) as? PamResult.Success)?.data
        val result = contacts.updateContact(
            edited.copy(
                name = name, title = edited.title.cleaned(), department = edited.department.cleaned(), phone = edited.phone.cleaned(),
                email = edited.email.cleaned(), room = edited.room.cleaned(), customDetails = CustomDetails.cleaned(edited.customDetails),
            ),
        )
        if (result is PamResult.Success && before != null) answerLetterFields(before, name)
        return result
    }

    private suspend fun answerLetterFields(before: ContactPerson, newName: String) {
        for (documentId in contacts.documentIdsOf(before.id)) {
            documents.observeExtractedData(documentId).first()
                .filter { it.slotKey == UnderstandingToFields.SLOT_CONTACT && !it.deletedByUser }
                .filter { it.fieldValue.trim().equals(before.name.trim(), ignoreCase = true) }
                .forEach { field ->
                    if (field.fieldValue.trim() == newName) {
                        if (field.source == ValueSource.MACHINE) documents.confirmExtractedField(field.id)
                    } else {
                        documents.updateExtractedField(field.id, field.fieldName, newName)
                    }
                }
        }
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The user discards a suggested contact: the letters' "Contact Person" fields that name it are ignored, and the contact is deleted with
 * the same tombstone as any deleted contact, so reading the letter again does not bring it back.
 */
class DiscardContactUseCase @Inject constructor(
    private val contacts: ContactRepository,
    private val documents: DocumentRepository,
) {
    suspend operator fun invoke(contactId: String): PamResult<Unit> {
        val contact = when (val found = contacts.getContact(contactId)) {
            is PamResult.Error -> return PamResult.Error(found.error)
            is PamResult.Success -> found.data
        }
        for (documentId in contacts.documentIdsOf(contactId)) {
            documents.observeExtractedData(documentId).first()
                .filter { it.slotKey == UnderstandingToFields.SLOT_CONTACT && !it.deletedByUser }
                .filter { it.fieldValue.trim().equals(contact.name.trim(), ignoreCase = true) }
                .forEach { documents.setFieldReviewState(it.id, ReviewState.IGNORED) }
        }
        return contacts.deleteContact(contactId)
    }
}

/**
 * The Extracted tab's contact field and the contact it names are one thing: editing the field renames the contact, ignoring the field
 * discards the contact (with its tombstone). Any other field is only edited or ignored. One owner of that link, so confirming, editing or
 * discarding in either place leaves both places saying the same.
 */
class LetterContactFields @Inject constructor(
    private val contacts: ContactRepository,
    private val documents: DocumentRepository,
    private val discard: DiscardContactUseCase,
) {
    /** The person edited the field [fieldId] of [documentId] to [value] (and, for a field they named themselves, [name]). */
    suspend fun edit(documentId: String, fieldId: String, name: String, value: String): PamResult<Unit> {
        val field = documents.observeExtractedData(documentId).first().firstOrNull { it.id == fieldId }
        val contact = field?.takeIf { it.slotKey == UnderstandingToFields.SLOT_CONTACT }?.let { contactNamed(documentId, it.fieldValue) }
        val result = documents.updateExtractedField(fieldId, name, value)
        val renamed = value.trim()
        if (result is PamResult.Success && contact != null && renamed.isNotEmpty()) contacts.updateContact(contact.copy(name = renamed))
        return result
    }

    /** The person ignored the fields [fieldIds] of [documentId]. */
    suspend fun ignore(documentId: String, fieldIds: List<String>): PamResult<Unit> {
        val fields = documents.observeExtractedData(documentId).first().filter { it.id in fieldIds }
        for (field in fields) {
            val contact = field.takeIf { it.slotKey == UnderstandingToFields.SLOT_CONTACT }?.let { contactNamed(documentId, it.fieldValue) }
            if (contact != null) discard(contact.id) else documents.setFieldReviewState(field.id, ReviewState.IGNORED)
        }
        return PamResult.Success(Unit)
    }

    private suspend fun contactNamed(documentId: String, name: String): ContactPerson? =
        contacts.observeContactsForDocument(documentId).first().firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
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
