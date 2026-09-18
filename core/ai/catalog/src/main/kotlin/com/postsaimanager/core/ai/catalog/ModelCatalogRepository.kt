package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.ai.catalog.download.ModelDownloadManager
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelFit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A catalog entry with everything the UI needs to decide what to show. */
data class CatalogEntry(
    val descriptor: AiModelDescriptor,
    val fit: ModelFit,
    val installed: InstalledModel?,
    /** Chats with the user. */
    val isActive: Boolean,
    /** Reads scanned documents. The same model as [isActive] unless the user split them. */
    val isExtractionModel: Boolean = false,
) {
    val isInstalled: Boolean get() = installed != null
}

data class ModelCatalogState(
    val entries: List<CatalogEntry>,
    val capability: DeviceCapability,
    val usingBundledCatalog: Boolean,
)

/**
 * Single source of truth for the model catalog.
 *
 * Merges three inputs — the catalog (bundled, or verified remote once a signing key
 * exists), what is installed on disk, and a **fresh** device measurement — into one list.
 *
 * Device capability is deliberately re-measured on every read rather than cached: available
 * memory moves constantly, and a stale snapshot is how a model gets offered and then OOMs
 * on load (see `ModelFit`).
 */
@Singleton
class ModelCatalogRepository @Inject constructor(
    private val installedStore: InstalledModelStore,
    private val capabilityChecker: DeviceCapabilityChecker,
    private val downloadManager: ModelDownloadManager,
) {

    /**
     * Descriptors from the verified remote manifest. Empty until a signing key ships
     * (task 7.4.6), at which point the app falls back to [BundledCatalog].
     */
    private val remoteDescriptors = MutableStateFlow<List<AiModelDescriptor>>(emptyList())

    fun setRemoteDescriptors(descriptors: List<AiModelDescriptor>) {
        remoteDescriptors.value = descriptors
    }

    val state: Flow<ModelCatalogState> =
        combine(remoteDescriptors, installedStore.installed) { remote, index ->
            val usingBundled = remote.isEmpty()
            val descriptors = if (usingBundled) BundledCatalog.models else remote
            val capability = capabilityChecker.current()

            ModelCatalogState(
                entries = descriptors.map { descriptor ->
                    val installed = index.models.firstOrNull { it.descriptorId == descriptor.id }
                    CatalogEntry(
                        descriptor = descriptor,
                        fit = ModelFit.evaluate(descriptor, capability),
                        installed = installed,
                        isActive = installed != null && installed.id == index.activeModelId,
                        isExtractionModel = installed != null &&
                            installed.id == (index.extractionModelId ?: index.activeModelId),
                    )
                },
                capability = capability,
                usingBundledCatalog = usingBundled,
            )
        }

    fun downloadStatus(descriptorId: String): Flow<ModelDownloadStatus> =
        downloadManager.observe(descriptorId)

    /** @return false when the descriptor carries no URL or integrity hash. */
    fun startDownload(descriptor: AiModelDescriptor, allowMetered: Boolean = false): Boolean =
        downloadManager.enqueue(descriptor, allowMetered)

    fun cancelDownload(descriptorId: String) = downloadManager.cancel(descriptorId)

    fun onDownloadComplete(descriptor: AiModelDescriptor, filePath: String) {
        installedStore.add(
            InstalledModel(
                id = descriptor.id,
                descriptorId = descriptor.id,
                name = descriptor.name,
                filePath = filePath,
                sizeBytes = descriptor.sizeBytes,
                sha256 = descriptor.sha256.orEmpty(),
                contextTokens = descriptor.contextTokens,
                source = com.postsaimanager.core.model.ModelSource.CATALOG,
                installedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun uninstall(modelId: String) = installedStore.remove(modelId)

    fun setActive(modelId: String) = installedStore.setActive(modelId)

    /** @param modelId null returns reading to whichever model chats. */
    fun setExtractionModel(modelId: String?) = installedStore.setExtractionModel(modelId)

    val activeModel: Flow<InstalledModel?> =
        installedStore.installed.map { index ->
            index.models.firstOrNull { it.id == index.activeModelId }
        }
}
