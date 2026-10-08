package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.model.Accelerator
import javax.inject.Inject

/**
 * Chooses the accelerator documents are read on. Choosing GPU explicitly is a request to try it, so an old GPU block recorded for the
 * active model (a crash from an earlier run) is cleared first: the reading tries the GPU once. If that run crashes, the crash observer
 * blocks the GPU again and the reading falls back to the CPU, as designed. Only the reading is affected: the chat's accelerator is
 * never set here, and a block recorded later still holds for it.
 */
class SetReadingAcceleratorUseCase @Inject constructor(
    private val setting: ReadingAcceleratorSetting,
    private val activeModel: ActiveModelProvider,
    private val inferenceSettings: InferenceSettingsRepository,
) {
    suspend operator fun invoke(accelerator: Accelerator) {
        if (accelerator == Accelerator.GPU) {
            activeModel.activeModelPath()?.let { inferenceSettings.unblockGpu(it) }
        }
        setting.set(accelerator)
    }
}
