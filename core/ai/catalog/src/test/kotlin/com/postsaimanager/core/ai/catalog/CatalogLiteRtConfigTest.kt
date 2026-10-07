package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.model.ModelSource
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeInferenceSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What the chat model's config says for a LiteRT-LM model: the runtime that routes it, the GPU it prefers whatever llama.cpp's
 * own accelerator probe found, a window that does not follow free memory, and the CPU after a GPU crash.
 */
class CatalogLiteRtConfigTest {

    private fun liteRtModel(descriptorId: String? = "gemma-4-e2b-it-litertlm") = InstalledModel(
        id = descriptorId ?: "imported-gemma",
        descriptorId = descriptorId,
        name = "Gemma 4 E2B",
        filePath = "/models/gemma.litertlm",
        sizeBytes = 1L,
        sha256 = "x",
        contextTokens = 4096,
        source = ModelSource.CATALOG,
        installedAt = 0L,
        runtime = ModelRuntime.LITERT_LM,
    )

    /** A phone whose llama.cpp build found no GPU backend (the default build), with [availableRamBytes] free. */
    private fun device(availableRamBytes: Long): DeviceCapabilityChecker {
        val checker = mockk<DeviceCapabilityChecker>()
        coEvery { checker.current() } returns DeviceCapability(
            totalRamBytes = 12L * 1024 * 1024 * 1024,
            availableRamBytes = availableRamBytes,
            freeStorageBytes = 8L * 1024 * 1024 * 1024,
            supportedAbis = listOf("arm64-v8a"),
            accelerators = setOf(Accelerator.CPU),
        )
        return checker
    }

    private fun provider(
        model: InstalledModel,
        settings: FakeInferenceSettingsRepository = FakeInferenceSettingsRepository(),
        availableRamBytes: Long = 6L * 1024 * 1024 * 1024,
    ): CatalogActiveModelProvider {
        val store = mockk<InstalledModelStore> {
            coEvery { reconcile() } returns Unit
            every { activeModel() } returns model
            every { extractionModel() } returns null
        }
        return CatalogActiveModelProvider(store, device(availableRamBytes), settings, FakeAiEngine(), CpuTopology())
    }

    @Test
    @DisplayName("a LiteRT-LM model's config names its runtime, so the chat engine router picks the LiteRT-LM engine")
    fun `config carries the runtime`() = runTest {
        assertThat(provider(liteRtModel()).activeModelConfig().runtime).isEqualTo(ModelRuntime.LITERT_LM)
    }

    @Test
    @DisplayName("it runs on the GPU even though llama.cpp's probe found none: LiteRT-LM brings its own GPU backend")
    fun `gpu is preferred`() = runTest {
        assertThat(provider(liteRtModel()).activeModelConfig().accelerator).isEqualTo(Accelerator.GPU)
    }

    @Test
    @DisplayName("a LiteRT-LM file with no catalogue entry (an import) is offered the GPU as well")
    fun `imported litert model prefers the gpu`() = runTest {
        assertThat(provider(liteRtModel(descriptorId = null)).activeModelConfig().accelerator).isEqualTo(Accelerator.GPU)
    }

    @Test
    @DisplayName("after a GPU crash with this model, the next load uses the CPU")
    fun `blocked gpu falls back to cpu`() = runTest {
        val settings = FakeInferenceSettingsRepository()
        settings.blockGpu("/models/gemma.litertlm")
        assertThat(provider(liteRtModel(), settings).activeModelConfig().accelerator).isEqualTo(Accelerator.CPU)
    }

    @Test
    @DisplayName("the window is the catalogue's, whatever memory is free: a changed window would restart the whole GPU engine")
    fun `window does not follow free memory`() = runTest {
        val low = provider(liteRtModel(), availableRamBytes = 1L * 1024 * 1024 * 1024).activeModelConfig().contextTokens
        val high = provider(liteRtModel(), availableRamBytes = 6L * 1024 * 1024 * 1024).activeModelConfig().contextTokens
        assertThat(low).isEqualTo(4096)
        assertThat(high).isEqualTo(4096)
    }

    @Test
    @DisplayName("a Gemma 4 LiteRT-LM entry declares tool support, and its config carries it (the Agent Skills run on it)")
    fun `catalogue entry tool support reaches the config`() = runTest {
        val liteRtEntries = BundledCatalog.models.filter { it.runtime == ModelRuntime.LITERT_LM }
        assertThat(liteRtEntries).isNotEmpty()
        assertThat(liteRtEntries.map { it.supportsTools }).doesNotContain(false)
        assertThat(provider(liteRtModel()).activeModelConfig().supportsTools).isTrue()
    }

    @Test
    @DisplayName("an imported LiteRT-LM file declares no tool support: nothing vouches for it")
    fun `imported litert model has no tools`() = runTest {
        assertThat(provider(liteRtModel(descriptorId = null)).activeModelConfig().supportsTools).isFalse()
    }

    @Test
    @DisplayName("a llama.cpp model never carries tool support in its config, whatever its descriptor says")
    fun `gguf model has no tools`() = runTest {
        val gguf = liteRtModel().copy(runtime = ModelRuntime.LLAMA_CPP, filePath = "/models/qwen.gguf", descriptorId = "qwen3.5-0.8b-q4_k_m")
        assertThat(provider(gguf).activeModelConfig().supportsTools).isFalse()
    }

    @Test
    @DisplayName("a llama.cpp model is untouched: its config says llama.cpp")
    fun `gguf model keeps the llama runtime`() = runTest {
        val gguf = liteRtModel().copy(runtime = ModelRuntime.LLAMA_CPP, filePath = "/models/qwen.gguf", descriptorId = null)
        assertThat(provider(gguf).activeModelConfig().runtime).isEqualTo(ModelRuntime.LLAMA_CPP)
    }
}
