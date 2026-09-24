package com.postsaimanager.core.model

/**
 * Describes one user-editable [InferenceConfig] setting, generically enough that
 * `feature:settings` can render a whole screen from a `List<ConfigSpec>` without knowing
 * what the individual settings are.
 *
 * [label] is a plain string, not an Android string resource — this module cannot depend on
 * `android.*` (architecture rule) — and the UI layer is free to map [key] to a localized
 * string resource of its own if it later wants to; today the rest of `feature:settings`
 * hardcodes its own copy the same way, so this follows that existing convention rather than
 * inventing a new one for a single section.
 */
sealed interface ConfigSpec {
    /** The [InferenceOverrides] field this setting writes to. */
    val key: String
    val label: String

    /** How expensive it is to apply a change to this setting — see [ReloadScope]. */
    val reloadScope: ReloadScope

    data class Slider(
        override val key: String,
        override val label: String,
        val min: Float,
        val max: Float,
        val step: Float,
        val default: Float,
        override val reloadScope: ReloadScope,
    ) : ConfigSpec

    data class Switch(
        override val key: String,
        override val label: String,
        val default: Boolean,
        override val reloadScope: ReloadScope,
    ) : ConfigSpec

    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<String>,
        val default: String,
        override val reloadScope: ReloadScope,
        /**
         * Options present in [options] that cannot be selected right now — e.g. "GPU" on a
         * build with no Vulkan backend. The UI renders these visibly rather than hiding
         * them, so the user learns the capability exists and why it is off, instead of
         * wondering why it is missing entirely.
         */
        val disabledOptions: Set<String> = emptySet(),
        /** Shown alongside a disabled option — e.g. "Not available in this build". */
        val disabledReason: String? = null,
    ) : ConfigSpec
}

/** [ConfigSpec.Slider]/[ConfigSpec.Switch]/[ConfigSpec.Choice] read the same way everywhere
 * a control renders one — the Settings screen and the chat model sheet alike — so this
 * mapping from "raw override" to "value the control should show" lives once, here, rather
 * than being copied per UI module. */
fun ConfigSpec.Slider.effectiveValue(overrides: InferenceOverrides): Float = when (key) {
    "threads" -> overrides.threads?.toFloat()
    "temperature" -> overrides.temperature
    "topK" -> overrides.topK?.toFloat()
    "topP" -> overrides.topP
    else -> null
} ?: default

fun ConfigSpec.Switch.effectiveValue(overrides: InferenceOverrides): Boolean = when (key) {
    "flashAttention" -> overrides.flashAttention
    "thinking" -> overrides.thinkingEnabled
    else -> null
} ?: default

fun ConfigSpec.Choice.effectiveValue(overrides: InferenceOverrides): String = when (key) {
    "contextTokens" -> overrides.contextTokens?.toString()
    "accelerator" -> overrides.accelerator?.name
    else -> null
} ?: default

/**
 * The settings a user may edit for the active model on this device.
 *
 * Bounds are computed fresh from [device] and [defaults] rather than hardcoded, so a schema
 * never offers a control whose value could crash the inference process:
 * - the thread slider tops out at the device's actual core count;
 * - the context choice list is filtered to what [InferenceConfig.defaults] already decided
 *   the device can afford (see its doc for why that ceiling exists) — never higher;
 * - the accelerator choice is always present — even on a CPU-only device — but an option
 *   this device or model cannot honour is carried as [ConfigSpec.Choice.disabledOptions]
 *   rather than omitted, so a chooser (the chat header sheet, in particular) can show *why*
 *   GPU is off instead of pretending it does not exist.
 *
 * @param model the active model's [BackendSpec], or null when no model is installed yet —
 *   the accelerator choice is then also omitted, since there is nothing to offload.
 * @param defaults [InferenceConfig.defaults] for the active model on this device; every
 *   slider/choice default mirrors it so "no override yet" renders as the value that is
 *   actually in effect.
 */
