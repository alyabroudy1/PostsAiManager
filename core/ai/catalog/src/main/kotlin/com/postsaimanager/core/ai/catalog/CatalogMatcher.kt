package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.InstalledModel

/**
 * Recognises a side-loaded GGUF as a catalog model by what the file is, not what it is called: the SHA-256 of its bytes (the
 * catalog pins one per model) and its size. A file that matches is the catalog's model, so everything keyed by the descriptor
 * (its inference config, `ModelProfiles`, the form agent's model) applies to it as if it had been downloaded.
 */
object CatalogMatcher {

    /** The descriptor whose pinned hash is [sha256] and whose size is [sizeBytes]; null for any other file. */
    fun descriptorFor(sha256: String, sizeBytes: Long, catalog: List<AiModelDescriptor> = BundledCatalog.models): AiModelDescriptor? =
        catalog.firstOrNull { it.sha256 != null && it.sha256.equals(sha256, ignoreCase = true) && it.sizeBytes == sizeBytes }

    /** [model] with its descriptor set when it has none and its hash and size match one (the migration of earlier imports). */
    fun adopt(model: InstalledModel, catalog: List<AiModelDescriptor> = BundledCatalog.models): InstalledModel {
        if (model.descriptorId != null) return model
        val match = descriptorFor(model.sha256, model.sizeBytes, catalog) ?: return model
        return model.copy(descriptorId = match.id)
    }
}
