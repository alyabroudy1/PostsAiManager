package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.SamplingConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [ModelLoadCoordinator] — single-flight dedup, [com.postsaimanager.core.model
 * .ReloadScope] dispatch, and the stale-generation guard. Driven entirely through
 * [FakeModelLoadOps], which is why this can run on the JVM without a device: the coordinator
 * itself has no native or Android dependency, only [ModelLoadOps].
 */
class ModelLoadCoordinatorTest {

    private fun config(
        contextTokens: Int = 4096,
        threads: Int = 4,
        temperature: Float = 0.7f,
        accelerator: Accelerator = Accelerator.CPU,
    ) = InferenceConfig(
        contextTokens = contextTokens,
        threads = threads,
        sampling = SamplingConfig(temperature = temperature),
        accelerator = accelerator,
    )

    @Nested
    @DisplayName("ReloadScope dispatch")
    inner class Dispatch {

        @Test
        fun `first load for a model is always a full load`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)

            val result = coordinator.load("model-a", config())

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            assertThat(ops.fullLoadCount.get()).isEqualTo(1)
            assertThat(ops.recreateContextCount.get()).isEqualTo(0)
            assertThat(coordinator.state.value).isInstanceOf(ModelLoadState.Ready::class.java)
        }

        @Test
        fun `NONE-scope change makes no native call`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config(temperature = 0.7f))

            val result = coordinator.load("model-a", config(temperature = 0.1f))

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            assertThat(ops.fullLoadCount.get()).isEqualTo(1)
            assertThat(ops.recreateContextCount.get()).isEqualTo(0)
            val ready = coordinator.state.value as ModelLoadState.Ready
            assertThat(ready.config.sampling.temperature).isEqualTo(0.1f)
        }

        @Test
        fun `CONTEXT-scope change recreates the context, not a full load`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config(contextTokens = 4096))

            val result = coordinator.load("model-a", config(contextTokens = 2048))

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            assertThat(ops.fullLoadCount.get()).isEqualTo(1)
            assertThat(ops.recreateContextCount.get()).isEqualTo(1)
            val ready = coordinator.state.value as ModelLoadState.Ready
            assertThat(ready.config.contextTokens).isEqualTo(2048)
        }

        @Test
        fun `MODEL-scope change does a full reload`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config().copy(useMmap = true))

            val result = coordinator.load("model-a", config().copy(useMmap = false))

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            assertThat(ops.fullLoadCount.get()).isEqualTo(2)
            assertThat(ops.recreateContextCount.get()).isEqualTo(0)
        }

        @Test
        fun `a different model is always a full load`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config())

            coordinator.load("model-b", config())

            assertThat(ops.fullLoadCount.get()).isEqualTo(2)
            val ready = coordinator.state.value as ModelLoadState.Ready
            assertThat(ready.modelId).isEqualTo("model-b")
        }

        @Test
        fun `an out-of-band unload is not trusted for the NONE fast path`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config())

            // The :inference process freed the model on its own (memory pressure) without
            // the coordinator's involvement.
            ops.actuallyLoaded = false

            val result = coordinator.load("model-a", config())

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            assertThat(ops.fullLoadCount.get()).isEqualTo(2)
        }

        @Test
        fun `a failed load reports Failed with the model id`() = runTest {
            val ops = FakeModelLoadOps(loadShouldFail = true)
            val coordinator = ModelLoadCoordinator(ops)

            val result = coordinator.load("model-a", config())

            assertThat(result).isInstanceOf(PamResult.Error::class.java)
            val failed = coordinator.state.value as ModelLoadState.Failed
            assertThat(failed.modelId).isEqualTo("model-a")
        }

        @Test
        fun `an accelerator switch unloads before loading, never holding two resident models`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config(accelerator = Accelerator.CPU))
            ops.callOrder.clear()

            val result = coordinator.load("model-a", config(accelerator = Accelerator.GPU))

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            assertThat(ops.callOrder).containsExactly("unload", "load").inOrder()
            assertThat(ops.peakResidentModels).isAtMost(1)
        }

        @Test
        fun `a model switch unloads before loading, never holding two resident models`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config())
            ops.callOrder.clear()

            val result = coordinator.load("model-b", config())

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            assertThat(ops.callOrder).containsExactly("unload", "load").inOrder()
            assertThat(ops.peakResidentModels).isAtMost(1)
        }

        @Test
        fun `a first load never issues an unload — nothing is resident yet`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)

            coordinator.load("model-a", config())

            assertThat(ops.callOrder).containsExactly("load")
            assertThat(ops.peakResidentModels).isAtMost(1)
        }
    }

    @Nested
    @DisplayName("single-flight")
    inner class SingleFlight {

        @Test
        fun `concurrent loads for the same model and config only issue one native call`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)

            // Both start before either finishes — the mutex serialises them, and the second
            // re-checks state after acquiring the lock rather than blindly reloading.
            val first = async { coordinator.load("model-a", config()) }
            val second = async { coordinator.load("model-a", config()) }

            val firstResult = first.await()
            val secondResult = second.await()

            assertThat(firstResult).isInstanceOf(PamResult.Success::class.java)
            assertThat(secondResult).isInstanceOf(PamResult.Success::class.java)
            // The point: only one native call for two overlapping requests for the exact
            // same model and config.
            assertThat(ops.fullLoadCount.get()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("stale generation guard")
    inner class StaleGeneration {

        @Test
        fun `an external failure for an old generation is ignored`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config())
            val staleGeneration = coordinator.currentGeneration()

            // A newer load started before the stale callback (e.g. a delayed binder-death
            // observer) got around to reporting.
            coordinator.load("model-b", config())
            coordinator.reportExternalFailure(staleGeneration, "model-a", "stale crash report")

            assertThat(coordinator.state.value).isInstanceOf(ModelLoadState.Ready::class.java)
            assertThat((coordinator.state.value as ModelLoadState.Ready).modelId).isEqualTo("model-b")
        }

        @Test
        fun `an external failure for the current generation is applied`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config())
            val currentGeneration = coordinator.currentGeneration()

            coordinator.reportExternalFailure(currentGeneration, "model-a", "it crashed")

            val failed = coordinator.state.value as ModelLoadState.Failed
            assertThat(failed.error).isEqualTo("it crashed")
        }
    }

    @Nested
    @DisplayName("ensureLoaded")
    inner class EnsureLoaded {

        @Test
        fun `external failure then ensureLoaded reloads with the last config`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config(contextTokens = 2048))
            val generation = coordinator.currentGeneration()

            coordinator.reportExternalFailure(generation, "model-a", "it crashed")
            assertThat(coordinator.state.value).isInstanceOf(ModelLoadState.Failed::class.java)

            val result = coordinator.ensureLoaded()

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            val ready = coordinator.state.value as ModelLoadState.Ready
            assertThat(ready.modelId).isEqualTo("model-a")
            assertThat(ready.config.contextTokens).isEqualTo(2048)
            // The initial load plus the reload triggered by ensureLoaded().
            assertThat(ops.fullLoadCount.get()).isEqualTo(2)
        }

        @Test
        fun `explicit unload then ensureLoaded does not reload`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config())
            coordinator.unload()

            val result = coordinator.ensureLoaded()

            assertThat(result).isNull()
            assertThat(coordinator.state.value).isEqualTo(ModelLoadState.Idle)
            // No reload attempt — the explicit unload cleared what to resume.
            assertThat(ops.fullLoadCount.get()).isEqualTo(1)
        }

        @Test
        fun `nothing ever requested means ensureLoaded is a no-op`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)

            val result = coordinator.ensureLoaded()

            assertThat(result).isNull()
            assertThat(ops.fullLoadCount.get()).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("unload")
    inner class Unload {

        @Test
        fun `unload resets to Idle and bumps the generation`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config())
            val loadedGeneration = coordinator.currentGeneration()

            coordinator.unload()

            assertThat(coordinator.state.value).isEqualTo(ModelLoadState.Idle)
            assertThat(coordinator.currentGeneration()).isGreaterThan(loadedGeneration)
        }

        @Test
        fun `memory-pressure unload does nothing when idle`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)

            coordinator.unloadOnMemoryPressure()

            assertThat(ops.unloadCount.get()).isEqualTo(0)
            assertThat(coordinator.state.value).isEqualTo(ModelLoadState.Idle)
        }

        @Test
        fun `memory-pressure unload frees a ready model`() = runTest {
            val ops = FakeModelLoadOps()
            val coordinator = ModelLoadCoordinator(ops)
            coordinator.load("model-a", config())

            coordinator.unloadOnMemoryPressure()

            assertThat(ops.unloadCount.get()).isEqualTo(1)
            assertThat(coordinator.state.value).isEqualTo(ModelLoadState.Idle)
        }
    }
}

