package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.InferenceOverrides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * What `feature:settings` needs to render the "On-device AI" section: which controls to
 * show ([schema]), what is actually in effect right now ([effectiveConfig] — defaults with
 * [overrides] applied and clamped, see [com.postsaimanager.core.model.applying]), and the
 * raw [overrides] themselves, so the UI can tell "user-set" apart from "default" per field.
 */
data class InferenceSettingsUiState(
    val schema: List<ConfigSpec>,
    val effectiveConfig: InferenceConfig,
    val overrides: InferenceOverrides,
)

/**
 * Streams the schema-driven settings state for the active model.
 *
 * Recomputes on every override change — cheap, since [ActiveModelProvider] only reads
 * already-measured device capability and a small on-disk index, not a model load.
 */
class ObserveInferenceSettingsUseCase @Inject constructor(
    private val activeModelProvider: ActiveModelProvider,
    private val inferenceSettingsRepository: InferenceSettingsRepository,
) {
    operator fun invoke(): Flow<InferenceSettingsUiState> =
        inferenceSettingsRepository.overrides.map { overrides ->
            InferenceSettingsUiState(
                schema = activeModelProvider.activeModelSchema(),
                effectiveConfig = activeModelProvider.activeModelConfig(),
                overrides = overrides,
            )
        }
}

/**
 * Writes one [ConfigSpec]-described setting, identified by its [ConfigSpec.key].
 *
 * Takes the raw value the generic UI control produced (a `Float` from a `Slider`, a
 * `Boolean` from a `Switch`, the selected `String` from a `Choice`) rather than a typed
 * parameter per setting, precisely so the UI can stay one composable per [ConfigSpec] type
 * without a `when` over every individual key.
 */
class UpdateInferenceSettingUseCase @Inject constructor(
    private val inferenceSettingsRepository: InferenceSettingsRepository,
) {
    suspend operator fun invoke(key: String, value: Any) {
        val current = inferenceSettingsRepository.overrides.first()
        val next = when (key) {
            "threads" -> current.copy(threads = (value as Number).toInt())
            "contextTokens" -> current.copy(contextTokens = value.toString().toIntOrNull())
            "accelerator" -> current.copy(accelerator = Accelerator.fromLabel(value.toString()))
            "temperature" -> current.copy(temperature = (value as Number).toFloat())
            "topK" -> current.copy(topK = (value as Number).toInt())
            "topP" -> current.copy(topP = (value as Number).toFloat())
            "flashAttention" -> current.copy(flashAttention = value as Boolean)
            else -> current
        }
        inferenceSettingsRepository.update(next)
    }
}

/** Clears every override — the "Reset" row. */
class ResetInferenceSettingsUseCase @Inject constructor(
    private val inferenceSettingsRepository: InferenceSettingsRepository,
) {
    suspend operator fun invoke() = inferenceSettingsRepository.reset()
}

/**
 * The "Try GPU again" affordance: reverses [InferenceSettingsRepository.blockGpu] for one
 * model, so the accelerator choice offers GPU again next time [ObserveInferenceSettingsUseCase]
 * recomputes the schema.
 *
 * Deliberately does not itself switch the active model's accelerator back to GPU — that
 * stays a user choice via [UpdateInferenceSettingUseCase] once the option is selectable
 * again, same as any other build/device capability change.
 *
 * @param modelId the installed file's absolute path — the same identifier
 *   [InferenceSettingsRepository.gpuBlockedModels] is keyed by, i.e.
 *   `InstalledModelSummary.filePath`, not `InstalledModelSummary.id`.
 */
class UnblockGpuUseCase @Inject constructor(
    private val inferenceSettingsRepository: InferenceSettingsRepository,
) {
    suspend operator fun invoke(modelId: String) = inferenceSettingsRepository.unblockGpu(modelId)
}
