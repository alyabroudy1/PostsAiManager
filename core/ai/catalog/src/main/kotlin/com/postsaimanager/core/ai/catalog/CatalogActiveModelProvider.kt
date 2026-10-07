package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.BackendSpec
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ModelRuntime
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
    private val aiEngine: AiEngine,
    private val cpuTopology: CpuTopology,
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

    override suspend fun extractionModelId(): String? = installedStore.extractionModel()?.descriptorId

    override suspend fun activeModelId(): String? = installedStore.activeModel()?.descriptorId

    /**
     * The model the form agent runs on: the first installed of [ModelProfiles.FORM_AGENT_MODELS] (the 2B, which handles tool calls far
     * better than the 0.8B), else the chat model. Chosen per run; the chat model is loaded again by the next chat message.
     */
    private fun formModel(): InstalledModel? =
        ModelProfiles.FORM_AGENT_MODELS.firstNotNullOfOrNull { id -> installedStore.models().firstOrNull { it.descriptorId == id } }
            ?: installedStore.activeModel()?.takeIf { it.runtime.canReadDocuments }
            ?: installedStore.extractionModel()

    override suspend fun formModelPath(): String? {
        installedStore.reconcile()
        return formModel()?.filePath
    }

    override suspend fun formModelId(): String? = formModel()?.descriptorId

    override suspend fun formModelConfig(): InferenceConfig {
        return configFor(formModel())
    }

    /**
     * Sizes context, threads and the rest of [InferenceConfig] to what the device can
     * currently afford — see [InferenceConfig.defaults] for the heuristics, which used to
     * live here and in `LocalAiEngine.defaultThreadCount` — and then layers the user's
     * persisted [com.postsaimanager.core.model.InferenceOverrides] on top, clamped by
     * [applying].
     */
    override suspend fun extractionModelConfig(): InferenceConfig {
        return configFor(installedStore.extractionModel())
    }

    override suspend fun activeModelConfig(): InferenceConfig = configFor(installedStore.activeModel())

    /**
     * The config [model] loads with, for its runtime. llama.cpp models are sized to what the device can afford
     * ([effectiveConfig]). A LiteRT-LM model keeps the window its catalogue entry names (the engine fixes it at start, so a
     * window that followed free memory would restart a GPU engine for nothing), and runs on the accelerator its entry prefers
     * ([liteRtDevice]).
     */
    private suspend fun configFor(model: InstalledModel?): InferenceConfig {
        if (model?.runtime != ModelRuntime.LITERT_LM) {
            return effectiveConfig(model?.contextTokens ?: DEFAULT_CONTEXT_TOKENS, backendSpec(model), model?.filePath)
        }
        val device = liteRtDevice(model)
        val overrides = inferenceSettingsRepository.overrides.first()
        val defaults = InferenceConfig.defaults(device, model.contextTokens, cpuTopology.coreMaxFreqsKHz())
            .copy(contextTokens = model.contextTokens)
        return defaults.applying(overrides, device, backendSpec(model))
            .copy(runtime = ModelRuntime.LITERT_LM, supportsTools = declaresTools(model))
    }

    /** Whether the catalogue entry [model] came from declares tool calling; an import with no entry declares none. */
    private fun declaresTools(model: InstalledModel): Boolean =
        model.descriptorId?.let { id -> BundledCatalog.models.firstOrNull { it.id == id }?.supportsTools } == true

    /**
     * The device as a LiteRT-LM model sees it: it brings its own GPU backend (not llama.cpp's Vulkan one, which a default build
     * does not have), so GPU is on offer unless this model has crashed on it before; the engine falls back to the CPU itself when
     * the GPU engine cannot start.
     */
    private suspend fun liteRtDevice(model: InstalledModel): DeviceCapability {
        val accelerators = if (isGpuBlocked(model.filePath)) setOf(Accelerator.CPU) else setOf(Accelerator.CPU, Accelerator.GPU)
        return deviceCapability.current().copy(accelerators = accelerators)
    }

    override suspend fun activeModelSchema(): List<ConfigSpec> {
        val model = installedStore.activeModel()
        val device = if (model?.runtime == ModelRuntime.LITERT_LM) liteRtDevice(model) else deviceCapability.current()
        val baseDefaults = InferenceConfig.defaults(
            device,
            model?.contextTokens ?: DEFAULT_CONTEXT_TOKENS,
            cpuTopology.coreMaxFreqsKHz(),
        )
        // A LiteRT-LM model runs on the backend its config resolves to (GPU unless blocked), which llama.cpp's own defaults know
        // nothing about: the accelerator control must show what the engine really uses, not llama.cpp's CPU default.
        val defaults = if (model?.runtime == ModelRuntime.LITERT_LM) {
            baseDefaults.copy(accelerator = configFor(model).accelerator)
        } else {
            baseDefaults
        }
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
        val overrides = inferenceSettingsRepository.overrides.first()
        val defaults = stickyDefaults(device, catalogedContextTokens, modelId, overrides)
        return defaults.applying(overrides, device, model)
    }

    /**
     * [InferenceConfig.defaults], except [InferenceConfig.contextTokens] is pinned to
     * whatever the resident model is already `Ready` with, instead of being recomputed from
     * *live* available RAM.
     *
     * [DeviceCapabilityChecker.current] deliberately reads memory fresh every call (its own
     * doc comment says so, and that is correct for e.g. deciding whether to *start* a load).
     * But [affordableContext][InferenceConfig.defaults] keys off that live number against a
     * fixed threshold, and loading a model itself consumes several hundred MB — so free RAM
     * legitimately sits on both sides of the threshold across a single chat session, and
     * every [activeModelConfig] call that disagreed with the last one made
     * `ModelLoadCoordinator.load` treat it as a [com.postsaimanager.core.model.ReloadScope
     * .CONTEXT] change, recreating the `llama_context` on **every** send even though nothing
     * the user asked for changed. See `SendChatMessageUseCase`, which calls this on every
     * message by design.
     *
     * The RAM tier is therefore decided once, when the model is *(re)loaded* — i.e. whenever
     * the engine is not already `Ready` with this exact [modelId] — and then stays sticky for
     * as long as that model remains resident. A model switch, an app restart, or the engine
     * dropping out of `Ready` (crash, memory-pressure unload) all naturally re-evaluate it,
     * since only [ModelLoadState.Ready] for the *same* model reuses the old value. An explicit
     * user context override always wins over the sticky value too — [InferenceConfig.applying]
     * still clamps it down to this ceiling, so the safety limit never disappears, but a user
     * who deliberately picked a smaller (or larger, up to this ceiling) window should see that
     * choice honoured rather than silently overwritten by the tier that happened to be sticky.
     */
    private suspend fun stickyDefaults(
        device: DeviceCapability,
        catalogedContextTokens: Int,
        modelId: String?,
        overrides: InferenceOverrides,
    ): InferenceConfig {
        val fresh = InferenceConfig.defaults(device, catalogedContextTokens, cpuTopology.coreMaxFreqsKHz())
        if (overrides.contextTokens != null) return fresh
        val ready = aiEngine.state.value
        if (ready is ModelLoadState.Ready && ready.modelId == modelId) {
            return fresh.copy(contextTokens = ready.config.contextTokens)
        }
        return fresh
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
            // A LiteRT-LM file with no catalogue entry (an import) still has the engine's own GPU backend to try.
            ?: if (model?.runtime == ModelRuntime.LITERT_LM) BackendSpec(listOf(Accelerator.GPU, Accelerator.CPU)) else BackendSpec()

    private companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4096
    }
}
