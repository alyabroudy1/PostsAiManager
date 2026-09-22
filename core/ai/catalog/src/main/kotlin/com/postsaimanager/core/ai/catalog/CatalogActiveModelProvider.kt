package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.BackendSpec
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.applying
import com.postsaimanager.core.model.inferenceConfigSchema
import com.postsaimanager.core.model.withGpuBlocked
import kotlinx.coroutines.flow.first
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
    private val inferenceSettingsRepository: InferenceSettingsRepository,
) : ActiveModelProvider {

    override suspend fun activeModelPath(): String? {
        // Reconcile first: an index entry whose file has vanished would otherwise hand the
        // engine a path that fails to load, reported as a model error rather than a
        // missing file.
        installedStore.reconcile()
        return installedStore.activeModel()?.filePath
    }

    override suspend fun extractionModelPath(): String? {
        installedStore.reconcile()
        return installedStore.extractionModel()?.filePath
    }

    /**
     * Sizes context, threads and the rest of [InferenceConfig] to what the device can
     * currently afford — see [InferenceConfig.defaults] for the heuristics, which used to
     * live here and in `LocalAiEngine.defaultThreadCount` — and then layers the user's
     * persisted [com.postsaimanager.core.model.InferenceOverrides] on top, clamped by
     * [applying].
     */
    override suspend fun extractionModelConfig(): InferenceConfig {
        val model = installedStore.extractionModel()
        return effectiveConfig(model?.contextTokens ?: DEFAULT_CONTEXT_TOKENS, backendSpec(model), model?.filePath)
    }

    override suspend fun activeModelConfig(): InferenceConfig {
        val model = installedStore.activeModel()
        return effectiveConfig(model?.contextTokens ?: DEFAULT_CONTEXT_TOKENS, backendSpec(model), model?.filePath)
    }

    override suspend fun activeModelSchema(): List<ConfigSpec> {
        val device = deviceCapability.current()
        val model = installedStore.activeModel()
        val defaults = InferenceConfig.defaults(
            device,
            model?.contextTokens ?: DEFAULT_CONTEXT_TOKENS,
        )
        val schema = inferenceConfigSchema(device, backendSpec(model), defaults)
        return if (model?.filePath != null && isGpuBlocked(model.filePath)) {
            schema.map { it.withGpuBlocked() }
        } else {
            schema
        }
    }

    private suspend fun effectiveConfig(
        catalogedContextTokens: Int,
        model: BackendSpec,
        modelId: String?,
    ): InferenceConfig {
        val device = deviceOmittingBlockedGpu(modelId)
        val defaults = InferenceConfig.defaults(device, catalogedContextTokens)
        val overrides = inferenceSettingsRepository.overrides.first()
        return defaults.applying(overrides, device, model)
    }

    /**
     * [deviceCapability] with GPU removed from what it reports as available, when [modelId]
     * has previously crashed the inference process on GPU — see
     * [InferenceCrashObserver][com.postsaimanager.core.ai.local.InferenceCrashObserver].
     *
     * Filtering the device's own accelerator set, rather than adding a special case to
     * [resolveAccelerator][com.postsaimanager.core.model.resolveAccelerator], reuses exactly
     * the same "device does not actually support this" path that a build with no Vulkan
     * backend already goes through — the effective config and the schema agree by
     * construction instead of two places independently deciding GPU is off.
     */
    private suspend fun deviceOmittingBlockedGpu(modelId: String?): DeviceCapability {
        val device = deviceCapability.current()
        if (modelId == null || !isGpuBlocked(modelId)) return device
        return device.copy(accelerators = device.accelerators - Accelerator.GPU)
    }

    private suspend fun isGpuBlocked(modelId: String): Boolean =
        modelId in inferenceSettingsRepository.gpuBlockedModels.first()

    /**
     * The installed model's declared accelerators, looked up from the catalog it came from.
     *
     * [InstalledModel] itself does not carry [BackendSpec] — it is a record of a file on
     * disk, not a catalog entry — so this re-resolves it from [BundledCatalog] by
     * [InstalledModel.descriptorId]. Defaults to CPU-only for a side-loaded model (no
     * descriptor) or when none is installed yet.
     */
    private fun backendSpec(model: InstalledModel?): BackendSpec =
        model?.descriptorId
            ?.let { id -> BundledCatalog.models.firstOrNull { it.id == id }?.backendSpec }
            ?: BackendSpec()

    private companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4096
    }
}
