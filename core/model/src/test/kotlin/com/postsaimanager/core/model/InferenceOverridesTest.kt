package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [InferenceOverrides.applying] — the clamping rule that keeps a persisted
 * override from crashing the inference process, and [inferenceConfigSchema] — the bounds
 * the Settings UI is generated from.
 */
class InferenceOverridesTest {

    private val gb = 1024L * 1024L * 1024L

    private fun device(
        accelerators: Set<Accelerator> = setOf(Accelerator.CPU),
        available: Long = 8 * gb,
    ) = DeviceCapability(
        totalRamBytes = 8 * gb,
        availableRamBytes = available,
        freeStorageBytes = 50 * gb,
        supportedAbis = listOf("arm64-v8a"),
        accelerators = accelerators,
    )

    private fun defaults(contextTokens: Int = 4096, threads: Int = 4) =
        InferenceConfig(contextTokens = contextTokens, threads = threads)

    @Nested
    @DisplayName("applying")
    inner class Applying {

        @Test
        fun `no overrides leaves the config unchanged`() {
            val base = defaults()
            val result = base.applying(InferenceOverrides.NONE, device())
            assertThat(result).isEqualTo(base)
        }

        @Test
        fun `threads override below availableProcessors is honoured`() {
            val base = defaults(threads = 4)
            val result = base.applying(InferenceOverrides(threads = 1), device())
            assertThat(result.threads).isEqualTo(1)
            assertThat(result.threadsBatch).isEqualTo(1)
        }

        @Test
        fun `threads override above availableProcessors is clamped down to it`() {
            val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val base = defaults(threads = 2)
            val result = base.applying(InferenceOverrides(threads = maxThreads + 1000), device())
            assertThat(result.threads).isEqualTo(maxThreads)
        }

        @Test
        fun `threads override below one is clamped up to one`() {
            val base = defaults(threads = 2)
            val result = base.applying(InferenceOverrides(threads = 0), device())
            assertThat(result.threads).isEqualTo(1)
        }

        @Test
        fun `contextTokens override above the affordable ceiling is clamped down to it`() {
            val base = defaults(contextTokens = 4096)
            val result = base.applying(InferenceOverrides(contextTokens = 32000), device())
            assertThat(result.contextTokens).isEqualTo(4096)
        }

        @Test
        fun `contextTokens override below the ceiling is honoured`() {
            val base = defaults(contextTokens = 4096)
            val result = base.applying(InferenceOverrides(contextTokens = 1024), device())
            assertThat(result.contextTokens).isEqualTo(1024)
        }

        @Test
        fun `accelerator override is resolved against device and model, not trusted blindly`() {
            val base = defaults()
            val result = base.applying(
                InferenceOverrides(accelerator = Accelerator.GPU),
                device(accelerators = setOf(Accelerator.CPU)),
                model = BackendSpec(accelerators = listOf(Accelerator.CPU, Accelerator.GPU)),
            )
            // Device has no GPU, so the preference cannot be honoured — falls back to CPU.
            assertThat(result.accelerator).isEqualTo(Accelerator.CPU)
            assertThat(result.gpuLayers).isEqualTo(0)
        }

        @Test
        fun `accelerator override honoured when device and model both support it`() {
            val base = defaults()
            val result = base.applying(
                InferenceOverrides(accelerator = Accelerator.GPU),
                device(accelerators = setOf(Accelerator.CPU, Accelerator.GPU)),
                model = BackendSpec(accelerators = listOf(Accelerator.CPU, Accelerator.GPU), gpuLayers = -1),
            )
            assertThat(result.accelerator).isEqualTo(Accelerator.GPU)
            assertThat(result.gpuLayers).isEqualTo(-1)
        }

        @Test
        fun `temperature is clamped to 0 to 2`() {
            val base = defaults()
            val high = base.applying(InferenceOverrides(temperature = 9f), device())
            val low = base.applying(InferenceOverrides(temperature = -9f), device())
            assertThat(high.sampling.temperature).isEqualTo(2f)
            assertThat(low.sampling.temperature).isEqualTo(0f)
        }

        @Test
        fun `topK is clamped to 1 to 100`() {
            val base = defaults()
            val high = base.applying(InferenceOverrides(topK = 9000), device())
            val low = base.applying(InferenceOverrides(topK = -5), device())
            assertThat(high.sampling.topK).isEqualTo(100)
            assertThat(low.sampling.topK).isEqualTo(1)
        }

        @Test
        fun `topP is clamped to 0 to 1`() {
            val base = defaults()
            val high = base.applying(InferenceOverrides(topP = 5f), device())
            val low = base.applying(InferenceOverrides(topP = -5f), device())
            assertThat(high.sampling.topP).isEqualTo(1f)
            assertThat(low.sampling.topP).isEqualTo(0f)
        }

        @Test
        fun `flashAttention override is passed through`() {
            val base = defaults().copy(flashAttention = false)
            val result = base.applying(InferenceOverrides(flashAttention = true), device())
            assertThat(result.flashAttention).isTrue()
        }
    }

