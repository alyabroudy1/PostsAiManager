package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Everything llama.cpp needs to load a model and sample from it.
 *
 * The single source of truth for inference configuration: before this, context size and
 * thread count were loose `Int` parameters threaded separately through `AiEngine.load`,
 * the AIDL boundary, `LlamaNative` and the JNI layer, and mmap/mlock/flash-attention/GPU
 * offload were hardcoded in `llama_jni.cpp` and could not be changed without touching C++.
 * Every one of those now reads from here.
 *
 * Immutable: changing a setting produces a *new* [InferenceConfig] rather than mutating one
 * in place, and [requiresReload] tells the caller how expensive moving to it is.
 */
@Serializable
data class InferenceConfig(
    val contextTokens: Int,
    val batchTokens: Int = DEFAULT_BATCH_TOKENS,
    val threads: Int,
    /** Threads used while processing the prompt, as opposed to generating. Defaults to [threads]. */
    val threadsBatch: Int = threads,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val flashAttention: Boolean = false,
    val accelerator: Accelerator = Accelerator.CPU,
    /** Layers offloaded to the accelerator. -1 = all, 0 = none (the only valid value on CPU). */
    val gpuLayers: Int = 0,
    val sampling: SamplingConfig = SamplingConfig(),
) {

    /**
     * How expensive it is to move from this config to [other].
     *
     * A sampling-only change (temperature, top-k, top-p, seed) costs nothing — the sampler
     * chain is already rebuilt for every generation, so [NONE] just means "nothing on the
     * native side needs to change before the next call". A change to the context — window
     * size, batch size, thread counts, flash attention — needs a fresh `llama_context`, but
     * the loaded weights are reusable, hence [CONTEXT]. A change to how the model itself is
     * read from disk or placed on an accelerator — mmap, mlock, the accelerator, or how many
     * layers it offloads — needs a full reload, hence [MODEL].
     */
    fun requiresReload(other: InferenceConfig): ReloadScope = when {
        useMmap != other.useMmap ||
            useMlock != other.useMlock ||
            accelerator != other.accelerator ||
            gpuLayers != other.gpuLayers -> ReloadScope.MODEL

        contextTokens != other.contextTokens ||
            batchTokens != other.batchTokens ||
            threads != other.threads ||
            threadsBatch != other.threadsBatch ||
            flashAttention != other.flashAttention -> ReloadScope.CONTEXT

        else -> ReloadScope.NONE
    }

    companion object {
        const val DEFAULT_BATCH_TOKENS = 512

        /**
         * Centralises the heuristics that used to live separately in
         * `CatalogActiveModelProvider.affordableContext` and `LocalAiEngine.defaultThreadCount`.
         *
         * @param catalogedContextTokens the context window the model itself declares — the
         *   ceiling. What this computes is how much of it the device can actually afford.
         */
        /**
         * @param coreMaxFreqsKHz each core's max frequency as read by the caller (this type
         *   stays free of IO). Empty, the default, means unknown: threadsBatch falls back to
         *   the generation thread count.
         */
        fun defaults(
            deviceCapability: DeviceCapability,
            catalogedContextTokens: Int,
            coreMaxFreqsKHz: List<Long> = emptyList(),
        ): InferenceConfig {
            val threads = defaultThreadCount()
            return InferenceConfig(
                contextTokens = affordableContext(deviceCapability, catalogedContextTokens),
                threads = threads,
                // Prompt processing (threadsBatch) parallelises across the batch, but more threads than
                // [threadsFor] gives were measured SLOWER on the reference device (see [threadsFor]): the
                // detected performance cores only ever lower it.
                threadsBatch = performanceCoreThreadCount(coreMaxFreqsKHz)?.let { minOf(it, threads) } ?: threads,
            )
        }

        /**
         * Threads to use for prompt processing (`threadsBatch`), preferring the SoC's
         * performance cluster over its efficiency cores on a big.LITTLE design.
         *
         * Prompt eval is compute-bound and parallel across `n_batch` — every core helps —
         * whereas token generation is a mostly-serial chain of single-token decodes where
         * more threads past a point mostly adds coordination overhead (why [defaultThreadCount]
         * intentionally stays at half the cores). The two workloads want different thread
         * counts, which is why `threadsBatch` exists as its own [InferenceConfig] field.
         *
         * @param coreMaxFreqsKHz each logical core's max scaling frequency, in any order —
         *   this only ever counts them, never assumes an ordering or a specific core index.
         *   Read by the caller from the platform.
         * @return null when the input does not look like a real big.LITTLE reading (fewer
         *   than 2 cores, or every core reporting the same frequency, e.g. a device that
         *   hides cpufreq, an emulator, or a read failure) — the caller falls back to
         *   [defaultThreadCount] in that case, same as it always did.
         */
        fun performanceCoreThreadCount(coreMaxFreqsKHz: List<Long>): Int? {
            val freqs = coreMaxFreqsKHz.filter { it > 0 }
            if (freqs.size < 2) return null

            val minFreq = freqs.min()
            val maxFreq = freqs.max()
            if (minFreq == maxFreq) return null // homogeneous — not big.LITTLE, or a bad read.

            val performanceCores = freqs.count { it > minFreq * PERFORMANCE_CORE_THRESHOLD }
            return performanceCores.takeIf { it in 1 until freqs.size }?.coerceAtLeast(2)
        }

        /**
         * What the device can **afford**, not what the model supports.
         *
         * These are different numbers and conflating them crashes the app. The context
         * window is a KV cache allocated up front and proportional to its size: Qwen3.5 2B
         * catalogues 32k, and asking for all of it on a phone with 2.5 GB free killed the
         * inference process inside `llama_decode` — a native abort, so nothing catchable,
         * just a dead process and a document that failed to be read.
         *
         * The catalogued value stays the ceiling, since the model genuinely cannot go beyond
         * it. What is subtracted is reality.
         *
         * The bands are deliberately generous toward the small end. The failure is not a
         * degraded answer but a native abort that takes the inference process with it, and
         * the cost of a smaller window on a one-page letter is nothing — its layout
         * description is a few thousand characters either way.
         *
         * The steps are deliberately coarse. Exact KV cost depends on layer count, head
         * count and head dimension, none of which the catalog records; a heuristic that is
         * roughly right and always conservative is worth more than a precise formula fed
         * from values this layer does not have. Task 7.2.9 — measuring peak RSS per model —
         * is what would replace it.
         */
        private fun affordableContext(
            deviceCapability: DeviceCapability,
            catalogedContextTokens: Int,
        ): Int {
            val available = deviceCapability.availableRamBytes

            val affordable = when {
                available < 1500 * MB -> 2048
                // Ceiling of 4096 everywhere, on evidence rather than caution. A device test
                // on the reference phone generated happily at 4096 and the app aborted at
                // 8192 — after `am kill-all`, with more memory free than when 4096
                // succeeded. So `availMem` does not predict whether the allocation lands,
                // and the failure is not a degraded answer but a native abort that kills the
                // inference process.
                //
                // Nothing is lost meanwhile: a one-page letter's layout description is a few
                // thousand characters, and chat grounds on a handful of retrieved passages.
                // The larger tiers come back when 7.2.9 measures peak RSS per model, which is
                // the number this should key off instead of a guess.
                else -> 4096
            }
            return minOf(catalogedContextTokens, affordable)
        }

        /**
         * Half the cores, at least two.
         *
         * Using every core measurably starves the UI thread — generation is CPU-bound and
         * will happily consume everything it is given.
         */
        fun defaultThreadCount(): Int = threadsFor(Runtime.getRuntime().availableProcessors())

        /**
         * The thread count for a device with [cores] logical cores, as data: [THREADS_MEASURED] holds what was measured, and any other
         * core count gets half the cores (at least two), the rule that matches it.
         *
         * Measured on the reference device (Galaxy S23, 8 cores, Qwen3.5-0.8B Q4_K_M, CPU), prompt processing in tokens per second:
         * 3 threads 68, 4 threads 106, 5 threads 66, 6 threads 59, 8 threads 21 (the extra threads land on the slow efficiency cores
         * and the whole batch waits for them). Generation showed the same order. So 4, which is also half the cores.
         */
        fun threadsFor(cores: Int): Int = THREADS_MEASURED[cores] ?: (cores / 2).coerceAtLeast(2)

        /** Logical core count to the thread count that measured best on such a device. */
        private val THREADS_MEASURED = mapOf(8 to 4)

        private const val MB = 1024L * 1024L

        /**
         * A core counts as "performance" once its max frequency is at least this multiple of
         * the slowest core's — chosen to sit clearly between a typical efficiency cluster and
         * a mid/performance cluster (e.g. this project's reference device, a Snapdragon
         * 8 Gen 2: 3 Cortex-A510 efficiency cores near 2.0 GHz vs. 2x A710 + 2x A715 near
         * 2.8 GHz + 1x Cortex-X3 near 3.36 GHz — a 1.15x threshold cleanly separates the two
         * without depending on exact model numbers, which vary chip to chip).
         */
        private const val PERFORMANCE_CORE_THRESHOLD = 1.15
    }
}

