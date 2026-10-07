package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.DocumentUnderstanding.Companion.AUTO_LINK_CONFIDENCE
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.RecognisedEntity
import javax.inject.Inject

/**
 * Decides what to do with one [RecognisedEntity] the model found in a document: link it to an
 * existing profile, create a new one, propose it for the user to confirm, or ignore it.
 *
 * ### Role gates the action, not confidence alone
 *
 * [RecognisedEntity.confidence] answers "did we read this correctly" — it is a grounding
 * score against the page (see `ExtractionConfidence`). It does not answer "should we act on
 * this". A spouse mentioned in a letter can be read perfectly (0.95) and still not warrant a
 * profile: nothing about the letter says the app should be tracking that person. [EntityRole]
 * is what answers that question, so it is checked first and confidence only ever narrows what
 * the role already allows.
 *
 * The rule table, and the reasoning behind each row:
 *
 * | Role | Link to a match | Create new |
 * |---|---|---|
 * | `SENDER` | auto, if confident and the match is confident | auto, if confident and no match exists |
 * | `RECIPIENT` | auto, only to an existing `USER_SELF` profile | never — always [Action.Propose] |
 * | `SENDER_CONTACT` | never a profile: [Action.AttachContact] | never a profile: [Action.AttachContact] |
 * | `MENTIONED` | auto, if confident and the match is confident | never — always [Action.Propose] |
 *
 * A contact person is not a profile: it is a person inside the sender's organisation, attached to that organisation by
 * `LinkSenderContactUseCase` (which asks the same-person question and creates or updates a contact). This class only says "that is the
 * contact's job"; it neither matches nor creates anything for it.
 *
 * The asymmetry behind never auto-creating for `RECIPIENT` and `MENTIONED`: a missing link
 * costs the user one tap to add later. A wrongly created profile has to be found, understood
 * and deleted, and pollutes every list it appears in until then. "Who the user is" additionally
 * can never be *silently* decided at all — the "Me" role ([HouseholdRole.SELF]) is set up once by the user — because a wrong guess
 * there is not fixed by deleting one profile; it is wrong in every document from then on.
 *
 * ### Why a weak match blocks auto-create too
 *
 * The table above only names [RecognisedEntity.confidence]. A second signal is folded in here:
 * whether the best matching existing profile is itself a *confident* match
 * ([MatchCandidate.isExactMatch], mirroring `ProfileMatcher.EXACT_MATCH_CONFIDENCE`). A
 * correctly-read sender name that weakly resembles an existing profile is exactly the case
 * `ProfileMatcher` already treats as `POSSIBLE_MATCH` rather than `NEW_PROFILE` — creating a
 * second profile there would produce the duplicate rule 5 exists to prevent, and silently
 * linking it would risk filing a letter under the wrong correspondent. Both are worse than
 * asking, so a weak match routes to [Action.Propose] instead of either extreme.
 *
 * Pure: no repository, no clock, no IO. The caller ([EntityProfileLinker] in `:core:data`) does
 * the matching and the dismissal lookup and hands the results in, so every branch here is
 * testable without a database.
 */
class EntityLinkingUseCase @Inject constructor() {

    /**
     * The best existing profile [EntityProfileLinker] found for an entity, already scored.
     *
     * @param isExactMatch Strong enough to act on without asking — see
     *   `ProfileMatcher.EXACT_MATCH_CONFIDENCE`. A candidate below that bar is not passed as
     *   `null` outright so [Action.Propose] can still reference it (`existingProfileId`), but
     *   it never drives a silent [Action.Link] or blocks an [Action.Create].
     */
    data class MatchCandidate(
        val profileId: String,
        val kind: ProfileKind,
        /** The matched profile is the "Me" profile ([HouseholdRole.SELF]). */
        val isSelf: Boolean,
        val isExactMatch: Boolean,
    )

    sealed interface Action {
        data class Link(val profileId: String, val role: ProfileRole) : Action

        data class Create(
            val kind: ProfileKind,
            val name: String,
            val organization: String?,
            val role: ProfileRole,
        ) : Action

        /** Nothing acted on automatically — the user is asked. [householdRole] is the role the proposal is about (SELF for "is this you?"). */
        data class Propose(
            val role: ProfileRole,
            val kind: ProfileKind,
            val householdRole: HouseholdRole?,
            val organization: String?,
            val existingProfileId: String?,
        ) : Action

        /** The entity is the letter's contact person: no profile; it is attached to the sender organisation as a contact. */
        data object AttachContact : Action

        /** Dismissed for this document before, or nothing worth doing. */
        data object Ignore : Action
    }

    fun decide(
        entity: RecognisedEntity,
        match: MatchCandidate?,
        isDismissed: Boolean,
    ): Action {
        // Mirrors MergeExtractionUseCase's tombstone: once the user has said no, reprocessing
        // must not ask again or recreate what they removed. Checked before anything else so no
        // other branch below can override it.
        if (isDismissed) return Action.Ignore

        // A contact person is a person inside the sender's organisation, never a profile: the contact linking owns it.
        if (entity.role == EntityRole.SENDER_CONTACT) return Action.AttachContact

        val role = targetRole(entity.role)
        val kind = targetKind(entity)
        val confident = entity.confidence >= AUTO_LINK_CONFIDENCE
        val confidentMatch = match != null && match.isExactMatch

        return when (entity.role) {
            EntityRole.SENDER -> when {
                confidentMatch && confident -> Action.Link(match!!.profileId, role)
                match == null && confident ->
                    Action.Create(kind, entity.name, organisationNameOf(entity), role)
                else -> Action.Propose(role, kind, null, organisationNameOf(entity), match?.profileId)
            }

            // Never auto-created: see the class doc on the "Me" role. Only ever linked to a
            // profile already marked as the user, and only when that link itself is confident.
            EntityRole.RECIPIENT -> when {
                confidentMatch && confident && match!!.isSelf -> Action.Link(match.profileId, role)
                else -> Action.Propose(role, ProfileKind.PERSON, HouseholdRole.SELF, null, match?.profileId)
            }

            EntityRole.SENDER_CONTACT -> Action.AttachContact

            // Never auto-created: a name in the body of a letter — a spouse, a child, a third
            // party — is not something the sender's letter licenses the app to start tracking.
            EntityRole.MENTIONED -> when {
                confidentMatch && confident -> Action.Link(match!!.profileId, role)
                else -> Action.Propose(role, kind, null, organisationNameOf(entity), match?.profileId)
            }
        }
    }

    /** Only organisations carry an `organization` value; a person's own name is not one. */
    private fun organisationNameOf(entity: RecognisedEntity): String? =
        if (isOrganisation(entity.kind)) entity.name else null

    private fun isOrganisation(kind: EntityKind): Boolean =
        kind == EntityKind.AUTHORITY || kind == EntityKind.COMPANY

    /** An authority and a company are both an organisation profile; anything else is a person. */
    private fun targetKind(entity: RecognisedEntity): ProfileKind =
        if (isOrganisation(entity.kind)) ProfileKind.ORGANISATION else ProfileKind.PERSON

    /** The profile role of a role that links to a profile; a contact person never does ([EntityRole.SENDER_CONTACT] is handled before). */
    private fun targetRole(role: EntityRole): ProfileRole = when (role) {
        EntityRole.SENDER -> ProfileRole.SENDER
        EntityRole.RECIPIENT -> ProfileRole.RECEIVER
        EntityRole.SENDER_CONTACT, EntityRole.MENTIONED -> ProfileRole.RELATED
    }
}
