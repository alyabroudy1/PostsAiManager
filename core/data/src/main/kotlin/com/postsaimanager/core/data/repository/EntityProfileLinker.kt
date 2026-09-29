package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.dao.EntityProposalDao
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.data.database.entity.EntityProposalEntity
import com.postsaimanager.core.domain.document.EntityProposalService
import com.postsaimanager.core.domain.document.normaliseEntityName
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.usecase.EntityLinkingUseCase
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityProposal
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.RecognisedEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * Every entity is handled independently, and a failure on one must not stop the rest — a
 * document with three good entities and one that could not be linked should keep the three.
 *
 * Also implements [EntityProposalService]: a `Propose` decision above is persisted via
 * [entityProposalDao] rather than only returned in [Outcome], and this class is the one place
 * that already knows how to turn an accepted proposal into either a link to the profile the
 * proposal already carried, or the same create-and-link a confident `Create` decision
 * performs, depending on whether that profile still exists (task 7.14.11b — see
 * [MIGRATION_5_6][com.postsaimanager.core.data.database.PamMigrations.MIGRATION_5_6]).
 */
@Singleton
class EntityProfileLinker @Inject constructor(
    private val profileMatcher: ProfileMatcher,
    private val profileRepository: ProfileRepository,
    private val dismissedEntityDao: DismissedEntityDao,
    private val entityProposalDao: EntityProposalDao,
    private val decide: EntityLinkingUseCase,
) : EntityProposalService {

    /** One entity the app would like the user to confirm — never acted on automatically. */
    data class Proposal(
        val entityName: String,
        val kind: EntityKind,
        val entityRole: EntityRole,
        val relation: String,
        val role: ProfileRole,
        val profileType: ProfileType,
        val organization: String?,
        val existingProfileId: String?,
    )

    data class Outcome(
        val linked: Int,
        val created: Int,
        /** Dismissed before, this run — not a count of everything skipped for any reason. */
        val ignoredAsDismissed: Int,
        val proposals: List<Proposal>,
    )

    suspend fun process(documentId: String, understanding: DocumentUnderstanding): Outcome {
        var linked = 0
        var created = 0
        var ignored = 0
        val proposals = mutableListOf<Proposal>()

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

                is EntityLinkingUseCase.Action.Propose -> {
                    proposals += Proposal(
                        entityName = entity.name,
                        kind = entity.kind,
                        entityRole = entity.role,
                        relation = entity.relation,
                        role = action.role,
                        profileType = action.profileType,
                        organization = action.organization,
                        existingProfileId = action.existingProfileId,
                    )

                    // IGNORE on the (documentId, entityNameKey) unique index — see
                    // EntityProposalDao.insert — so re-running extraction on a document that
                    // already has an unanswered proposal for Layla leaves the one row the
                    // user may already be looking at, rather than a second one under a new id.
                    entityProposalDao.insert(
                        EntityProposalEntity(
                            id = UuidGenerator.generate(),
                            documentId = documentId,
                            entityName = entity.name,
                            entityNameKey = key,
                            kind = entity.kind.name,
                            entityRole = entity.role.name,
                            relation = entity.relation,
                            role = action.role.name,
                            profileType = action.profileType.name,
                            organization = action.organization,
                            existingProfileId = action.existingProfileId,
                            confidence = entity.confidence,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

        return Outcome(linked, created, ignored, proposals)
    }

    override fun pendingProposals(documentId: String): Flow<List<EntityProposal>> =
        entityProposalDao.getForDocument(documentId).map { rows -> rows.map(::toDomain) }

    /**
     * Links to [EntityProposal.existingProfileId] when that profile still exists; otherwise
     * creates a profile exactly as a confident [EntityLinkingUseCase.Action.Create] would,
     * then links it. Either way the proposal is resolved so [pendingProposals] does not
     * return it again.
     *
     * A weak match is carried on the proposal (see that property's doc) precisely so this
     * moment can act on it: the user's "yes" means "yes, that is the organisation/person I
     * already have on file", and linking is what makes that true rather than filing a second,
     * duplicate profile for something the app already knew about. [ProfileMatcher] and
     * [profileRepository]'s own `linkExistingProfile` were considered here, but both are
     * built around a [com.postsaimanager.core.model.ProfileSuggestion] carrying extracted
     * contact fields (phone/email/address) to backfill onto the profile — an [EntityProposal]
     * has none of those, so a plain [ProfileRepository.linkProfileToDocument] call is the
     * right amount of machinery, not a reason to force-fit the suggestion type.
     *
     * A stale id — the profile was deleted after the proposal was raised, discovered via
     * [ProfileRepository.getProfileById] — falls back to create rather than surfacing as an
     * error the user never asked about: they said "yes, add this", and they should get a
     * profile either way.
     */
    override suspend fun accept(proposal: EntityProposal): PamResult<Unit> {
        val existingId = proposal.existingProfileId
        val stillExists = existingId != null &&
            profileRepository.getProfileById(existingId) is PamResult.Success
        if (existingId != null && stillExists) {
            profileRepository.linkProfileToDocument(existingId, proposal.documentId, proposal.role)
            entityProposalDao.delete(proposal.id)
            return PamResult.Success(Unit)
        }

        val now = System.currentTimeMillis()
        val profile = Profile(
            id = UuidGenerator.generate(),
            type = proposal.profileType,
            name = proposal.entityName,
            organization = proposal.organization,
            sourceDocumentId = proposal.documentId,
            sourceEntityName = normaliseEntityName(proposal.entityName),
            createdAt = now,
            modifiedAt = now,
        )

        return when (val result = profileRepository.createProfile(profile)) {
            is PamResult.Error -> result
            is PamResult.Success -> {
                profileRepository.linkProfileToDocument(
                    profile.id, proposal.documentId, proposal.role,
                )
                entityProposalDao.delete(proposal.id)
                PamResult.Success(Unit)
            }
        }
    }

    /**
     * Tombstones [proposal]'s entity the same way [dismiss] already does for an explicit "no",
     * then resolves the proposal row itself so it stops appearing on the document.
     */
    override suspend fun dismiss(proposal: EntityProposal): PamResult<Unit> {
        dismiss(proposal.documentId, proposal.entityName)
        entityProposalDao.delete(proposal.id)
        return PamResult.Success(Unit)
    }

    private fun toDomain(entity: EntityProposalEntity): EntityProposal = EntityProposal(
        id = entity.id,
        documentId = entity.documentId,
        entityName = entity.entityName,
        kind = EntityKind.valueOf(entity.kind),
        entityRole = EntityRole.valueOf(entity.entityRole),
        relation = entity.relation,
        role = ProfileRole.valueOf(entity.role),
        profileType = ProfileType.valueOf(entity.profileType),
        organization = entity.organization,
        existingProfileId = entity.existingProfileId,
        confidence = entity.confidence,
        createdAt = entity.createdAt,
    )

    /**
     * Records that the user does not want this entity acted on for this document again.
     *
     * Called both for an explicit "no" on a [Proposal] and — via
     * [com.postsaimanager.core.data.repository.ProfileRepositoryImpl.deleteProfile] — when a
     * machine-created profile is deleted. Either way the next [process] of the same document
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
     * Looks up the best existing profile for an entity, using exactly the scoring
     * [ProfileMatcher] already applies to extracted fields — see its class doc.
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
