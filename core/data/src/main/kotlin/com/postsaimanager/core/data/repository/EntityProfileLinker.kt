package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.domain.document.normaliseEntityName
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.usecase.EntityLinkingUseCase
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileType
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
) {

    data class Outcome(
        val linked: Int,
        val created: Int,
        /** Dismissed before, this run — not a count of everything skipped for any reason. */
        val ignoredAsDismissed: Int,
    )

    suspend fun process(documentId: String, understanding: DocumentUnderstanding): Outcome {
        var linked = 0
        var created = 0
        var ignored = 0

        // The one SENDER entity in the document, if any — see rule 3: a contact is only ever
        // auto-created when there is an organisation on this document to attach it to.
        val senderOrganisation = understanding.sender
            ?.takeIf { isOrganisation(it.kind) }
            ?.name

        for (entity in understanding.entities) {
            if (entity.name.isBlank()) continue

            val key = normaliseEntityName(entity.name)
            val dismissed = dismissedEntityDao.isDismissed(documentId, key)
            val match = if (dismissed) null else findMatch(entity, senderOrganisation)

            when (val action = decide.decide(entity, match, senderOrganisation, dismissed)) {
                is EntityLinkingUseCase.Action.Ignore -> ignored++

                is EntityLinkingUseCase.Action.Link -> {
                    val result = profileRepository.linkProfileToDocument(
                        action.profileId, documentId, action.role,
                    )
                    if (result is PamResult.Success) linked++
                }

                is EntityLinkingUseCase.Action.Create -> {
                    if (createAndLink(documentId, key, action)) created++
                }

                is EntityLinkingUseCase.Action.Propose -> Unit
            }
        }

        return Outcome(linked, created, ignored)
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
    ): Boolean {
        val now = System.currentTimeMillis()
        val profile = Profile(
            id = UuidGenerator.generate(),
            type = action.profileType,
            name = action.name,
            organization = action.organization,
            sourceDocumentId = documentId,
            sourceEntityName = entityKey,
            createdAt = now,
            modifiedAt = now,
        )

        val result = profileRepository.createProfile(profile)
        if (result !is PamResult.Success) return false
        profileRepository.linkProfileToDocument(profile.id, documentId, action.role)
        return true
    }

    /**
     * Looks up the best existing profile for an entity, using the scoring of [ProfileMatcher].
     *
     * An organisation entity is matched by organisation; a person is matched by name, plus
     * organisation when they are a [EntityRole.SENDER_CONTACT] — "Frau Müller" should match
     * the "Frau Müller" already on file *at this Jobcenter*, not a namesake elsewhere.
     */
    private suspend fun findMatch(
        entity: RecognisedEntity,
        senderOrganisation: String?,
    ): EntityLinkingUseCase.MatchCandidate? {
        val isOrg = isOrganisation(entity.kind)
        val name = entity.name.takeUnless { isOrg }
        val organization = when {
            isOrg -> entity.name
            entity.role == EntityRole.SENDER_CONTACT -> senderOrganisation
            else -> null
        }

        // An organisation entity may only match its own AUTHORITY profile, never a person's
        // profile that merely shares its `organization` string (see findBestMatch's doc).
        val (profile, confidence) = profileMatcher.findBestMatch(
            name, organization,
            profileType = { type -> isOrg == (type == ProfileType.AUTHORITY) },
        )
        profile ?: return null

        return EntityLinkingUseCase.MatchCandidate(
            profileId = profile.id,
            profileType = profile.type,
            isExactMatch = confidence >= ProfileMatcher.EXACT_MATCH_CONFIDENCE,
        )
    }

    private fun isOrganisation(kind: EntityKind): Boolean =
        kind == EntityKind.AUTHORITY || kind == EntityKind.COMPANY
}
