package com.postsaimanager.core.ai.local

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * First CPU-vs-GPU number for the Vulkan backend (`-Ppam.gpuBackend=vulkan`).
 *
 * Skips itself — via [assumeTrue] — unless both the spike model is on the device and the
 * native backend actually reports a GPU accelerator (i.e. this was built with
 * `-Ppam.gpuBackend=vulkan` *and* the device's driver was found at runtime). On a CPU-only
 * build, or a device without Vulkan, this test is a no-op rather than a failure.
 */
@RunWith(AndroidJUnit4::class)
class GpuSmokeTest {

    private val modelFile = File("/data/local/tmp/spike-model.gguf")
    private val tag = "pam_gpu_smoke"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun engine() = RemoteAiEngine(context)

    private fun config(accelerator: Accelerator, gpuLayers: Int) = InferenceConfig(
        contextTokens = 1024,
        threads = InferenceConfig.defaultThreadCount(),
        accelerator = accelerator,
        gpuLayers = gpuLayers,
    )

    /** Loads [config], generates [maxTokens] tokens, and returns (loadMillis, tokensPerSecond). */
    private fun runOnce(engine: RemoteAiEngine, config: InferenceConfig, maxTokens: Int): Pair<Long, Double> =
        runBlocking {
            val loadStart = System.nanoTime()
            val result = engine.load(modelFile.absolutePath, config)
            val loadMillis = (System.nanoTime() - loadStart) / 1_000_000
            assertTrue("load failed: $result", result is PamResult.Success)

            try {
                val genStart = System.nanoTime()
                val tokens = engine.generate(
                    AiRequest("Name three colours.", maxTokens = maxTokens, temperature = 0f),
                ).toList()
                val genSeconds = (System.nanoTime() - genStart) / 1_000_000_000.0

                assertTrue("expected streamed tokens, got ${tokens.size}", tokens.isNotEmpty())
                val tokensPerSecond = tokens.size / genSeconds
                Log.i(
                    tag,
                    "config=$config loadMs=$loadMillis tokens=${tokens.size} " +
                        "seconds=$genSeconds tokensPerSecond=$tokensPerSecond " +
                        "text=<<<${tokens.joinToString("")}>>>",
                )
                loadMillis to tokensPerSecond
            } finally {
                engine.unload()
            }
        }

    @Test
    fun cpuVsGpuTokensPerSecond(): Unit = runBlocking {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())

        val engine = engine()
        val accelerators = engine.availableAccelerators()
        Log.i(tag, "availableAccelerators=$accelerators")
        assumeTrue("device/build reports no GPU accelerator: $accelerators", Accelerator.GPU in accelerators)

        val maxTokens = 64

        val (cpuLoadMs, cpuTokensPerSecond) = runOnce(engine, config(Accelerator.CPU, gpuLayers = 0), maxTokens)
        val (gpuLoadMs, gpuTokensPerSecond) = runOnce(engine, config(Accelerator.GPU, gpuLayers = -1), maxTokens)

        Log.i(
            tag,
            "RESULT cpuLoadMs=$cpuLoadMs cpuTokensPerSecond=$cpuTokensPerSecond " +
                "gpuLoadMs=$gpuLoadMs gpuTokensPerSecond=$gpuTokensPerSecond",
        )
    }

    /**
     * The regression this whole fix exists for: on a Vulkan build, `accelerator = CPU`
     * (`gpuLayers = 0`) must never touch the GPU backend at all — not just keep weights off
     * it. Before `llama_model_params.devices` was restricted to CPU-type devices,
     * `n_gpu_layers = 0` alone still left llama.cpp free to register the Vulkan device in
     * `ggml_backend_sched` and offload large-batch ops to it, which crashed on the Adreno 740
     * (`vk::Device::createComputePipeline: ErrorUnknown`) for models whose shaders fail on
     * this driver.
     *
     * Only meaningful on a build that actually compiled the Vulkan backend in — skips itself
     * on a CPU-only build, same as [cpuVsGpuTokensPerSecond].
     */
    @Test
    fun cpuAcceleratorNeverTouchesTheGpuDeviceOnAVulkanBuild(): Unit = runBlocking {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())

        val engine = engine()
        val accelerators = engine.availableAccelerators()
        Log.i(tag, "availableAccelerators=$accelerators")
        assumeTrue("device/build reports no GPU accelerator: $accelerators", Accelerator.GPU in accelerators)

        val result = engine.load(modelFile.absolutePath, config(Accelerator.CPU, gpuLayers = 0))
        assertTrue("load failed: $result", result is PamResult.Success)
        try {
            val devices = engine.lastLoadDevices()
            Log.i(tag, "cpuAccelerator lastLoadDevices=$devices")
            assertEquals("accelerator=CPU must restrict llama_model_params.devices to CPU only", "CPU", devices)
        } finally {
            engine.unload()
        }
    }
}
