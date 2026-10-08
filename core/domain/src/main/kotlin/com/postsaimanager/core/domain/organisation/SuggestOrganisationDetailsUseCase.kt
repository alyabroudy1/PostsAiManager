package com.postsaimanager.core.domain.organisation

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.contacts.ContactExcerpt
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.repository.ProfileSuggestionRepository
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.SuggestionField
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** What [SuggestOrganisationDetailsUseCase] did for one letter. */
data class SuggestOutcome(
    /** Suggestions newly offered to the organisation profile. */
    val offered: Int = 0,
    /** Phone numbers and e-mail addresses the model said are the letter's contact person's, written to that contact's empty fields. */
    val contactFilled: Int = 0,
    /** The letter has no resolved sender organisation, or the model could not answer (the values were not offered). */
    val skipped: Boolean = false,
)

/**
 * After a reading: offers what the letter shows about its sender organisation to that organisation's profile, as suggestions the user
 * accepts, edits or dismisses (never written by itself).
 *
 * - The postal address comes from the reading's verified sender address (letterhead, return line or footer); it needs no further question.
 * - A phone number, e-mail address, website or bank account of the letter is offered only after the model said whose it is
 *   ([DecideDetailOwnerUseCase]): the organisation's own is offered to the profile, the contact person's direct one fills that contact's
 *   empty field. Code only verifies that the value is in the letter.
 * - Only for a field the profile has empty; a value that was offered before (pending or dismissed) is not offered again; a value the user
 *   typed is never replaced.
 */
class SuggestOrganisationDetailsUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val profiles: ProfileRepository,
    private val contacts: ContactRepository,
    private val suggestions: ProfileSuggestionRepository,
    private val decide: DecideDetailOwnerUseCase,
) {

    suspend operator fun invoke(documentId: String): SuggestOutcome {
        val sender = profiles.getProfilesForDocument(documentId).first()
            .firstOrNull { (profile, role) -> role == ProfileRole.SENDER && profile.kind == ProfileKind.ORGANISATION }?.first
            ?: return SuggestOutcome(skipped = true)
        val organisation = (profiles.getProfileById(sender.id) as? PamResult.Success)?.data ?: return SuggestOutcome(skipped = true)
        val fields = documents.observeExtractedData(documentId).first()
        val text = (documents.getDocumentPages(documentId) as? PamResult.Success)?.data.orEmpty()
            .sortedBy { it.pageNumber }.mapNotNull { it.ocrText }.joinToString("\n")
        val known = suggestions.all(organisation.id)
        val now = System.currentTimeMillis()
        var offered = 0

        suspend fun offer(field: SuggestionField, value: String) {
            val clean = value.trim()
            if (clean.isEmpty() || known.any { it.field == field && it.value == clean }) return
            suggestions.offer(
                ProfileSuggestion(UuidGenerator.generate(), organisation.id, field, clean, documentId, now),
            )
            offered++
        }

        LetterDetailCandidates.senderAddress(fields)
            ?.takeIf { SuggestionRules.isEmpty(organisation, SuggestionField.ADDRESS) }
            ?.let { offer(SuggestionField.ADDRESS, it.encode()) }

        val contact = contacts.observeContactsForDocument(documentId).first().firstOrNull()
        val name = organisation.organization ?: organisation.name
        var contactFilled = 0
        var skipped = false
        for (candidate in LetterDetailCandidates.details(fields, text).take(MAX_CANDIDATES)) {
            val field = fieldOf(candidate.kind)
            val organisationWants = SuggestionRules.isEmpty(organisation, field) && known.none { it.field == field && it.value == candidate.value }
            val current = contact?.let { (contacts.getContact(it.id) as? PamResult.Success)?.data }
            val contactWants = current != null && contactFieldEmpty(current, candidate.kind)
            if (!organisationWants && !contactWants) continue
            val owner = when (val answer = decide(candidate, name, contact?.name, ContactExcerpt.around(text, candidate.value, EXCERPT_RADIUS))) {
                is PamResult.Error -> { skipped = true; break }
                is PamResult.Success -> answer.data
            }
            when {
                owner == DetailOwner.ORGANISATION && organisationWants -> offer(field, candidate.value)
                owner == DetailOwner.CONTACT && contactWants && current != null -> {
                    contacts.updateContact(withDetail(current, candidate))
                    contactFilled++
                }
            }
        }
        return SuggestOutcome(offered = offered, contactFilled = contactFilled, skipped = skipped)
    }

    private fun contactFieldEmpty(c: ContactPerson, kind: DetailKind): Boolean = when (kind) {
        DetailKind.PHONE -> c.phone.isNullOrBlank()
        DetailKind.EMAIL -> c.email.isNullOrBlank()
        DetailKind.WEBSITE, DetailKind.IBAN -> false
    }

    private fun withDetail(c: ContactPerson, candidate: DetailCandidate): ContactPerson = when (candidate.kind) {
        DetailKind.PHONE -> c.copy(phone = candidate.value)
        DetailKind.EMAIL -> c.copy(email = candidate.value)
        else -> c
    }

    private fun fieldOf(kind: DetailKind): SuggestionField = when (kind) {
        DetailKind.PHONE -> SuggestionField.PHONE
        DetailKind.EMAIL -> SuggestionField.EMAIL
        DetailKind.WEBSITE -> SuggestionField.WEBSITE
        DetailKind.IBAN -> SuggestionField.IBAN
    }

    companion object {
        /** The label of the own detail an accepted account is stored under (an acronym, the same in every language). */
        const val IBAN_LABEL = "IBAN"

        /** One letter's footer can hold many numbers; the model is asked about this many at most. */
        private const val MAX_CANDIDATES = 10
        private const val EXCERPT_RADIUS = 250
    }
}
