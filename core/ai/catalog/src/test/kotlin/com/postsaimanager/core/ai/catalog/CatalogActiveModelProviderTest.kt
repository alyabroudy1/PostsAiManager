package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelSource
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeInferenceSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the fix for "the model reloads on every chat message".
 *
 * [SendChatMessageUseCase][com.postsaimanager.core.domain.usecase.SendChatMessageUseCase]
 * calls [CatalogActiveModelProvider.activeModelConfig] on **every** send, and
 * [InferenceConfig.defaults][com.postsaimanager.core.model.InferenceConfig.defaults] sizes
 * [com.postsaimanager.core.model.InferenceConfig.contextTokens] off *live* available RAM.
 * That RAM genuinely fluctuates across a session — the resident model itself uses several
 * hundred MB — so two calls a few seconds apart can land on opposite sides of the 1.5 GB
 * tier threshold even though nothing the user asked for changed. Left alone, that made
 * `ModelLoadCoordinator.load` see a different `contextTokens` on every send and treat it as
 * a genuine [com.postsaimanager.core.model.ReloadScope.CONTEXT] change — recreating the
 * `llama_context` on every message.
 *
 * The fix: while the engine is already [ModelLoadState.Ready] for the *same* model and the
 * user has not set an explicit context override, [CatalogActiveModelProvider] reuses that
 * `Ready` config's `contextTokens` instead of recomputing it from the live RAM snapshot.
 */
class CatalogActiveModelProviderTest {

    private val model = InstalledModel(
        id = "model-a",
        descriptorId = null,
        name = "model-a",
        filePath = "/models/model-a.gguf",
        sizeBytes = 1L,
        sha256 = "x",
        contextTokens = 32_000,
        source = ModelSource.CATALOG,
        installedAt = 0L,
    )

    private val installedStore = mockk<InstalledModelStore> {
        every { reconcile() } returns Unit
        every { activeModel() } returns model
        every { extractionModel() } returns model
    }

    private lateinit var settings: InferenceSettingsRepository
    private lateinit var engine: FakeAiEngine

    @BeforeEach
    fun setUp() {
        settings = FakeInferenceSettingsRepository()
        engine = FakeAiEngine()
    }

    private fun provider(deviceCapability: DeviceCapabilityChecker) =
        CatalogActiveModelProvider(installedStore, deviceCapability, settings, engine)

    /** Fluctuates the RAM tier around the 1.5 GB `affordableContext` threshold on demand. */
    private fun flakyDeviceCapability(availableRamBytesSequence: List<Long>): DeviceCapabilityChecker {
        var index = 0
        val checker = mockk<DeviceCapabilityChecker>()
        coEvery { checker.current() } answers {
            val bytes = availableRamBytesSequence[index.coerceAtMost(availableRamBytesSequence.size - 1)]
            index++
            DeviceCapability(
                totalRamBytes = 8L * 1024 * 1024 * 1024,
                availableRamBytes = bytes,
                freeStorageBytes = 8L * 1024 * 1024 * 1024,
                supportedAbis = listOf("arm64-v8a"),
                accelerators = setOf(Accelerator.CPU),
            )
        }
        return checker
    }

    @Test
    @DisplayName("contextTokens stays fixed across sends while the model is Ready, despite fluctuating RAM")
    fun `repeated activeModelConfig calls with fluctuating RAM return the same config while Ready`() = runTest {
        // Under 1.5 GB free -> 2048; over -> 4096. A real session sees exactly this kind of
        // flip once the model itself is resident and using memory.
        val device = flakyDeviceCapability(
            listOf(2L * 1024 * 1024 * 1024, 1L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024),
        )
        val provider = provider(device)

        // Simulate the model becoming Ready after the first load, as SendChatMessageUseCase
        // would leave it — the first call's config is what actually got loaded.
        val firstConfig = provider.activeModelConfig()
        engine.setReady("/models/model-a.gguf", firstConfig)

        val second = provider.activeModelConfig()
        val third = provider.activeModelConfig()

        assertThat(second.contextTokens).isEqualTo(firstConfig.contextTokens)
        assertThat(third.contextTokens).isEqualTo(firstConfig.contextTokens)
    }

    @Test
    @DisplayName("a model switch re-evaluates the RAM tier instead of reusing the old model's value")
    fun `switching models re-evaluates contextTokens`() = runTest {
        val device = flakyDeviceCapability(listOf(2L * 1024 * 1024 * 1024, 1L * 1024 * 1024 * 1024))
        val provider = provider(device)

        val firstConfig = provider.activeModelConfig()
        // Ready for a *different* model id — activeModelConfig must not treat it as sticky.
        engine.setReady("/models/some-other-model.gguf", firstConfig)

        val second = provider.activeModelConfig()

        assertThat(second.contextTokens).isEqualTo(2048)
    }

    @Test
    @DisplayName("an explicit user override always re-evaluates rather than reusing the sticky tier")
    fun `explicit context override bypasses the sticky value`() = runTest {
        val device = flakyDeviceCapability(
            listOf(2L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024, 1L * 1024 * 1024 * 1024),
        )
        val provider = provider(device)

        val firstConfig = provider.activeModelConfig()
        engine.setReady("/models/model-a.gguf", firstConfig)
        assertThat(firstConfig.contextTokens).isEqualTo(4096)

        settings.update(InferenceOverrides(contextTokens = 3000))
        val overridden = provider.activeModelConfig()
        assertThat(overridden.contextTokens).isEqualTo(3000)

        // RAM has since dropped below the threshold — the ceiling clamps the override down,
        // proving the override path still re-evaluates the live device rather than reusing
        // the earlier sticky value.
        val afterRamDrop = provider.activeModelConfig()
        assertThat(afterRamDrop.contextTokens).isEqualTo(2048)
    }

    @Test
    @DisplayName("when the engine is not Ready for this model, the RAM tier is recomputed fresh")
    fun `not-Ready engine recomputes from live RAM`() = runTest {
        val device = flakyDeviceCapability(listOf(2L * 1024 * 1024 * 1024, 1L * 1024 * 1024 * 1024))
        val provider = provider(device)
        // engine stays whatever FakeAiEngine defaults to (Ready for "fake", a different id).

        val first = provider.activeModelConfig()
        val second = provider.activeModelConfig()

        assertThat(first.contextTokens).isEqualTo(4096)
        assertThat(second.contextTokens).isEqualTo(2048)
    }
}
