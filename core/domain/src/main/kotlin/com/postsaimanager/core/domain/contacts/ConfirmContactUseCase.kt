package com.postsaimanager.core.domain.contacts

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ValueSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The user says a contact made from a reading that was not sure is right: the letters' "Contact Person" fields that name it are
 * confirmed (the existing field confirmation), which is what clears the contact's "to check" mark.
 */
class ConfirmContactUseCase @Inject constructor(
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
                .filter { it.slotKey == UnderstandingToFields.SLOT_CONTACT && it.source == ValueSource.MACHINE && !it.deletedByUser }
                .filter { it.fieldValue.trim().equals(contact.name.trim(), ignoreCase = true) }
                .forEach { documents.confirmExtractedField(it.id) }
        }
        return PamResult.Success(Unit)
    }
}
