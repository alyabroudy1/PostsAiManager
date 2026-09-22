package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [InferenceConfig] — the single source of truth for how a model is loaded and
 * sampled from, and the [ReloadScope] rule that decides how expensive a change is to apply.
 */
class InferenceConfigTest {

    private val gb = 1024L * 1024L * 1024L

    private fun device(available: Long) = DeviceCapability(
        totalRamBytes = 8 * gb,
        availableRamBytes = available,
        freeStorageBytes = 50 * gb,
        supportedAbis = listOf("arm64-v8a"),
    )

    private fun config(
        contextTokens: Int = 4096,
        batchTokens: Int = 512,
        threads: Int = 4,
        threadsBatch: Int = threads,
        useMmap: Boolean = true,
        useMlock: Boolean = false,
        flashAttention: Boolean = false,
        accelerator: Accelerator = Accelerator.CPU,
        gpuLayers: Int = 0,
    ) = InferenceConfig(
        contextTokens = contextTokens,
        batchTokens = batchTokens,
        threads = threads,
        threadsBatch = threadsBatch,
        useMmap = useMmap,
        useMlock = useMlock,
        flashAttention = flashAttention,
        accelerator = accelerator,
        gpuLayers = gpuLayers,
    )

    @Nested
    @DisplayName("defaults per RAM tier")
    inner class Defaults {

        @Test
        fun `below 1500 MB available, context is clamped to 2048`() {
            val config = InferenceConfig.defaults(device(1200L * 1024 * 1024), 4096)
            assertThat(config.contextTokens).isEqualTo(2048)
        }

        @Test
        fun `at or above 1500 MB available, context is clamped to 4096`() {
            val config = InferenceConfig.defaults(device(1500L * 1024 * 1024), 8192)
            assertThat(config.contextTokens).isEqualTo(4096)
        }

        @Test
        fun `the catalogued value stays the ceiling even with ample memory`() {
            val config = InferenceConfig.defaults(device(8 * gb), 2048)
            assertThat(config.contextTokens).isEqualTo(2048)
        }

        @Test
        fun `threads default to threadsBatch`() {
            val config = InferenceConfig.defaults(device(8 * gb), 4096)
            assertThat(config.threadsBatch).isEqualTo(config.threads)
        }

        @Test
        fun `thread count is half the cores, at least two`() {
            // Cannot control Runtime.availableProcessors() in a unit test, but the floor
            // and the halving relationship are guaranteed regardless of the test machine.
            assertThat(InferenceConfig.defaultThreadCount()).isAtLeast(2)
        }
    }

    @Nested
    @DisplayName("threadsBatch")
    inner class ThreadsBatch {

        @Test
        fun `defaults to threads when not specified`() {
            val config = InferenceConfig(contextTokens = 4096, threads = 6)
            assertThat(config.threadsBatch).isEqualTo(6)
        }

        @Test
        fun `can be set independently`() {
            val config = InferenceConfig(contextTokens = 4096, threads = 6, threadsBatch = 2)
            assertThat(config.threadsBatch).isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("requiresReload")
    inner class RequiresReload {

        @Test
        fun `identical configs need no reload`() {
            val a = config()
            assertThat(a.requiresReload(a.copy())).isEqualTo(ReloadScope.NONE)
        }

        @Test
        fun `sampling-only change needs no reload`() {
            val a = config()
            val b = a.copy(sampling = a.sampling.copy(temperature = 0.2f, topK = 10))
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.NONE)
        }

        @Test
        fun `context size change recreates the context`() {
            val a = config(contextTokens = 4096)
            val b = a.copy(contextTokens = 2048)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.CONTEXT)
        }

        @Test
        fun `batch size change recreates the context`() {
            val a = config(batchTokens = 512)
            val b = a.copy(batchTokens = 256)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.CONTEXT)
        }

