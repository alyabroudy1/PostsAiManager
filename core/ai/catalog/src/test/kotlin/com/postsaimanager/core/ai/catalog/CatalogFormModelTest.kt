package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelSource
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeInferenceSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The form agent runs on the 2B when it is installed and on the chat model otherwise (one owner: `ModelProfiles.FORM_AGENT_MODELS`). */
class CatalogFormModelTest {

    private fun installed(descriptorId: String) = InstalledModel(
        id = descriptorId, descriptorId = descriptorId, name = descriptorId, filePath = "/models/$descriptorId.gguf",
        sizeBytes = 1L, sha256 = "x", contextTokens = 4096, source = ModelSource.CATALOG, installedAt = 0L,
    )

    private val small = installed("qwen3.5-0.8b-q4_k_m")
    private val large = installed("qwen3.5-2b-q4_k_m")

    private fun provider(models: List<InstalledModel>): CatalogActiveModelProvider {
        val store = mockk<InstalledModelStore> {
            coEvery { reconcile() } returns Unit
            every { models() } returns models
            every { activeModel() } returns small
            every { extractionModel() } returns small
        }
        val device = mockk<DeviceCapabilityChecker>()
        coEvery { device.current() } returns DeviceCapability(
            totalRamBytes = 8L shl 30, availableRamBytes = 4L shl 30, freeStorageBytes = 8L shl 30,
            supportedAbis = listOf("arm64-v8a"), accelerators = setOf(Accelerator.CPU),
        )
        return CatalogActiveModelProvider(store, device, FakeInferenceSettingsRepository(), FakeAiEngine(), CpuTopology())
    }

    @Test
    fun `the 2B runs the forms when it is installed, while chat stays on the chat model`() = runTest {
        val provider = provider(listOf(small, large))

        assertThat(provider.formModelId()).isEqualTo("qwen3.5-2b-q4_k_m")
        assertThat(provider.formModelPath()).isEqualTo("/models/qwen3.5-2b-q4_k_m.gguf")
        assertThat(provider.activeModelPath()).isEqualTo("/models/qwen3.5-0.8b-q4_k_m.gguf")
    }

    @Test
    fun `without the 2B the forms run on the chat model`() = runTest {
        val provider = provider(listOf(small))

        assertThat(provider.formModelId()).isEqualTo("qwen3.5-0.8b-q4_k_m")
        assertThat(provider.formModelPath()).isEqualTo(provider.activeModelPath())
        assertThat(provider.formModelConfig().contextTokens).isEqualTo(provider.activeModelConfig().contextTokens)
    }
}
