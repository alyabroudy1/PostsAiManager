package com.postsaimanager.core.domain.ai

/**
 * One schema-constrained answer from the resident chat model (LiteRT-LM `ResponseFormat.json`), as [ChatEngine.generateStructured]
 * takes it: a conversation of its own, thinking off, no tools, closed again after the answer.
 *
 * @property schema a JSON Schema (as text) the answer is constrained to: the engine can only produce JSON that fits it
 * @property imagePaths files of the pictures that go in front of [prompt] (paths only: no picture bytes cross the process boundary);
 *   the engine starts its vision encoder when there is one
 * @property timeoutMs the engine stops the generation when it takes longer than this, and answers null
 * @property leadPrompt when set, the conversation has two turns: this first message (with the pictures) is answered in free text, which
 *   goes to [onLead] at once; then [prompt] is asked in the same conversation (nothing is prefilled twice) and its answer, constrained
 *   to [schema], is the result. When null the one message [prompt] carries the pictures and its answer is the result.
 * @property onLead receives the free-text answer of the first turn the moment it is complete, while the second turn still runs
 */
data class StructuredRequest(
    val system: String,
    val prompt: String,
    val schema: String,
    val imagePaths: List<String> = emptyList(),
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    // Greedy by default ([samplingFor] STRUCTURED): every constrained call is a structured request.
    val temperature: Float = samplingFor(SamplingPurpose.STRUCTURED).temperature,
    val topK: Int = samplingFor(SamplingPurpose.STRUCTURED).topK,
    val topP: Float = samplingFor(SamplingPurpose.STRUCTURED).topP,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val leadPrompt: String? = null,
    val onLead: (suspend (String) -> Unit)? = null,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
