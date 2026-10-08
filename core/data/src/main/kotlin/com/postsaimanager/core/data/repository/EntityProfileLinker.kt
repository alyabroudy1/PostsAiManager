package com.postsaimanager.core.data.repository

import android.util.Log
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.domain.contacts.ContactLinkOutcome
import com.postsaimanager.core.domain.contacts.LinkSenderContactUseCase
import com.postsaimanager.core.domain.document.normaliseEntityName
import com.postsaimanager.core.domain.organisation.ReplaceStaleSenderUseCase
import com.postsaimanager.core.domain.organisation.SuggestOrganisationDetailsUseCase
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.usecase.EntityLinkingUseCase
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.RecognisedEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The data-layer half of turning recognised entities into profiles and links.
 *
 * [EntityLinkingUseCase] holds the decision — link, create, propose or ignore — as a pure
 * function of a role, a confidence and a match. This class supplies the three things that
 * decision needs and cannot look up itself: the best matching profile ([ProfileMatcher],
 * reused rather than re-implemented — see [ProfileMatcher.findBestMatch]), whether this
 * entity has already been dismissed for this document ([DismissedEntityDao]), and the actual
 * database writes for the outcome ([ProfileRepository]).
 *
 * A letter's contact person is never a profile: after the entities are handled (so after the sender organisation is linked) the
 * contact linking ([LinkSenderContactUseCase]) attaches it to that organisation, or leaves it waiting on the letter.
 *
 * Only a confident link or create is acted on. A `Propose` decision (an entity the app would have asked the person about) is
 * left alone: nothing is stored and nobody is asked, so a document never raises an "is this you?" question.
 *
 * Every entity is handled independently, and a failure on one must not stop the rest — a
 * document with three good entities and one that could not be linked should keep the three.
 */
