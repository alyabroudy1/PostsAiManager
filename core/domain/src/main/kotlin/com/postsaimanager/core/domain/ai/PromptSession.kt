package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.common.result.PamResult

/**
 * "Read once, ask many short questions": a prefix is decoded a single time and every question
 * after it starts from that same state.
 *
 * A separate, small port on purpose: [AiEngine] stays what it is (one-shot generation and the
 * standing chat), and a caller that wants many grammar-constrained answers about one text does not
 * pay to read the text again for each of them. Implemented by the same engines as [AiEngine]
 * (`RemoteAiEngine`, `LocalAiEngine` in `:core:ai:local`), bound separately in DI.
 *
 * ### Contract
 *
 * - [open] decodes [open]'s `prefix` once and remembers where it ends (a checkpoint).
 * - [ask] decodes only the question, generates greedily under the question's own grammar, then
 *   **rolls back** to the checkpoint, so the next question sees exactly the prefix and never an
 *   earlier question or answer. Answers are therefore independent of the order they are asked in.
 * - [close] drops the session.
 * - One session at a time: [open] replaces the previous one.
 *
 * ### Sharing the engine
 *
 * Every call takes the engine's mutex, exactly like [AiEngine]'s own calls (so a prompt session is
 * never interleaved with a chat turn or a [AiEngine.generate] on the native context), but the
 * session is **not** held across calls: a chat message may run between two questions. Anything that
 * touches the KV cache in between (a chat, a one-shot generation, a model reload) invalidates the
 * checkpoint, and the next [ask] transparently re-decodes the prefix once before answering. In the
 * other direction, [open] takes the KV cache over from any standing chat session, which then
 * re-primes (see [AiEngine.ensureChatSession]), the same as after a one-shot [AiEngine.generate].
 */
interface PromptSession {

    /**
     * Decodes [prefix] and makes it the state every [ask] starts from. [prefix] is the complete text
     * before the first question, already in the model's chat format (the caller renders the template).
     *
     * @return the number of tokens the prefix took, or an error (no model, prefix longer than the
     *   context window).
     */
    suspend fun open(prefix: String): PamResult<Int>

    /**
     * Answers [question] (the text that follows the prefix, including whatever closes the user turn
     * and opens the assistant's) with at most [maxTokens] tokens, constrained by the GBNF [grammar],
     * greedy. The state is rolled back to the prefix afterwards, whatever happens.
     */
    suspend fun ask(question: String, grammar: String, maxTokens: Int): PamResult<String>

    /** Drops the session. Safe to call when none is open. */
    suspend fun close()

    /**
     * How many tokens [text] is for the loaded model, so callers budget in tokens rather than in an
     * estimate of characters. Null when the count cannot be had (no model loaded, engine not
     * reachable); callers then fall back to their estimate.
     */
    suspend fun countTokens(text: String): Int?
}
