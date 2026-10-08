package com.postsaimanager.core.domain.ai

/**
 * One schema-constrained answer from the resident chat model (LiteRT-LM `ResponseFormat.json`), as [ChatEngine.generateStructured]
 * takes it: a conversation of its own, thinking off, no tools, closed again after the answer.
 *
 * @property schema a JSON Schema (as text) the answer is constrained to: the engine can only produce JSON that fits it
 * @property imagePaths files of the pictures that go in front of [prompt] (paths only: no picture bytes cross the process boundary);
 *   the engine starts its vision encoder when there is one
 * @property timeoutMs the engine stops the generation when it takes longer than this, and answers null
 */
data class StructuredRequest(
    val system: String,
    val prompt: String,
    val schema: String,
    val imagePaths: List<String> = emptyList(),
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TEMPERATURE = 0.1f
        const val DEFAULT_TOP_K = 20
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