@Singleton
class EntityProfileLinker @Inject constructor(
    private val profileMatcher: ProfileMatcher,
    private val profileRepository: ProfileRepository,
    private val dismissedEntityDao: DismissedEntityDao,
    private val decide: EntityLinkingUseCase,
    // Lazy: the same-person question reaches the model, which reaches the document processor that owns this linker (a cycle otherwise).
    private val linkSenderContact: dagger.Lazy<LinkSenderContactUseCase>,
    // Lazy for the same reason: whose a phone number is, is also a question to the model.
    private val suggestOrganisationDetails: dagger.Lazy<SuggestOrganisationDetailsUseCase>,
    private val replaceStaleSender: ReplaceStaleSenderUseCase,
) {

    data class Outcome(
        val linked: Int,
        val created: Int,
        /** Dismissed before, this run — not a count of everything skipped for any reason. */
        val ignoredAsDismissed: Int,
        /** What the contact linking did for the letter (never a profile); null when it failed unexpectedly. */
        val contact: ContactLinkOutcome? = null,
    )

    suspend fun process(documentId: String, understanding: DocumentUnderstanding): Outcome {
        var linked = 0
        var created = 0
        var ignored = 0
        var contactDismissed = false

        for (entity in understanding.entities) {
            if (entity.name.isBlank()) continue

            val key = normaliseEntityName(entity.name)
            val dismissed = dismissedEntityDao.isDismissed(documentId, key)
            // A contact person is not matched against profiles: it belongs to the organisation, not to the list of profiles.
            val match = if (dismissed || entity.role == EntityRole.SENDER_CONTACT) null else findMatch(entity)

            when (val action = decide.decide(entity, match, dismissed)) {
                is EntityLinkingUseCase.Action.Ignore -> {
                    ignored++
                    // A contact the user removed from this letter stays removed.
                    if (entity.role == EntityRole.SENDER_CONTACT) contactDismissed = true
                }

                // Handled once below, after the sender is linked, whatever the order of the entities.
                is EntityLinkingUseCase.Action.AttachContact -> Unit

                is EntityLinkingUseCase.Action.Link -> {
                    val result = profileRepository.linkProfileToDocument(
                        action.profileId, documentId, action.role,
                    )
                    if (result is PamResult.Success) {
                        linked++
                        settleSender(documentId, entity, action.role, action.profileId)
                    }
                }

                is EntityLinkingUseCase.Action.Create -> {
                    val profileId = createAndLink(documentId, key, action)
                    if (profileId != null) {
                        created++
                        settleSender(documentId, entity, action.role, profileId)
                    }
                }

                is EntityLinkingUseCase.Action.Propose -> Unit
            }
        }

        // The contact person waits on the letter (its stored "Contact Person" field) until the sender organisation is linked: this is the
        // one place a sender link is confirmed, so the contact is attached here, after it. Without a resolved sender it stays waiting, and
        // the next run that confirms the sender attaches it.
        val contact = if (contactDismissed) ContactLinkOutcome.NothingToLink else try {
            linkSenderContact.get()(documentId).also { logContactDecision(documentId, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log(Log.WARN, "contact linking failed for $documentId: ${e.javaClass.simpleName}")
            null
        }

        // What the letter shows about its sender organisation (address, general phone, e-mail, website, account) is offered to that
        // organisation's profile as suggestions, and the contact person's own phone or e-mail goes to the contact. The sender is linked
        // by now, so this is the one place it can be done; a failure leaves the reading as it was.
        try {
            val offered = suggestOrganisationDetails.get()(documentId)
            log(
                Log.DEBUG,
                "suggestions for $documentId: offered=${offered.offered} contactFilled=${offered.contactFilled} skipped=${offered.skipped}",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log(Log.WARN, "organisation suggestions failed for $documentId: ${e.javaClass.simpleName}")
        }

        return Outcome(linked, created, ignored, contact)
    }

    /**
     * The reading settled [profileId] as the letter's sender organisation: an organisation an earlier reading linked as the sender of the
     * same letter stops being it ([ReplaceStaleSenderUseCase]), so the contact and the suggestions below go to the right one.
     */
    private suspend fun settleSender(documentId: String, entity: RecognisedEntity, role: ProfileRole, profileId: String) {
        if (role != ProfileRole.SENDER || !isOrganisation(entity.kind)) return
        try {
            val replaced = replaceStaleSender(documentId, profileId)
            if (replaced > 0) log(Log.INFO, "sender of $documentId: $replaced earlier sender organisation(s) unlinked")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log(Log.WARN, "earlier sender not unlinked for $documentId: ${e.javaClass.simpleName}")
        }
    }

    /** android.util.Log is not available in a JVM unit test. */
    private fun log(priority: Int, text: String) {
        runCatching { Log.println(priority, TAG, text) }
    }

    /** The scores of the same-person question, ids and numbers only (no name), at debug level. */
    private fun logContactDecision(documentId: String, outcome: ContactLinkOutcome) {
        val decision = when (outcome) {
            is ContactLinkOutcome.Matched -> outcome.decision
            is ContactLinkOutcome.Created -> outcome.decision
            else -> null
        }
        val text = buildString {
            append("contact for ").append(documentId).append(": ").append(outcome::class.simpleName)
            if (outcome is ContactLinkOutcome.Pending) append(" ").append(outcome.reason)
            decision?.let { d ->
                append(" matched=").append(d.matchedId).append(" baseline=").append(d.baseline).append(" margin=").append(d.margin)
                append(" scores=").append(d.asked.joinToString { "${it.candidateId}:${it.score}" })
            }
        }
        // android.util.Log is not available in a JVM unit test.
        runCatching { Log.d(TAG, text) }
    }

    /**
     * Records that the user does not want this entity acted on for this document again.
     *
     * Called via [com.postsaimanager.core.data.repository.ProfileRepositoryImpl.deleteProfile] when a
     * machine-created profile is deleted. The next [process] of the same document
     * must see it and stop, exactly as a deleted [com.postsaimanager.core.model.ExtractedData]
     * field stays deleted.
     */
    suspend fun dismiss(documentId: String, entityName: String) {
        dismissedEntityDao.dismiss(
            DismissedEntityEntity(
                documentId, normaliseEntityName(entityName), System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun createAndLink(
        documentId: String,
        entityKey: String,
        action: EntityLinkingUseCase.Action.Create,
    ): String? {
        val now = System.currentTimeMillis()
        val profile = Profile(
            id = UuidGenerator.generate(),
            kind = action.kind,
            name = action.name,
            organization = action.organization,
            sourceDocumentId = documentId,
            sourceEntityName = entityKey,
            createdAt = now,
            modifiedAt = now,
        )

        val result = profileRepository.createProfile(profile)
        if (result !is PamResult.Success) return null
        profileRepository.linkProfileToDocument(profile.id, documentId, action.role)
        return profile.id
    }

    /**
     * Looks up the best existing profile for an entity, using the scoring of [ProfileMatcher].
     *
     * An organisation entity is matched by organisation; a person is matched by name. (A contact person is never matched here: it
     * is not a profile.)
     */
    private suspend fun findMatch(entity: RecognisedEntity): EntityLinkingUseCase.MatchCandidate? {
        val isOrg = isOrganisation(entity.kind)
        val name = entity.name.takeUnless { isOrg }
        val organization = if (isOrg) entity.name else null

        // An organisation entity may only match its own AUTHORITY profile, never a person's
        // profile that merely shares its `organization` string (see findBestMatch's doc).
        val (profile, confidence) = profileMatcher.findBestMatch(
            name, organization,
            profileKind = { kind -> isOrg == (kind == ProfileKind.ORGANISATION) },
        )
        profile ?: return null

        return EntityLinkingUseCase.MatchCandidate(
            profileId = profile.id,
            kind = profile.kind,
            isSelf = profile.isSelf,
            isExactMatch = confidence >= ProfileMatcher.EXACT_MATCH_CONFIDENCE,
        )
    }

    private fun isOrganisation(kind: EntityKind): Boolean =
        kind == EntityKind.AUTHORITY || kind == EntityKind.COMPANY

    private companion object {
        const val TAG = "EntityProfileLinker"
    }
}
