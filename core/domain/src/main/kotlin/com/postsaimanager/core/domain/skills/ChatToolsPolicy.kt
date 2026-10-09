package com.postsaimanager.core.domain.skills

import com.postsaimanager.core.domain.ai.ChatToolsRequest
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelRuntime

/**
 * When a chat reply gets the Agent Skills tools, and which letter they are grounded on. Pure decisions, so they are pinned by
 * tests and no feature or engine repeats them.
 */
object ChatToolsPolicy {

    /**
     * Tools are offered only by a LiteRT-LM model whose catalogue entry declares tool support. llama.cpp chat stays tool-less
     * whatever its descriptor says: it has no tool-calling loop.
     */
    fun enabledFor(config: InferenceConfig): Boolean = config.runtime == ModelRuntime.LITERT_LM && config.supportsTools

    /** The tools request of one reply, or null when the model gets none. */
    fun requestFor(config: InferenceConfig, chatDocumentId: String?, sourceDocumentIds: List<String>): ChatToolsRequest? =
        if (enabledFor(config)) ChatToolsRequest(documentFor(chatDocumentId, sourceDocumentIds)) else null

    /**
     * The letter an action of this reply is grounded on. A chat about one letter: that letter. A chat over all letters: the
     * letter the reply's passages come from when they all come from one, otherwise none (the card then flags the values it
     * cannot find in a letter), because guessing which of several letters the user meant would check a value against the
     * wrong one.
     */
    fun documentFor(chatDocumentId: String?, sourceDocumentIds: List<String>): String? =
        chatDocumentId ?: sourceDocumentIds.distinct().singleOrNull()
}

/** What the model is told, in its system prompt, about the skills and how its tools behave. English: the model answers in the user's language. */
object ChatToolsPrompt {

    /** The most characters the whole tools prompt may have: it is prefilled at every chat warm-up (about 20 s on CPU for 2,000). */
    const val MAX_CHARS = 600

    /**
     * A short hint instead of the Gallery's long `DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY` (shortened for speed; the skills, the tools
     * and the on-demand `load_skill` are still the Gallery's). The letter grounding is put in front of it by the engine; the date is
     * not in it (a time-aware skill gets it with its text).
     *
     * @param skillsList [SkillCatalog.namesAndDescriptions]: one `- name: description` line per skill
     */
    fun build(skillsList: String): String = "$HINT\n$skillsList"

    private const val HINT =
        "You can use these skills when the user asks for an action. Call load_skill(name) to read a skill's steps, " +
            "then follow them. For ordinary questions about the letter, just answer."
}
