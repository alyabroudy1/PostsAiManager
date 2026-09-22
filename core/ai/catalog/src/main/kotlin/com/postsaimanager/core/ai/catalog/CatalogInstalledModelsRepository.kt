package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.InstalledModelSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapts [InstalledModelStore] to [InstalledModelsRepository] — the seam that lets the chat
 * header/model sheet list installed models and switch the active one without depending on
 * `:core:ai:catalog` directly (architecture rule 1; see `FeatureBoundaryKonsistTest`).
 *
 * Deliberately thinner than `CatalogActiveModelProvider`: it has no device or inference
 * config to reason about, only "what is installed" and "which one chats".
 */
@Singleton
class CatalogInstalledModelsRepository @Inject constructor(
    private val installedStore: InstalledModelStore,
) : InstalledModelsRepository {

    override val installed: Flow<List<InstalledModelSummary>> =
        installedStore.installed.map { index -> index.models.map(::toSummary) }

    override val activeModelId: Flow<String?> =
        installedStore.installed.map { it.activeModelId }

    override suspend fun setActive(modelId: String) = installedStore.setActive(modelId)

    /**
     * [InstalledModel] does not carry quantization — that is catalog metadata, not a fact
     * about the file on disk — so this re-resolves it from [BundledCatalog] by
     * [InstalledModel.descriptorId], same as [CatalogActiveModelProvider.backendSpec]. Null
     * for a side-loaded model, which has no descriptor.
     */
    private fun toSummary(model: InstalledModel): InstalledModelSummary = InstalledModelSummary(
        id = model.id,
        name = model.name,
        filePath = model.filePath,
        sizeBytes = model.sizeBytes,
        quantization = model.descriptorId
            ?.let { id -> BundledCatalog.models.firstOrNull { it.id == id }?.quantization },
        contextTokens = model.contextTokens,
    )
}
