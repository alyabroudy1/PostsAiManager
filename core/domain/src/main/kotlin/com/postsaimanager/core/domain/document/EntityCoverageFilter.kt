package com.postsaimanager.core.domain.document

import com.postsaimanager.core.model.EntityProposal
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileSuggestion

/**
 * Comparison key for a name or organisation string: trimmed and lower-cased so incidental
 * whitespace or casing differences are never treated as two different people.
 *
 * The single implementation of this rule. It used to live private inside `EntityProfileLinker`
 * in `:core:data`, where it built dismissal keys; lifted here (task 7.14.11d) so
 * [EntityCoverageFilter] can compare names the exact same way instead of growing a second,
 * quietly-driftable copy — `EntityProfileLinker` now calls this too.
 */
fun normaliseEntityName(name: String): String = name.trim().lowercase()

/**
 * Removes field-based [ProfileSuggestion]s that ask about a person the entity path already
 * covers for the same document — task 7.14.11d.
 *
 * Before this, a single Jobcenter letter could put two cards about the same Jobcenter in front
 * of the user: a [ProfileSuggestion] from `ProfileMatchingService`, derived from extracted
 * fields such as "Sender Organization", and an [EntityProposal] from `EntityProposalService`,
 * derived from the AI's recognised entities. Same person, different wording, different
 * buttons, right next to each other.
 *
 * This dedupes rather than suppressing the field path outright, because the two paths are not
 * interchangeable:
 * - On a device with no chat model installed, extraction falls back to the regex extractor,
 *   which never produces a [com.postsaimanager.core.model.RecognisedEntity] and so never
 *   produces an [EntityProposal] or an entity-linked profile either. [apply] is then a no-op —
 *   [pendingProposals] and [entityLinkedProfiles] are both empty — and every field suggestion
 *   is shown exactly as it was before this filter existed.
 * - Even with a model installed, the entity path can legitimately find *less* than the field
 *   path for the same document: a real run of Qwen3.5 2B against a German letter identified no
 *   sender at all, while the field extractor still read "Sender Organization" from the layout.
 *   Suppressing field suggestions whenever the AI ran at all — rather than only when it named
 *   this specific person — would leave that document with no profile prompt whatsoever, which
 *   is strictly worse than the duplicate this filter removes.
 */
object EntityCoverageFilter {

    /**
     * @param documentId The document [suggestions] belongs to.
     * @param suggestions Field-based suggestions about to reach the UI.
     * @param pendingProposals [EntityProposal]s still awaiting an answer for [documentId] — see
     *   `EntityProposalService.pendingProposals`.
     * @param entityLinkedProfiles Every profile currently linked to [documentId] (see
     *   `ProfileRepository.getProfilesForDocument`), regardless of which subsystem linked it or
     *   which document originally created it. All of them are coverage: the question a
     *   [ProfileSuggestion] asks is "shall I create or link a profile for this person?", and if
     *   a profile for that person is already linked to this document, the question is moot no
     *   matter who linked it. [Profile.sourceDocumentId] is deliberately *not* consulted here —
     *   see that property's doc — because it names who created the row, not who it currently
     *   covers: a profile `Action.Link` attaches to a second letter from the same Jobcenter
     *   keeps the `sourceDocumentId` of the *first* letter, so filtering on it would make the
     *   duplicate-card bug reappear on every repeat sender, which is the common case, not an
     *   edge case.
     */
    fun apply(
        documentId: String,
        suggestions: List<ProfileSuggestion>,
        pendingProposals: List<EntityProposal>,
        entityLinkedProfiles: List<Profile>,
    ): List<ProfileSuggestion> {
        val covered = coveredNames(pendingProposals, entityLinkedProfiles)
        if (covered.isEmpty()) return suggestions
        return suggestions.filterNot { isCovered(it, covered) }
    }

    private fun coveredNames(
        pendingProposals: List<EntityProposal>,
        entityLinkedProfiles: List<Profile>,
    ): Set<String> {
        val fromProposals = pendingProposals
            .flatMap { listOfNotNull(it.entityName, it.organization) }
        val fromEntityLinkedProfiles = entityLinkedProfiles
            .flatMap { listOfNotNull(it.name, it.organization) }
        return (fromProposals + fromEntityLinkedProfiles).map(::normaliseEntityName).toSet()
    }

    /**
     * A [ProfileSuggestion] carries both [ProfileSuggestion.extractedName] and
     * [ProfileSuggestion.extractedOrganization] — a "Sender" suggestion for a Jobcenter letter
     * typically has an organisation but no personal name, while one for an individual has a
     * name but no organisation. The entity path can likewise know the same party by either
     * string: an AUTHORITY/COMPANY entity is proposed by its organisation name, a PERSON by
     * their own name. Neither side is "the" identifying field, so a match on *either* side is
     * treated as the same party — an entity proposal named "Jobcenter Berlin" must hide a field
     * suggestion whose `extractedOrganization` is "Jobcenter Berlin", even though that
     * suggestion's `extractedName` is null.
     */
    private fun isCovered(suggestion: ProfileSuggestion, covered: Set<String>): Boolean {
        val candidates = listOfNotNull(suggestion.extractedName, suggestion.extractedOrganization)
        return candidates.any { normaliseEntityName(it) in covered }
    }
}
