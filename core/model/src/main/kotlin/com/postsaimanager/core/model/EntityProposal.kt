package com.postsaimanager.core.model

/**
 * A recognised entity `EntityLinkingUseCase` decided to ask about rather than act on
 * automatically — see `EntityLinkingUseCase.Action.Propose`.
 *
 * Persisted rather than returned-and-dropped: a proposal is a question the app asked about a
 * specific document, and forgetting it the moment the process exits means the user never
 * learns that, say, Sam's wife Layla was found in the letter at all. It lives with the
 * document that produced it (see [documentId]) rather than in one global inbox, so a hundred
 * scanned documents cannot turn into a hundred-item list to work through — see
 * `core.domain.document.EntityProposalService`'s class doc.
 *
 * @param id Stable identity so `EntityProposalService.accept`/`.dismiss` can resolve exactly
 *   this row, independent of a later re-scan finding the same name again.
 * @param entityRole What the entity was doing in the document (recipient, mentioned, ...).
 *   Distinct from [role], the profile role the app would link it as if accepted.
 * @param relation Free text such as "spouse of the recipient" — empty when the document did
 *   not say. Carried through so the UI copy can be specific rather than generic.
 * @param existingProfileId A weak match `EntityLinkingUseCase` found but was not confident
 *   enough to link to silently. Acted on by `EntityProposalService.accept`: if the profile
 *   still exists, accepting links to it instead of creating a duplicate; if it has since been
 *   deleted, accepting creates fresh, exactly as when this is null.
 */
data class EntityProposal(
    val id: String,
    val documentId: String,
    val entityName: String,
    val kind: EntityKind,
    val entityRole: EntityRole,
    val relation: String,
    val role: ProfileRole,
    val profileType: ProfileType,
    val organization: String?,
    val existingProfileId: String?,
    val confidence: Float,
    val createdAt: Long,
)
