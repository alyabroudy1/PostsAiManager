package com.postsaimanager.core.domain.contacts

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.document.contacts.ContactCandidate
import com.postsaimanager.core.domain.document.contacts.DecideSameContactUseCase
import com.postsaimanager.core.domain.document.contacts.ReadContact
import com.postsaimanager.core.domain.document.contacts.SameContactDecision
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.ValueSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Why a contact is waiting on its letter. */
enum class PendingReason {
    /** The letter's sender organisation is not resolved yet; the contact is attached when it is. */
    SENDER_UNRESOLVED,

    /** The same-person question could not be answered (no model); the contact is attached on the next run. */
    DECISION_FAILED,
}

/** What [LinkSenderContactUseCase] did for one letter. [decision] (when a question was asked) is for the debug log. */
sealed interface ContactLinkOutcome {
    /** The letter names no contact, the person ignored it, deleted that contact from it, or it is linked already. */
    data object NothingToLink : ContactLinkOutcome

    /** The contact waits on the letter: its name stays in the letter's stored "Contact Person" field. */
    data class Pending(val reason: PendingReason) : ContactLinkOutcome

    /** The letter's contact is [contactId], an existing contact of the organisation. */
    data class Matched(val contactId: String, val decision: SameContactDecision?) : ContactLinkOutcome

    /** The letter's contact is a person the organisation had no contact for; [contactId] is the new one. */
    data class Created(val contactId: String, val decision: SameContactDecision?) : ContactLinkOutcome
}

/**
 * Attaches the contact person a letter names to the letter's sender organisation. It replaces the SENDER_CONTACT branch of the entity
 * linking: a contact never becomes a profile.
 *
 * The reading stores the contact's name in the letter's "Contact Person" field; that field is where a contact waits. When the sender
 * organisation is linked to the letter, the organisation's contacts are fetched and [DecideSameContactUseCase] (the model decides, code
 * only verifies) says whether the name is one of them. A match gets `lastSeen` and whatever it did not know yet (a value already set
 * is never replaced: the user may have edited it); no match is a new contact. A sender that is not resolved yet, or no model to answer,
 * leaves the contact waiting: calling [invoke] again after the sender link is confirmed attaches it. A field the person ignored, a
 * contact the person deleted from this letter and a letter that has a contact already are left alone.
 */
class LinkSenderContactUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val profiles: ProfileRepository,
    private val contacts: ContactRepository,
    private val decide: DecideSameContactUseCase,
) {

    /** Links the contact stored on [documentId], if it has one and the sender organisation is resolved. */
    suspend operator fun invoke(documentId: String): ContactLinkOutcome {
        val field = documents.observeExtractedData(documentId).first().firstOrNull(::isLiveContact) ?: return ContactLinkOutcome.NothingToLink
        return link(documentId, ReadContact(field.fieldValue.trim()), field.evidence)
    }

    /** Links [read] to [documentId]'s sender organisation. [fallbackExcerpt] is used when the contact's name is not found in the letter's text. */
    suspend fun link(documentId: String, read: ReadContact, fallbackExcerpt: String? = null): ContactLinkOutcome {
        if (read.name.isBlank()) return ContactLinkOutcome.NothingToLink
        if (contacts.observeContactsForDocument(documentId).first().isNotEmpty()) return ContactLinkOutcome.NothingToLink
        if (contacts.isRemovedFromDocument(documentId, read.name)) return ContactLinkOutcome.NothingToLink

        val organisation = senderOrganisation(documentId) ?: return ContactLinkOutcome.Pending(PendingReason.SENDER_UNRESOLVED)
        val seenAt = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.createdAt ?: System.currentTimeMillis()
        val known = contacts.observeContacts(organisation.id).first()
        val excerpt = ContactExcerpt.around(letterText(documentId), read.name) ?: fallbackExcerpt?.takeIf { it.isNotBlank() }

        val decision = when (
            val answer = decide(read, organisation.organization ?: organisation.name, excerpt, known.map(::candidateOf))
        ) {
            is PamResult.Error -> return ContactLinkOutcome.Pending(PendingReason.DECISION_FAILED)
            is PamResult.Success -> answer.data
        }

        val existing = decision.matchedId?.let { id -> known.firstOrNull { it.id == id } }
        return if (existing != null) {
            contacts.updateContact(seen(existing, read, seenAt))
            contacts.linkContactToDocument(existing.id, documentId)
            ContactLinkOutcome.Matched(existing.id, decision)
        } else {
            val created = ContactPerson(
                id = UuidGenerator.generate(), organisationId = organisation.id, name = read.name.trim(), title = read.title.clean(),
                phone = read.phone.clean(), email = read.email.clean(), firstSeen = seenAt, lastSeen = seenAt,
            )
            contacts.addContact(created)
            contacts.linkContactToDocument(created.id, documentId)
            ContactLinkOutcome.Created(created.id, decision)
        }
    }

    /** The existing contact seen again: newer `lastSeen` (and older `firstSeen`), and only what it did not have yet. */
    private fun seen(contact: ContactPerson, read: ReadContact, seenAt: Long) = contact.copy(
        firstSeen = minOf(contact.firstSeen, seenAt),
        lastSeen = maxOf(contact.lastSeen, seenAt),
        title = contact.title.takeUnless { it.isNullOrBlank() } ?: read.title.clean(),
        phone = contact.phone.takeUnless { it.isNullOrBlank() } ?: read.phone.clean(),
        email = contact.email.takeUnless { it.isNullOrBlank() } ?: read.email.clean(),
    )

    private suspend fun senderOrganisation(documentId: String): Profile? =
        profiles.getProfilesForDocument(documentId).first()
            .firstOrNull { (profile, role) -> role == ProfileRole.SENDER && profile.kind == ProfileKind.ORGANISATION }?.first

    private suspend fun letterText(documentId: String): String =
        (documents.getDocumentPages(documentId) as? PamResult.Success)?.data.orEmpty()
            .sortedBy { it.pageNumber }.mapNotNull { it.ocrText }.joinToString("\n")

    private fun candidateOf(c: ContactPerson) =
        ContactCandidate(id = c.id, name = c.name, title = c.title, phone = c.phone, email = c.email, lastSeenAt = c.lastSeen)

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun isLiveContact(row: ExtractedData): Boolean =
        row.slotKey == UnderstandingToFields.SLOT_CONTACT && row.fieldValue.isNotBlank() && !row.deletedByUser &&
            row.reviewState != ReviewState.IGNORED &&
            (row.source == ValueSource.USER || row.confidence >= DocumentUnderstanding.AUTO_LINK_CONFIDENCE)
}

/** A short excerpt of a letter around a name, so the same-person question has the signature's surroundings. Structure only: no word is interpreted. */
object ContactExcerpt {

    /** [radius] characters before and after the first occurrence of [name] (case-insensitive), or null when the text does not contain it. */
    fun around(text: String, name: String, radius: Int = RADIUS): String? {
        val wanted = name.trim()
        if (wanted.isEmpty() || text.isBlank()) return null
        val at = text.indexOf(wanted, ignoreCase = true)
        if (at < 0) return null
        val from = (at - radius).coerceAtLeast(0)
        val to = (at + wanted.length + radius).coerceAtMost(text.length)
        return text.substring(from, to).trim()
    }

    private const val RADIUS = 300
}
