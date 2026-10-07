package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.model.ContactPerson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [ContactRepository] for tests: simple storage, no merge semantics beyond removing the merged contact. */
class FakeContactRepository : ContactRepository {

    private val contacts = MutableStateFlow<List<ContactPerson>>(emptyList())

    /** Links as (contactId, documentId). */
    val links = mutableListOf<Pair<String, String>>()

    fun seed(vararg items: ContactPerson) {
        contacts.value = contacts.value + items
    }

    override fun observeContacts(organisationId: String): Flow<List<ContactPerson>> =
        contacts.map { list -> list.filter { it.organisationId == organisationId }.sortedByDescending { it.lastSeen } }

    override fun observeContactsForDocument(documentId: String): Flow<List<ContactPerson>> =
        contacts.map { list -> list.filter { c -> links.any { it.first == c.id && it.second == documentId } } }

    /** The ids [observeContactsToCheck] reports; the real repository derives them from the letters' contact fields. */
    val toCheck = MutableStateFlow<Set<String>>(emptySet())

    override fun observeContactsToCheck(organisationId: String): Flow<Set<String>> = toCheck

    override suspend fun documentIdsOf(contactId: String): List<String> = links.filter { it.first == contactId }.map { it.second }

    override fun observeContactCounts(): Flow<Map<String, Int>> =
        contacts.map { list -> list.groupingBy { it.organisationId }.eachCount() }

    override suspend fun getContact(id: String): PamResult<ContactPerson> =
        contacts.value.firstOrNull { it.id == id }?.let { PamResult.Success(it) } ?: PamResult.Error(PamError.FileNotFound("No contact $id"))

    override suspend fun addContact(contact: ContactPerson): PamResult<ContactPerson> {
        contacts.value = contacts.value + contact
        return PamResult.Success(contact)
    }

    override suspend fun updateContact(contact: ContactPerson): PamResult<Unit> {
        contacts.value = contacts.value.map { if (it.id == contact.id) contact else it }
        return PamResult.Success(Unit)
    }

    /** Removed (documentId, trimmed lower-case name) pairs, as the real repository remembers them. */
    val removed = mutableSetOf<Pair<String, String>>()

    override suspend fun deleteContact(id: String): PamResult<Unit> {
        val gone = contacts.value.firstOrNull { it.id == id }
        if (gone != null) links.filter { it.first == id }.forEach { removed += it.second to gone.name.trim().lowercase() }
        contacts.value = contacts.value.filterNot { it.id == id }
        links.removeAll { it.first == id }
        return PamResult.Success(Unit)
    }

    override suspend fun isRemovedFromDocument(documentId: String, name: String): Boolean =
        (documentId to name.trim().lowercase()) in removed

    override suspend fun setActive(id: String, active: Boolean): PamResult<Unit> {
        contacts.value = contacts.value.map { if (it.id == id) it.copy(active = active) else it }
        return PamResult.Success(Unit)
    }

    override suspend fun mergeContacts(keepId: String, mergedId: String): PamResult<Unit> {
        val moved = links.filter { it.first == mergedId }.map { keepId to it.second }
        links.removeAll { it.first == mergedId }
        moved.filterNot { it in links }.forEach { links += it }
        contacts.value = contacts.value.filterNot { it.id == mergedId }
        return PamResult.Success(Unit)
    }

    override suspend fun linkContactToDocument(contactId: String, documentId: String): PamResult<Unit> {
        if ((contactId to documentId) !in links) links += contactId to documentId
        return PamResult.Success(Unit)
    }

    override suspend fun unlinkContactFromDocument(contactId: String, documentId: String): PamResult<Unit> {
        links.remove(contactId to documentId)
        return PamResult.Success(Unit)
    }
}