fun inferenceConfigSchema(
    device: DeviceCapability,
    model: BackendSpec?,
    defaults: InferenceConfig,
): List<ConfigSpec> {
    val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val specs = mutableListOf<ConfigSpec>()

    specs += ConfigSpec.Slider(
        key = "threads",
        label = "CPU threads",
        min = 1f,
        max = maxThreads.toFloat(),
        step = 1f,
        default = defaults.threads.toFloat(),
        reloadScope = ReloadScope.CONTEXT,
    )

    val contextChoices = CONTEXT_CHOICES
        .filter { it <= defaults.contextTokens }
        .ifEmpty { listOf(defaults.contextTokens) }
    specs += ConfigSpec.Choice(
        key = "contextTokens",
        label = "Context window",
        options = contextChoices.map(Int::toString),
        default = defaults.contextTokens.toString(),
        reloadScope = ReloadScope.CONTEXT,
    )

    run {
        val modelAccelerators = (model?.accelerators ?: listOf(Accelerator.CPU)).ifEmpty { listOf(Accelerator.CPU) }
        val disabled = Accelerator.entries
            .filterNot { it in device.accelerators && it in modelAccelerators }
            .map { it.name }
            .toSet()
        specs += ConfigSpec.Choice(
            key = "accelerator",
            label = "Accelerator",
            options = Accelerator.entries.map { it.name },
            default = defaults.accelerator.name,
            reloadScope = ReloadScope.MODEL,
            disabledOptions = disabled,
            disabledReason = if (disabled.isNotEmpty()) "Not available in this build" else null,
        )
    }

    specs += ConfigSpec.Slider(
        key = "temperature",
        label = "Temperature",
        min = 0f,
        max = 2f,
        step = 0.05f,
        default = defaults.sampling.temperature,
        reloadScope = ReloadScope.NONE,
    )

    specs += ConfigSpec.Slider(
        key = "topK",
        label = "Top-K",
        min = 1f,
        max = 100f,
        step = 1f,
        default = defaults.sampling.topK.toFloat(),
        reloadScope = ReloadScope.NONE,
    )

    specs += ConfigSpec.Slider(
        key = "topP",
        label = "Top-P",
        min = 0f,
        max = 1f,
        step = 0.05f,
        default = defaults.sampling.topP,
        reloadScope = ReloadScope.NONE,
    )

    specs += ConfigSpec.Switch(
        key = "flashAttention",
        label = "Flash attention",
        default = defaults.flashAttention,
        reloadScope = ReloadScope.CONTEXT,
    )

    // Sampling-only — see InferenceOverrides.thinkingEnabled. Not model/device-conditional
    // (unlike accelerator/context above): every model that ships no `<think>` tags at all
    // simply ignores the `/no_think` suffix this sends, so the control is always safe to
    // show rather than only for models known to reason.
    specs += ConfigSpec.Switch(
        key = "thinking",
        label = "Thinking",
        default = true,
        reloadScope = ReloadScope.NONE,
    )

    return specs
}

private val CONTEXT_CHOICES = listOf(1024, 2048, 4096, 8192)

/**
 * Greys GPU out of the accelerator [ConfigSpec.Choice], reason
 * "GPU driver failed to compile shaders for this model" — applied by
 * `CatalogActiveModelProvider.activeModelSchema` when the active model is in
 * [com.postsaimanager.core.domain.repository.InferenceSettingsRepository.gpuBlockedModels].
 *
 * The wording names the actual failure mode (a Vulkan pipeline-link error inside the driver,
 * not a bug in this app's own code) rather than the generic "Crashed on this device" it used
 * to say — see documentation/02-architecture.md §5.3: on the Adreno 740, every
 * `GGML_VK_DISABLE_*`/`GGML_VK_*` toggle `ggml-vulkan.cpp` exposes was tried and none avoided
 * it, so this is reported as a driver limitation for this model/device pair rather than
 * implying a retry might succeed unaided.
 *
 * A no-op for any spec other than the accelerator choice, so callers can map every entry in
 * a schema through this uniformly rather than picking the one spec out by key.
 */
fun ConfigSpec.withGpuBlocked(): ConfigSpec {
    if (this !is ConfigSpec.Choice || key != "accelerator") return this
    val reason = if (disabledReason.isNullOrBlank()) {
        GPU_SHADER_COMPILE_FAILED_REASON
    } else if (Accelerator.GPU.name in disabledOptions) {
        // Already disabled for another reason (e.g. no Vulkan backend in this build) —
        // that reason already explains why GPU is off, nothing to add.
        disabledReason
    } else {
        "$disabledReason; $GPU_SHADER_COMPILE_FAILED_REASON"
    }
    return copy(
        disabledOptions = disabledOptions + Accelerator.GPU.name,
        disabledReason = reason,
    )
}

private const val GPU_SHADER_COMPILE_FAILED_REASON = "GPU driver failed to compile shaders for this model"

/**
 * True when [ConfigSpec.withGpuBlocked] is the (or part of the) reason GPU is disabled on
 * this spec — i.e. there is a persisted crash block a "Try GPU again" affordance could
 * usefully clear, as opposed to GPU being off because this build/device never had it to
 * begin with (`disabledReason == "Not available in this build"`, where retrying cannot help).
 *
 * `false` for any spec other than the accelerator choice, or one where GPU is not disabled
 * at all.
 */
val ConfigSpec.isGpuBlockedByDriver: Boolean
    get() = this is ConfigSpec.Choice &&
        key == "accelerator" &&
        Accelerator.GPU.name in disabledOptions &&
        disabledReason?.contains(GPU_SHADER_COMPILE_FAILED_REASON) == true
