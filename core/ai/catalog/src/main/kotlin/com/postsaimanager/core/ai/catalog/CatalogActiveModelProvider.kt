package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exposes the user's chosen model to the domain layer, without leaking the catalog.
 *
 * `:core:domain` needs to know *which file* to load — not how models are downloaded,
 * verified, or indexed. This adapter is the whole of that seam.
 */
@Singleton
class CatalogActiveModelProvider @Inject constructor(
    private val installedStore: InstalledModelStore,
) : ActiveModelProvider {

    override suspend fun activeModelPath(): String? {
        // Reconcile first: an index entry whose file has vanished would otherwise hand the
        // engine a path that fails to load, reported as a model error rather than a
        // missing file.
        installedStore.reconcile()
        return installedStore.activeModel()?.filePath
    }

    override suspend fun activeModelContextTokens(): Int =
        installedStore.activeModel()?.contextTokens ?: DEFAULT_CONTEXT_TOKENS

    private companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4096
    }
}
