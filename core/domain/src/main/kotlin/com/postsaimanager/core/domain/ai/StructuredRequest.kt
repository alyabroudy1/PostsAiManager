package com.postsaimanager.core.domain.ai

/**
 * One schema-constrained answer from the resident chat model (LiteRT-LM `ResponseFormat.json`), as [ChatEngine.generateStructured]
 * takes it: a conversation of its own, thinking off, no tools, closed again after the answer (unless [keepOpenAs] keeps it).
 *
 * @property schema a JSON Schema (as text) the answer is constrained to: the engine can only produce JSON that fits it. Blank: no
 *   constraint, the last turn is answered in free text (the "Questions" reader style)
 * @property imagePaths files of the pictures that go in front of [prompt] (paths only: no picture bytes cross the process boundary);
 *   the engine starts its vision encoder when there is one
 * @property timeoutMs the engine stops the generation when it takes longer than this, and answers null
 * @property leadPrompt when set, the conversation has two turns: this first message (with the pictures) is answered in free text, which
 *   goes to [onLead] at once; then [prompt] is asked in the same conversation (nothing is prefilled twice) and its answer, constrained
 *   to [schema], is the result. When null the one message [prompt] carries the pictures and its answer is the result.
 * @property onLead receives the free-text answer of the first turn the moment it is complete, while the second turn still runs
 * @property onPartial for a one-turn free-text request ([leadPrompt] null, blank [schema]): receives the answer so far each time a line
 *   of it is complete, while the rest is still being decoded (the "Questions" reader shows its summary line as soon as it ends)
 * @property keepOpenAs when set, the conversation is not closed after a good answer: it stays open under this key (the document's id) so
 *   that [ChatEngine.continueStructured] can ask follow-up questions in it, with the letter and the pictures already in its cache. It
 *   lives until [ChatEngine.closeStructured], or until any other caller uses the model (then it is gone, and a follow-up answers null).
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
    val keepOpenAs: String? = null,
    val onPartial: (suspend (String) -> Unit)? = null,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}

/**
 * One more constrained question in the conversation a [StructuredRequest.keepOpenAs] kept open under [key]: [prompt] is the next turn
 * (the letter and the earlier answers are already there), the answer is constrained to [schema]. The conversation stays open.
 */
data class FollowUpRequest(
    val key: String,
    val prompt: String,
    val schema: String,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        /** An answer that is one id of a short list is a few tokens; this only stops a runaway one. */
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
