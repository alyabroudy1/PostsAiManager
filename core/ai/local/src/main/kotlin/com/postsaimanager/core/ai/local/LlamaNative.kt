package com.postsaimanager.core.ai.local

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

    external fun backendInit()

    external fun systemInfo(): String

    /** @return an opaque handle, or 0 on failure. */
    external fun loadModel(modelPath: String, contextTokens: Int, threads: Int): Long

    external fun freeModel(handle: Long)

    /**
     * Begins a generation; pull tokens with [nextToken].
     *
     * Clears the KV cache, so each call starts from a clean context.
     *
     * @param grammar GBNF source, or null for unconstrained sampling. With a grammar the
     *   sampler cannot emit output that violates it — the basis of the Phase 8 tool layer.
     * @return false if the prompt was empty or tokenisation failed.
     */
    external fun startGeneration(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
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
