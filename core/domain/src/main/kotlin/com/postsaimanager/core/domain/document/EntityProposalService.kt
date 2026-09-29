package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.EntityProposal
import kotlinx.coroutines.flow.Flow

/**
 * The port through which a feature reads the entity proposals pending on a document, and
 * answers them.
 *
 * Lives in `:core:domain` because features may only see the domain layer (architecture
 * rule 1) — see [ProfileMatchingService]'s doc for the same reasoning. `:core:data`'s
 * `EntityProfileLinker` is the only implementation exposed here.
 *
 * There is deliberately no method to list every pending proposal across all documents: a
 * proposal is scoped to the document it came from and answered there, not collected into one
 * global inbox that would grow without bound — see [EntityProposal]'s class doc.
 *
 * Answering is idempotent and final, mirroring `extracted_data.deletedByUser`: the app must
 * not argue with the user once per run. [accept] creates the profile and links it, and
 * [dismiss] tombstones the entity the same way a deleted machine-created profile does — either
 * way the proposal itself is resolved and does not return from [pendingProposals] again.
 */
interface EntityProposalService {

    /** Proposals still awaiting an answer for [documentId], oldest first. */
    fun pendingProposals(documentId: String): Flow<List<EntityProposal>>

    /**
     * Creates a profile from [proposal] and links it to its document, then resolves the
     * proposal so it does not appear again.
     */
    suspend fun accept(proposal: EntityProposal): PamResult<Unit>

    /**
     * Tombstones [proposal]'s entity for its document — the same mechanism a deleted
     * machine-created profile writes — so re-processing never asks again, then resolves the
     * proposal.
     */
    suspend fun dismiss(proposal: EntityProposal): PamResult<Unit>
}