/** How expensive applying a changed [InferenceConfig] is. */
enum class ReloadScope {
    /** Sampling-only change; nothing native needs to change before the next generation. */
    NONE,

    /** Context/batch/thread/flash-attention change; recreate the `llama_context`, keep the model. */
    CONTEXT,

    /** mmap/mlock/accelerator/gpuLayers change; the model must be reloaded from disk. */
    MODEL,
}

/** Where generation runs. GPU is a seam for Phase 2 — every model ships CPU-capable. */
@Serializable
enum class Accelerator {
    CPU,
    GPU,
    ;

    companion object {
        fun fromLabel(label: String): Accelerator =
            entries.firstOrNull { it.name.equals(label, ignoreCase = true) } ?: CPU
    }
}

/**
 * Picks the accelerator a model actually loads with.
 *
 * The user's preference wins whenever it is possible — both the device and the model have
 * to agree it will work, since a GPU preference the device cannot honour (no Vulkan
 * backend) or the model does not support (CPU-only in the catalog) is not a real choice.
 * Failing that, the first accelerator the *model* prefers that the *device* also supports
 * wins — the model's [BackendSpec.accelerators] is already in preference order. CPU is the
 * unconditional fallback: every model ships CPU-capable (see [BackendSpec]'s default), so
 * this never needs to fail.
 *
 * @param preference what the user asked for, or null for "no opinion" — the common case
 *   until Phase 4 exposes this as a setting.
 * @param device accelerators [DeviceCapability] reports as available, probed natively.
 * @param model what this specific model may run on.
 */
