package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiEngine

/** [PromptFraming] through the engine's chat template (the same cut the zone interpreters make). */
class AiEnginePromptFraming(private val engine: AiEngine) : PromptFraming {

    override fun frame(system: String, user: String): Pair<String, String> {
        val rendered = engine.formatPrompt(
            listOf(AiChatMessage(AiChatRole.SYSTEM, system), AiChatMessage(AiChatRole.USER, user + MARK)),
        )
        val at = rendered.lastIndexOf(MARK)
        return if (at < 0) rendered to "" else rendered.substring(0, at) to rendered.substring(at + MARK.length)
    }

    private companion object {
        const val MARK = "@@QUESTION@@"
    }
}