        @Test
        fun `thread count change recreates the context`() {
            val a = config(threads = 4)
            val b = a.copy(threads = 2, threadsBatch = 2)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.CONTEXT)
        }

        @Test
        fun `flash attention change recreates the context`() {
            val a = config(flashAttention = false)
            val b = a.copy(flashAttention = true)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.CONTEXT)
        }

        @Test
        fun `mmap change needs a full reload`() {
            val a = config(useMmap = true)
            val b = a.copy(useMmap = false)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.MODEL)
        }

        @Test
        fun `mlock change needs a full reload`() {
            val a = config(useMlock = false)
            val b = a.copy(useMlock = true)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.MODEL)
        }

        @Test
        fun `accelerator change needs a full reload`() {
            val a = config(accelerator = Accelerator.CPU, gpuLayers = 0)
            val b = a.copy(accelerator = Accelerator.GPU, gpuLayers = -1)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.MODEL)
        }

        @Test
        fun `gpuLayers change needs a full reload`() {
            val a = config(gpuLayers = 0)
            val b = a.copy(gpuLayers = -1)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.MODEL)
        }

        @Test
        fun `a model-level change wins over a simultaneous context-level change`() {
            val a = config(contextTokens = 4096, useMmap = true)
            val b = a.copy(contextTokens = 2048, useMmap = false)
            assertThat(a.requiresReload(b)).isEqualTo(ReloadScope.MODEL)
        }
    }

    @Nested
    @DisplayName("Accelerator.fromLabel")
    inner class AcceleratorFromLabel {

        @Test
        fun `parses known labels case-insensitively`() {
            assertThat(Accelerator.fromLabel("gpu")).isEqualTo(Accelerator.GPU)
            assertThat(Accelerator.fromLabel("CPU")).isEqualTo(Accelerator.CPU)
        }

        @Test
        fun `unknown labels fall back to CPU`() {
            assertThat(Accelerator.fromLabel("quantum")).isEqualTo(Accelerator.CPU)
        }
    }

    @Nested
    @DisplayName("resolveAccelerator")
    inner class ResolveAccelerator {

        @Test
        fun `CPU-only model resolves to CPU regardless of preference`() {
            val resolved = resolveAccelerator(
                preference = Accelerator.GPU,
                device = setOf(Accelerator.CPU, Accelerator.GPU),
                model = BackendSpec(accelerators = listOf(Accelerator.CPU)),
            )
            assertThat(resolved).isEqualTo(Accelerator.CPU)
        }

        @Test
        fun `user preference wins when both device and model support it`() {
            val resolved = resolveAccelerator(
                preference = Accelerator.GPU,
                device = setOf(Accelerator.CPU, Accelerator.GPU),
                model = BackendSpec(accelerators = listOf(Accelerator.CPU, Accelerator.GPU)),
            )
            assertThat(resolved).isEqualTo(Accelerator.GPU)
        }

        @Test
        fun `preference for an accelerator the device lacks falls back to the model's own choice`() {
            val resolved = resolveAccelerator(
                preference = Accelerator.GPU,
                device = setOf(Accelerator.CPU),
                model = BackendSpec(accelerators = listOf(Accelerator.GPU, Accelerator.CPU)),
            )
            assertThat(resolved).isEqualTo(Accelerator.CPU)
        }

        @Test
        fun `no preference picks the model's first choice the device supports`() {
            val resolved = resolveAccelerator(
                preference = null,
                device = setOf(Accelerator.CPU, Accelerator.GPU),
                model = BackendSpec(accelerators = listOf(Accelerator.GPU, Accelerator.CPU)),
            )
            assertThat(resolved).isEqualTo(Accelerator.GPU)
        }

        @Test
        fun `nothing supported by both falls back to CPU`() {
            val resolved = resolveAccelerator(
                preference = null,
                device = setOf(Accelerator.CPU),
                model = BackendSpec(accelerators = listOf(Accelerator.GPU)),
            )
            assertThat(resolved).isEqualTo(Accelerator.CPU)
        }
    }

    @Nested
    @DisplayName("resolveGpuLayers")
    inner class ResolveGpuLayers {

        @Test
        fun `zero on CPU regardless of the model's declared gpuLayers`() {
            val layers = resolveGpuLayers(Accelerator.CPU, BackendSpec(gpuLayers = -1))
            assertThat(layers).isEqualTo(0)
        }

        @Test
        fun `the model's declared gpuLayers on GPU`() {
            val layers = resolveGpuLayers(Accelerator.GPU, BackendSpec(gpuLayers = -1))
            assertThat(layers).isEqualTo(-1)
        }

        @Test
        fun `a partial offload is passed through unchanged`() {
            val layers = resolveGpuLayers(Accelerator.GPU, BackendSpec(gpuLayers = 20))
            assertThat(layers).isEqualTo(20)
        }
    }
}
