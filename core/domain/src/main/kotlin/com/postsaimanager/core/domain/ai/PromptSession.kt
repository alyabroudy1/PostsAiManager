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

    /**
     * Label-free scoring: for each of [continuations] (text that follows the prefix, like [ask]'s
     * question, including whatever closes the user turn and opens the assistant's), decodes it after
     * the prefix, reads the model's next-token logits at its last position and returns
     * `logit(yes) - logit(no)`, the log-odds of the [yes] word against the [no] word (the first token
     * of each). Rolls back to the prefix after every continuation, so scores are independent of each
     * other and of their order. No generation, no grammar: one forward pass each. A caller shows no
     * option labels and picks by argmax with its own abstain threshold.
     *
     * Same sharing rules as [ask]: a lost state is repaired by re-decoding the prefix once.
     *
     * [shared] is a second level of the prefix tree: text every continuation starts with, decoded once after
     * the prefix and remembered (a checkpoint), so each continuation is rolled back to it and pays only for
     * its own tokens. The scores are those of `prefix + shared + continuation` read as one text, so a caller
     * may split a continuation at any point without changing its score. Empty: no shared level.
     *
     * @return one score per continuation, in order; an error when the model or session is missing or
     *   a continuation does not fit.
     */
    suspend fun score(continuations: List<String>, yes: String, no: String, shared: String = ""): PamResult<List<Double>>

    /**
     * Scores every one of [heads] followed by every one of [asks] (`shared + head + ask` read as one text): a three-level prefix tree,
     * where what the questions have in common is decoded once. [shared] is decoded once, each head once after it, and each ask after its
     * head. A caller whose questions are about one value (a head) under several statements (the asks) and one zone block (shared) pays
     * for the block and the value once, not once per statement.
     *
     * The default reads the grid through [score] (every cell whole), which gives the same scores; an engine that can keep checkpoints at
     * two levels overrides it to decode less.
     *
     * @return `scores[i][j]` for head `i` under ask `j`.
     */
    suspend fun scoreGrid(shared: String, heads: List<String>, asks: List<String>, yes: String, no: String): PamResult<List<List<Double>>> {
        if (heads.isEmpty() || asks.isEmpty()) return PamResult.Success(heads.map { emptyList() })
        return when (val flat = score(heads.flatMap { h -> asks.map { a -> h + a } }, yes, no, shared)) {
            is PamResult.Error -> flat
            is PamResult.Success -> PamResult.Success(heads.indices.map { i -> asks.indices.map { j -> flat.data[i * asks.size + j] } })
        }
    }

    /** Drops the session. Safe to call when none is open. */
    suspend fun close()

    /**
     * How many tokens [text] is for the loaded model, so callers budget in tokens rather than in an
     * estimate of characters. Null when the count cannot be had (no model loaded, engine not
     * reachable); callers then fall back to their estimate.
     */
    suspend fun countTokens(text: String): Int?
}
