package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.DocumentUnderstanding.Companion.AUTO_LINK_CONFIDENCE
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
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
 * | `SENDER_CONTACT` | auto, if confident and the match is confident | auto, only when the sender organisation is known |
 * | `MENTIONED` | auto, if confident and the match is confident | never — always [Action.Propose] |
 *
 * The asymmetry behind never auto-creating for `RECIPIENT` and `MENTIONED`: a missing link
 * costs the user one tap to add later. A wrongly created profile has to be found, understood
 * and deleted, and pollutes every list it appears in until then. "Who the user is" additionally
 * can never be *silently* decided at all — see [ProfileType.USER_SELF] — because a wrong guess
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
        val profileType: ProfileType,
        val isExactMatch: Boolean,
    )

    sealed interface Action {
        data class Link(val profileId: String, val role: ProfileRole) : Action

        data class Create(
            val profileType: ProfileType,
            val name: String,
            val organization: String?,
            val role: ProfileRole,
        ) : Action

        /** Nothing acted on automatically — the user is asked. */
        data class Propose(
            val role: ProfileRole,
            val profileType: ProfileType,
            val organization: String?,
            val existingProfileId: String?,
        ) : Action

        /** Dismissed for this document before, or nothing worth doing. */
        data object Ignore : Action
    }

    fun decide(
        entity: RecognisedEntity,
        match: MatchCandidate?,
        senderOrganisation: String?,
        isDismissed: Boolean,
    ): Action {
        // Mirrors MergeExtractionUseCase's tombstone: once the user has said no, reprocessing
        // must not ask again or recreate what they removed. Checked before anything else so no
        // other branch below can override it.
        if (isDismissed) return Action.Ignore

        val role = targetRole(entity.role)
        val profileType = targetProfileType(entity)
        val confident = entity.confidence >= AUTO_LINK_CONFIDENCE
        val confidentMatch = match != null && match.isExactMatch

        return when (entity.role) {
            EntityRole.SENDER -> when {
                confidentMatch && confident -> Action.Link(match!!.profileId, role)
                match == null && confident ->
                    Action.Create(profileType, entity.name, organisationNameOf(entity), role)
                else -> Action.Propose(role, profileType, organisationNameOf(entity), match?.profileId)
            }

            // Never auto-created: see the class doc on USER_SELF. Only ever linked to a
            // profile already marked as the user, and only when that link itself is confident.
            EntityRole.RECIPIENT -> when {
                confidentMatch && confident && match!!.profileType == ProfileType.USER_SELF ->
                    Action.Link(match.profileId, role)
                else -> Action.Propose(role, ProfileType.USER_SELF, null, match?.profileId)
            }

            // A contact is a PERSON that belongs to the sender's organisation (rule 3). With
            // no sender entity in this document there is no organisation to attach — creating
            // the profile anyway would be exactly the orphan-signature-line clutter rule 1
            // exists to prevent, so it is proposed instead.
            EntityRole.SENDER_CONTACT -> when {
                confidentMatch && confident -> Action.Link(match!!.profileId, role)
                match == null && confident && senderOrganisation != null ->
                    Action.Create(ProfileType.PERSON, entity.name, senderOrganisation, role)
                else -> Action.Propose(role, ProfileType.PERSON, senderOrganisation, match?.profileId)
            }

            // Never auto-created: a name in the body of a letter — a spouse, a child, a third
            // party — is not something the sender's letter licenses the app to start tracking.
            EntityRole.MENTIONED -> when {
                confidentMatch && confident -> Action.Link(match!!.profileId, role)
                else -> Action.Propose(role, profileType, organisationNameOf(entity), match?.profileId)
            }
        }
    }

    /** Only organisations carry an `organization` value; a person's own name is not one. */
    private fun organisationNameOf(entity: RecognisedEntity): String? =
        if (isOrganisation(entity.kind)) entity.name else null

    private fun isOrganisation(kind: EntityKind): Boolean =
        kind == EntityKind.AUTHORITY || kind == EntityKind.COMPANY

    /**
     * [ProfileType] has no dedicated `COMPANY` bucket, so a company entity lands on `AUTHORITY`
     * — the same choice `ProfileMatcher.createAndLinkProfile` already makes for any profile
     * carrying an organisation name. Kept consistent with that rather than introducing a
     * second convention for the same distinction.
     */
    private fun targetProfileType(entity: RecognisedEntity): ProfileType =
        if (isOrganisation(entity.kind)) ProfileType.AUTHORITY else ProfileType.PERSON

    private fun targetRole(role: EntityRole): ProfileRole = when (role) {
        EntityRole.SENDER -> ProfileRole.SENDER
        EntityRole.RECIPIENT -> ProfileRole.RECEIVER
        EntityRole.SENDER_CONTACT -> ProfileRole.CASE_WORKER
        EntityRole.MENTIONED -> ProfileRole.RELATED
    }
}
