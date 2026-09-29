package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession

/**
 * A [PromptSession] that answers what a test tells it to, and models the contract the real one keeps:
 * the prefix is decoded once, every question starts from exactly the prefix, and the state is rolled
 * back to the prefix after each answer, however the answer went.
 *
 * The "state" is the text the model would be holding. [stateAtAsk] records what it held when each question
 * began, so a test can assert that no question ever saw an earlier question or answer.
 */
class FakePromptSession : PromptSession {

    /** One question as [ask] received it. */
    class Ask(val question: String, val grammar: String, val maxTokens: Int, val answer: String?)

    /** Answers a question; null makes [ask] fail. */
    var responder: (question: String, grammar: String) -> String? = { _, _ -> "NONE" }

    /** Tokens of [text]; deliberately not chars/2.5, so a test can tell which one a caller used. */
    var tokenCounter: (String) -> Int = { (it.length + 3) / 4 }

    /** False makes [countTokens] answer null, like an engine that cannot count. */
    var canCount: Boolean = true

    /** Make [open] fail with this. */
    var openFailsWith: PamError? = null

    val opens = mutableListOf<String>()
    val asks = mutableListOf<Ask>()
    val stateAtAsk = mutableListOf<String>()
    val countedTexts = mutableListOf<String>()

    /** How many times the prefix was (re)decoded: once by [open], again after [clobber]. */
    var prefixDecodes = 0
        private set

    var closes = 0
        private set

    val isOpen: Boolean get() = prefix != null

    private var prefix: String? = null
    private var state: String? = null

    /** What another engine user does to the KV cache (a chat turn, a one-shot generation): the state is gone. */
    fun clobber() {
        state = null
        clobbered = true
    }

    private var clobbered = false

    override suspend fun open(prefix: String): PamResult<Int> {
        openFailsWith?.let { return PamResult.Error(it) }
        opens += prefix
        this.prefix = prefix
        state = prefix
        prefixDecodes++
        return PamResult.Success(tokenCounter(prefix))
    }

    override suspend fun ask(question: String, grammar: String, maxTokens: Int): PamResult<String> {
        val head = prefix ?: return PamResult.Error(PamError.InferenceError("no prompt session is open"))
        if (state == null) {
            // The engine repairs a lost state by reading the prefix again.
            state = head
            prefixDecodes++
        }
        stateAtAsk += state.orEmpty()
        clobbered = false
        val answer = try {
            responder(question, grammar)
        } finally {
            // The rollback: the question and its answer are gone. (A clobber that happened during the
            // question, from a test's responder, is another engine user acting after the rollback.)
            state = if (clobbered) null else head
        }
        asks += Ask(question, grammar, maxTokens, answer)
        return if (answer == null) PamResult.Error(PamError.InferenceError("the fake failed this question")) else PamResult.Success(answer)
    }

    override suspend fun close() {
        closes++
        prefix = null
        state = null
    }

    override suspend fun countTokens(text: String): Int? {
        if (!canCount) return null
        countedTexts += text
        return tokenCounter(text)
    }
}