/** A [ModelLoadOps] with no native or Android dependency, for JVM tests. */
private class FakeModelLoadOps(
    private val loadShouldFail: Boolean = false,
) : ModelLoadOps {

    val fullLoadCount = AtomicInteger(0)
    val recreateContextCount = AtomicInteger(0)
    val unloadCount = AtomicInteger(0)

    /** Overridable to simulate an out-of-band unload the coordinator does not know about. */
    var actuallyLoaded: Boolean = true

    /** Order [loadModel]/[unloadModel] were invoked in — "load" / "unload" per call. */
    val callOrder = mutableListOf<String>()

    /**
     * How many models this fake believes are simultaneously resident — the count a real
     * process's native memory would hold. Incremented in [loadModel], decremented in
     * [unloadModel]; a coordinator bug that allocates the new model before freeing the old
     * one would push this above 1.
     */
    private var residentModels = 0
    var peakResidentModels = 0
        private set

    override suspend fun loadModel(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> {
        callOrder += "load"
        fullLoadCount.incrementAndGet()
        if (loadShouldFail) {
            return PamResult.Error(PamError.ModelNotLoaded("fake load failure"))
        }
        residentModels++
        peakResidentModels = maxOf(peakResidentModels, residentModels)
        actuallyLoaded = true
        return PamResult.Success(capabilities(modelId, config))
    }

    override suspend fun recreateContext(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> {
        recreateContextCount.incrementAndGet()
        return PamResult.Success(capabilities(modelId, config))
    }

    override suspend fun unloadModel() {
        callOrder += "unload"
        unloadCount.incrementAndGet()
        if (residentModels > 0) residentModels--
        actuallyLoaded = false
    }

    override suspend fun isActuallyLoaded(): Boolean = actuallyLoaded

    private fun capabilities(modelId: String, config: InferenceConfig) = AiCapabilities(
        supportsGrammar = true,
        contextTokens = config.contextTokens,
        modelName = modelId,
        hasNativeChatTemplate = true,
    )
}