    @Nested
    @DisplayName("inferenceConfigSchema")
    inner class Schema {

        @Test
        fun `thread slider is bounded by 1 and availableProcessors`() {
            val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val schema = inferenceConfigSchema(device(), null, defaults())
            val slider = schema.filterIsInstance<ConfigSpec.Slider>().first { it.key == "threads" }
            assertThat(slider.min).isEqualTo(1f)
            assertThat(slider.max).isEqualTo(maxThreads.toFloat())
        }

        @Test
        fun `context choices are filtered to the affordable ceiling`() {
            val schema = inferenceConfigSchema(device(), null, defaults(contextTokens = 2048))
            val choice = schema.filterIsInstance<ConfigSpec.Choice>().first { it.key == "contextTokens" }
            assertThat(choice.options).containsExactly("1024", "2048")
        }

        @Test
        fun `context choices include everything up to a high ceiling`() {
            val schema = inferenceConfigSchema(device(), null, defaults(contextTokens = 8192))
            val choice = schema.filterIsInstance<ConfigSpec.Choice>().first { it.key == "contextTokens" }
            assertThat(choice.options).containsExactly("1024", "2048", "4096", "8192")
        }

        @Test
        fun `accelerator choice is present but GPU is disabled on a CPU-only device`() {
            val schema = inferenceConfigSchema(device(accelerators = setOf(Accelerator.CPU)), null, defaults())
            val choice = schema.filterIsInstance<ConfigSpec.Choice>().first { it.key == "accelerator" }
            assertThat(choice.options).containsExactly("CPU", "GPU")
            assertThat(choice.disabledOptions).containsExactly("GPU")
            assertThat(choice.disabledReason).isNotNull()
        }

        @Test
        fun `accelerator choice has no disabled options when the device reports more than one`() {
            val schema = inferenceConfigSchema(
                device(accelerators = setOf(Accelerator.CPU, Accelerator.GPU)),
                BackendSpec(accelerators = listOf(Accelerator.CPU, Accelerator.GPU)),
                defaults(),
            )
            val choice = schema.filterIsInstance<ConfigSpec.Choice>().first { it.key == "accelerator" }
            assertThat(choice.options).containsExactly("CPU", "GPU")
            assertThat(choice.disabledOptions).isEmpty()
            assertThat(choice.disabledReason).isNull()
        }

        @Test
        fun `sampling and flash attention specs carry no reload cost`() {
            val schema = inferenceConfigSchema(device(), null, defaults())
            listOf("temperature", "topK", "topP").forEach { key ->
                assertThat(schema.first { it.key == key }.reloadScope).isEqualTo(ReloadScope.NONE)
            }
        }

        @Test
        fun `context and thread changes require a context reload, accelerator requires a model reload`() {
            val schema = inferenceConfigSchema(
                device(accelerators = setOf(Accelerator.CPU, Accelerator.GPU)),
                BackendSpec(accelerators = listOf(Accelerator.CPU, Accelerator.GPU)),
                defaults(),
            )
            assertThat(schema.first { it.key == "threads" }.reloadScope).isEqualTo(ReloadScope.CONTEXT)
            assertThat(schema.first { it.key == "contextTokens" }.reloadScope).isEqualTo(ReloadScope.CONTEXT)
            assertThat(schema.first { it.key == "flashAttention" }.reloadScope).isEqualTo(ReloadScope.CONTEXT)
            assertThat(schema.first { it.key == "accelerator" }.reloadScope).isEqualTo(ReloadScope.MODEL)
        }
    }

    /**
     * [withGpuBlocked] and the "remove GPU from the device's reported accelerators"
     * technique `CatalogActiveModelProvider` uses together implement the GPU-crash
     * fallback (task B): a model that has crashed on GPU on this device is offered CPU
     * only, both in the effective [InferenceConfig] and in the schema the settings sheet
     * renders from.
     */
    @Nested
    @DisplayName("GPU-crash fallback")
    inner class GpuCrashFallback {

        @Test
        fun `withGpuBlocked disables GPU with a device-specific reason`() {
            val schema = inferenceConfigSchema(
                device(accelerators = setOf(Accelerator.CPU, Accelerator.GPU)),
                BackendSpec(accelerators = listOf(Accelerator.CPU, Accelerator.GPU)),
                defaults(),
            ).map { it.withGpuBlocked() }

            val choice = schema.filterIsInstance<ConfigSpec.Choice>().first { it.key == "accelerator" }
            assertThat(choice.disabledOptions).containsExactly("GPU")
            assertThat(choice.disabledReason).isEqualTo("GPU driver failed to compile shaders for this model")
        }

        @Test
        fun `withGpuBlocked leaves a build-unavailable reason alone`() {
            // GPU already disabled because the device itself has no accelerator to offer —
            // that reason already explains why GPU is off, no need to append a crash note
            // the user never actually triggered on this device.
            val schema = inferenceConfigSchema(device(accelerators = setOf(Accelerator.CPU)), null, defaults())
                .map { it.withGpuBlocked() }

            val choice = schema.filterIsInstance<ConfigSpec.Choice>().first { it.key == "accelerator" }
            assertThat(choice.disabledOptions).containsExactly("GPU")
            assertThat(choice.disabledReason).isEqualTo("Not available in this build")
        }

        @Test
        fun `withGpuBlocked is a no-op for specs other than accelerator`() {
            val schema = inferenceConfigSchema(device(), null, defaults())
            val untouched = schema.filterNot { it.key == "accelerator" }
            assertThat(untouched.map { it.withGpuBlocked() }).isEqualTo(untouched)
        }

        @Test
        fun `a model blocked from GPU resolves to CPU even when the user prefers GPU`() {
            // What CatalogActiveModelProvider does: drop GPU from the device's reported
            // accelerators before resolving, rather than trusting the user's preference.
            val deviceWithoutBlockedGpu = device(accelerators = setOf(Accelerator.CPU, Accelerator.GPU))
                .copy(accelerators = setOf(Accelerator.CPU))
            val result = defaults().applying(
                InferenceOverrides(accelerator = Accelerator.GPU),
                deviceWithoutBlockedGpu,
                model = BackendSpec(accelerators = listOf(Accelerator.CPU, Accelerator.GPU), gpuLayers = -1),
            )
            assertThat(result.accelerator).isEqualTo(Accelerator.CPU)
            assertThat(result.gpuLayers).isEqualTo(0)
        }
    }
}
