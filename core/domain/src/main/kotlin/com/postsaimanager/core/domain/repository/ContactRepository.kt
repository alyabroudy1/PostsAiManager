package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.ContactPerson
import kotlinx.coroutines.flow.Flow

/**
 * The persons inside organisation profiles and which of them handled which letter. One owner of the contact concept: nothing
 * else reads or writes the contact tables.
 */
interface ContactRepository {
    /** The contacts of one organisation, newest [ContactPerson.lastSeen] first. */
    fun observeContacts(organisationId: String): Flow<List<ContactPerson>>

    /** The contacts that handled one document. */
    fun observeContactsForDocument(documentId: String): Flow<List<ContactPerson>>

    /**
     * The ids of the organisation's contacts that are "to check": made from a machine reading that was not sure (below the profile
     * auto-create bar) and that nobody has confirmed, edited or typed. Confirming the letter's contact field clears it.
     */
    fun observeContactsToCheck(organisationId: String): Flow<Set<String>>

    /** The documents a contact is linked to. */
    suspend fun documentIdsOf(contactId: String): List<String>

    /** How many contacts each organisation has (organisations without any are absent). */
    fun observeContactCounts(): Flow<Map<String, Int>>

    suspend fun getContact(id: String): PamResult<ContactPerson>
    suspend fun addContact(contact: ContactPerson): PamResult<ContactPerson>
    suspend fun updateContact(contact: ContactPerson): PamResult<Unit>

    /**
     * Deletes a contact. The letters stay; they only lose the link. The contact's name is remembered for every letter it was
     * linked to ([isRemovedFromDocument]), so reading one of those letters again does not bring the contact back.
     */
    suspend fun deleteContact(id: String): PamResult<Unit>

    /** Whether the user deleted a contact named [name] from [documentId]'s links (compared as a trimmed, lower-cased name). */
    suspend fun isRemovedFromDocument(documentId: String, name: String): Boolean

    /** Marks a contact "no longer responsible" ([active] false) or back. The contact stays as history. */
    suspend fun setActive(id: String, active: Boolean): PamResult<Unit>

    /**
     * Folds [mergedId] into [keepId] (both of the same organisation): [keepId] keeps its own values and takes the other's where it
     * has none, spans both seen-ranges, and gets the documents of [mergedId]; [mergedId] is gone afterwards.
     */
    suspend fun mergeContacts(keepId: String, mergedId: String): PamResult<Unit>

    suspend fun linkContactToDocument(contactId: String, documentId: String): PamResult<Unit>
    suspend fun unlinkContactFromDocument(contactId: String, documentId: String): PamResult<Unit>
}
