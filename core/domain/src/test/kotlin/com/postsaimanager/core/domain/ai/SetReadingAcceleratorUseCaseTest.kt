package com.postsaimanager.core.domain.ai

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeInferenceSettingsRepository
import com.postsaimanager.core.testing.FakeReadingAcceleratorSetting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SetReadingAcceleratorUseCaseTest {

    private val model = "/models/gemma.litertlm"
    private val settings = FakeInferenceSettingsRepository()
    private val reading = FakeReadingAcceleratorSetting()
    private val useCase = SetReadingAcceleratorUseCase(reading, FakeActiveModelProvider(path = model), settings)

    @Test
    @DisplayName("choosing GPU for the reading clears the old GPU block of the active model, so it is tried once")
    fun `gpu clears the block`() = runTest {
        settings.blockGpu(model)

        useCase(Accelerator.GPU)

        assertThat(reading.current()).isEqualTo(Accelerator.GPU)
        assertThat(settings.gpuBlockedModels.first()).isEmpty()
    }

    @Test
    @DisplayName("choosing CPU leaves a block in place")
    fun `cpu keeps the block`() = runTest {
        settings.blockGpu(model)

        useCase(Accelerator.CPU)

        assertThat(reading.current()).isEqualTo(Accelerator.CPU)
        assertThat(settings.gpuBlockedModels.first()).containsExactly(model)
    }
}