fun resolveAccelerator(
    preference: Accelerator?,
    device: Set<Accelerator>,
    model: BackendSpec,
): Accelerator {
    val jointlySupported = model.accelerators.filter { it in device }
    if (preference != null && preference in jointlySupported) return preference
    return jointlySupported.firstOrNull() ?: Accelerator.CPU
}

/**
 * Layers to offload for [accelerator], as resolved by [resolveAccelerator].
 *
 * Zero is the only valid value on CPU — offloading nothing is what "CPU" *means* here, so
 * [BackendSpec.gpuLayers] (which describes the GPU case) is ignored rather than trusted
 * when the resolved accelerator turned out to be CPU.
 */
fun resolveGpuLayers(accelerator: Accelerator, model: BackendSpec): Int =
    if (accelerator == Accelerator.CPU) 0 else model.gpuLayers

/**
 * Sampling parameters for one generation.
 *
 * Lives on [InferenceConfig] rather than being hardcoded in the JNI layer so a caller can
 * ask for near-deterministic output (low temperature, as `AiExtractionUseCase` does) without
 * a native code change.
 */
@Serializable
data class SamplingConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    /** Presence penalty over the current reply's own tokens; 0 disables it. */
    val presencePenalty: Float = 0f,
    /** Null means llama.cpp picks a random seed (`LLAMA_DEFAULT_SEED`). */
    val seed: Long? = null,
)
