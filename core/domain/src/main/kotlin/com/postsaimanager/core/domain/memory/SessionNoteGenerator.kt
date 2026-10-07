package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.model.ModelLoadState
import javax.inject.Inject

/**
 * The one short generation that writes a session's notes. A port so the use case is tested with a fake and so that it never learns
 * which runtime answers.
 *
 * Quiet work: it must never load a model, evict a chat or wait behind a reply. An implementation says [isAvailable] only when a chat
 * model is resident and idle, and answers null when it could not generate (busy, no model, failure).
 */
interface SessionNoteGenerator {

    /** True when a chat model is resident and not busy right now: a cheap read, decided again by [generate]. */
    fun isAvailable(): Boolean

    /** The model's answer to [prompt] under [system], or null when nothing could be generated. */
    suspend fun generate(system: String, prompt: String): String?
}

/**
 * [SessionNoteGenerator] over the chat engine's one-off generation ([ChatEngine.generateOnce]), which leaves the chat's own
 * transcript, history and committed turns untouched. Greedy-ish sampling (a low temperature): the notes are facts, not prose.
 */
class ChatEngineSessionNoteGenerator @Inject constructor(
    private val engine: ChatEngine,
) : SessionNoteGenerator {

    override fun isAvailable(): Boolean = engine.state.value is ModelLoadState.Ready && !engine.isBusy

    override suspend fun generate(system: String, prompt: String): String? {
        if (!isAvailable()) return null
        return engine.generateOnce(
            system,
            AiRequest(prompt = prompt, maxTokens = SessionNotesFormat.MAX_TOKENS, temperature = TEMPERATURE, topK = 1, thinkingEnabled = false),
        )
    }

    private companion object {
        const val TEMPERATURE = 0.1f
    }
}
