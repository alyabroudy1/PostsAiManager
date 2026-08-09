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
    private val deviceCapability: DeviceCapabilityChecker,
) : ActiveModelProvider {

    override suspend fun activeModelPath(): String? {
        // Reconcile first: an index entry whose file has vanished would otherwise hand the
        // engine a path that fails to load, reported as a model error rather than a
        // missing file.
        installedStore.reconcile()
        return installedStore.activeModel()?.filePath
    }

    /**
     * What the device can **afford**, not what the model supports.
     *
     * These are different numbers and conflating them crashes the app. The context window
     * is a KV cache allocated up front and proportional to its size: Qwen3.5 2B catalogues
     * 32k, and asking for all of it on a phone with 2.5 GB free killed the inference
     * process inside `llama_decode` — a native abort, so nothing catchable, just a dead
     * process and a document that failed to be read.
     *
     * The catalogued value stays the ceiling, since the model genuinely cannot go beyond
     * it. What is subtracted is reality.
     *
     * The bands are deliberately generous toward the small end. The failure is not a
     * degraded answer but a native abort that takes the inference process with it, and the
     * cost of a smaller window on a one-page letter is nothing — its layout description is
     * a few thousand characters either way.
     *
     * The steps are deliberately coarse. Exact KV cost depends on layer count, head count
     * and head dimension, none of which the catalog records; a heuristic that is roughly
     * right and always conservative is worth more than a precise formula fed from values
     * this layer does not have. Task 7.2.9 — measuring peak RSS per model — is what would
     * replace it.
     */
    override suspend fun activeModelContextTokens(): Int {
        val catalogued = installedStore.activeModel()?.contextTokens ?: DEFAULT_CONTEXT_TOKENS
        val available = deviceCapability.current().availableRamBytes

        val affordable = when {
            available < 1500 * MB -> 2048
            // Ceiling of 4096 everywhere, on evidence rather than caution. A device test on
            // the reference phone generated happily at 4096 and the app aborted at 8192 —
            // after `am kill-all`, with more memory free than when 4096 succeeded. So
            // `availMem` does not predict whether the allocation lands, and the failure is
            // not a degraded answer but a native abort that kills the inference process.
            //
            // Nothing is lost meanwhile: a one-page letter's layout description is a few
            // thousand characters, and chat grounds on a handful of retrieved passages.
            // The larger tiers come back when 7.2.9 measures peak RSS per model, which is
            // the number this should key off instead of a guess.
            else -> 4096
        }
        return minOf(catalogued, affordable)
    }

    private companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4096
        const val MB = 1024L * 1024L
        const val GB = 1024L * MB
    }
}
