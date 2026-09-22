package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.model.InstalledModelSummary
import kotlinx.coroutines.flow.Flow

/**
 * What a feature needs to list installed models and switch the active chat model, without
 * reaching into `:core:ai:catalog` — see `FeatureBoundaryKonsistTest` and
 * `documentation/02-architecture.md` §10 rule 1. `feature:models` has a sanctioned exception
 * for full catalog management (browsing, downloading, importing); this port exists so the
 * chat screen's much smaller need — "which models are installed, which is active, switch
 * it" — does not have to borrow that exception too.
 *
 * Implemented in `:core:ai:catalog` over `InstalledModelStore`.
 */
interface InstalledModelsRepository {
    val installed: Flow<List<InstalledModelSummary>>

    /** The chat model's id, or null when none is installed yet. */
    val activeModelId: Flow<String?>

    /** No-ops if [modelId] is not an installed model's id. */
    suspend fun setActive(modelId: String)
}
