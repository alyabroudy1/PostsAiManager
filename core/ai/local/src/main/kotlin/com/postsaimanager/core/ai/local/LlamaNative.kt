package com.postsaimanager.core.ai.local

import android.util.Log

/**
 * Thin JNI surface over llama.cpp.
 *
 * Deliberately dumb: it owns no state beyond the opaque handle from [loadModel], and
 * generation is a pull-based stream so the interesting logic — lifecycle, cancellation,
 * error mapping — lives in testable Kotlin rather than C++.
 *
 * Not thread-safe per handle. [LocalAiEngine] serialises access through a mutex.
 */
internal object LlamaNative {

    @Volatile
    private var loaded = false

    /** @return false if the native library is unavailable — never throws. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (loaded) return@synchronized true
            try {
                applyVulkanWorkaroundEnv()
                System.loadLibrary("pam_llama")
                backendInit()
                loaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                // A device whose ABI we do not ship must degrade to "no local AI",
                // not crash on first use.
                false
            }
        }
    }

    /**
     * Sets `GGML_VK_*` environment variables (read via `getenv()` inside
     * `ggml-vulkan.cpp`, at Vulkan device init — the first model load, not
     * [backendInit] itself) from the `debug.pam.vk_env` system property, a
     * comma-separated list of names to set to `"1"` — e.g.
     * `adb shell setprop debug.pam.vk_env GGML_VK_DISABLE_COOPMAT,GGML_VK_DISABLE_F16`.
     *
     * This exists for the Adreno pipeline-link-failure investigation (see
     * documentation/02-architecture.md §5.3): rather than hardcoding one candidate and
     * rebuilding per try, the candidate list is set from outside the APK so the same build
     * can be re-tested against each `GGML_VK_DISABLE_*` flag ggml-vulkan.cpp honours.
     * `SystemProperties` is a hidden API, so this goes through reflection and degrades to
     * "nothing set" (the normal path) if that ever breaks — never worth failing native
     * library load over a debug knob.
     */
    private fun applyVulkanWorkaroundEnv() {
        // `android.os.SystemProperties` is on the hidden-API blocklist on modern Android —
        // reflection into it fails silently on-device even though it compiles — so this
        // shells out to the `getprop` binary instead, which needs no special permission to
        // read a non-privileged property.
        val raw = runCatching {
            ProcessBuilder("/system/bin/getprop", "debug.pam.vk_env")
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    val output = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    output
                }
        }.getOrDefault("")
        if (raw.isBlank()) return
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            runCatching {
                android.system.Os.setenv(name, "1", true)
                Log.w("LlamaNative", "vk workaround: $name=1 (from debug.pam.vk_env)")
            }
        }
    }

    external fun backendInit()

    external fun systemInfo(): String

    /**
     * Which accelerator types the native backend reports as available on this device,
     * probed via `ggml_backend_dev_count()`/`ggml_backend_dev_type()` over every registered
     * `ggml` backend device.
     *
     * GPU is reported only when a device of type `GGML_BACKEND_DEVICE_TYPE_GPU` (or
     * `..._IGPU`) is actually enumerated — which, until the `pam.gpuBackend=vulkan` Gradle
     * switch compiles a GPU backend in, is never, since the CPU-only build registers CPU
     * devices only. CPU is always included.
     *
     * @return each entry is an [com.postsaimanager.core.model.Accelerator] ordinal
     *   (0 = CPU, 1 = GPU), deduplicated.
     */
    external fun availableAccelerators(): IntArray

    /** Diagnostics: name and description of every registered backend device, one per line. */
    external fun backendDescription(): String

    /**
     * Loads a model and creates a context from every field of `InferenceConfig` — see
     * [com.postsaimanager.core.model.InferenceConfig] for what each parameter means and why
     * it is a config field rather than a literal in the JNI bridge.
     *
     * A previously loaded model in this process, if any, is always freed on the native side
     * before this one is allocated — the process holds at most one resident model on at most
     * one accelerator at a time. See `llama_jni.cpp`'s `loadModel` for the guard.
     *
     * @param gpuLayers layers offloaded to an accelerator; -1 = all, 0 = none. Ignored (forced
     *   to 0) when [accelerator] is CPU — see `llama_jni.cpp`.
     * @param accelerator [com.postsaimanager.core.model.Accelerator] ordinal (0 = CPU, 1 =
     *   GPU). On CPU, `llama_model_params.devices` is restricted to CPU-type devices so the
     *   GPU backend (Vulkan) is never registered for this model at all — `gpuLayers = 0` alone
     *   is not enough, since llama.cpp still offloads some ops to a registered non-CPU device
     *   regardless of layer count. See documentation/02-architecture.md §5.3.
     * @return an opaque handle, or 0 on failure.
     */
    external fun loadModel(
        modelPath: String,
        contextTokens: Int,
        batchTokens: Int,
        threads: Int,
        threadsBatch: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        flashAttention: Boolean,
        gpuLayers: Int,
        accelerator: Int,
    ): Long

    external fun freeModel(handle: Long)

    /**
     * Diagnostic: which devices the most recent successful [loadModel] call actually passed
     * to `llama_model_params.devices` — `"CPU"`, `"all"`, or `"none"` if nothing has loaded
     * yet in this process. See `llama_jni.cpp`'s `lastLoadDevices`.
     */
    external fun lastLoadDevices(): String

    /**
     * Recreates the `llama_context` for an already-resident model — [ReloadScope.CONTEXT][
     * com.postsaimanager.core.model.ReloadScope]. The `llama_model` itself (and the memory
     * or mmap it occupies) is untouched; only context/batch/thread/flash-attention size
     * change, which is why this is far cheaper than [loadModel].
     *
     * @return false if the context could not be recreated. On failure the previous context
     *   remains valid — [handle] is only ever left with the old context or the new one, never
     *   neither.
     */
    external fun recreateContext(
        handle: Long,
        contextTokens: Int,
        batchTokens: Int,
        threads: Int,
        threadsBatch: Int,
        flashAttention: Boolean,
    ): Boolean

    /**
     * Begins a generation; pull tokens with [nextToken].
     *
     * Clears the KV cache, so each call starts from a clean context.
     *
     * @param grammar GBNF source, or null for unconstrained sampling. With a grammar the
     *   sampler cannot emit output that violates it — the basis of the Phase 8 tool layer.
     * @param seed -1 means "no seed" (`LLAMA_DEFAULT_SEED` — a random seed each call).
     * @return false if the prompt was empty or tokenisation failed.
     */
    external fun startGeneration(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Long,
        grammar: String?,
    ): Boolean

    /** @return the next token's text, or null when generation is complete. */
    external fun nextToken(handle: Long): String?

    /** Releases generation state early. Safe to call when nothing is running. */
    external fun stopGeneration(handle: Long)

    /**
     * Formats a conversation with the model's **own** chat template, taken from its GGUF
     * metadata.
     *
     * @return the formatted prompt, or null if the model declares no template.
     */
    external fun formatChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    external fun hasChatTemplate(handle: Long): Boolean

    /** Debug only — intentionally segfaults to measure crash blast radius (spike Q3). */
    external fun crashForTesting()
}
