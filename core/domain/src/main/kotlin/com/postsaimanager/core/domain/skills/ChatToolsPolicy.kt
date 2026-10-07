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

    /**
     * Adapted from the Gallery's agent-chat system prompt (Apache 2.0): the skills' names and descriptions, then "load the skill
     * first". Ours adds what is different here: the answer is grounded on the user's letters, a tool call only proposes a card
     * the user must confirm, and values come from the letters or the user, never from the model's own head.
     *
     * @param skillsList [SkillCatalog.namesAndDescriptions]
     */
    fun build(skillsList: String): String = TEMPLATE.replace(SKILLS_PLACEHOLDER, skillsList)

    private const val SKILLS_PLACEHOLDER = "___SKILLS___"

    // The placeholder is replaced after trimIndent: a multi-line list substituted before it would break the indentation.
    private val TEMPLATE = """
        You can also act for the user on their letters, using skills. These are the skills you have:

        ___SKILLS___

        When the user asks for something one of these skills covers, call the `load_skill` tool with that skill's name and follow its instructions exactly. The skill tells you when to call `run_intent`. Never call `run_intent` before you have loaded the skill that asks for it.

        `run_intent` does nothing by itself: it shows the user a card in the chat, and nothing happens until the user opens it. So after you call it, say in one or two short sentences, in the user's language, what you prepared and that they can check it on the card. Do not repeat the card's content, and do not say the action is done.

        Take every name, address, date, amount and reference from the letters or from the user's own words, never from memory or guesswork. If a value you need is not there, ask the user for it instead of calling the tool. For ordinary questions about the letters, just answer; do not use a skill.
    """.trimIndent()
}
