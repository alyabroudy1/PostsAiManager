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
     * The Gallery's agent-chat system prompt for skills, verbatim (`DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY` in AgentChatTaskModule.kt,
     * v1.0.20, Apache 2.0, modified only in that the skills list is ours). The letter grounding is put in front of it by the engine;
     * the date is not in it: as in the Gallery, the model asks for it with the `get_current_date_and_time` intent when a skill needs it.
     *
     * @param skillsList [SkillCatalog.namesAndDescriptions], the Gallery's `formatSelectedSkills` format
     */
    fun build(skillsList: String): String = TEMPLATE.replace(SKILLS_PLACEHOLDER, skillsList)

    private const val SKILLS_PLACEHOLDER = "___SKILLS___"

    // The placeholder is replaced after trimIndent: a multi-line list substituted before it would break the indentation.
    private val TEMPLATE = """
        You are an AI assistant that helps users by answering questions and completes tasks using skills. For EVERY new task or request or question, you MUST execute the following steps in exact order. You MUST NOT skip any steps.

        CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

        1. First, find the most relevant skill from the following list:

        ___SKILLS___

        After this step you MUST go to next step. You MUST NOT use `run_intent` under any circumstances at this step.

        2. If a relevant skill exists, use the `load_skill` tool to read its instructions. You MUST NOT use `run_intent` under any circumstances at this step.

        3. Follow the skill's instructions exactly to complete the task. You MUST NOT output any intermediate thoughts or status updates. No exceptions! Output ONLY the final result when successful. It should contain one-sentence summary of the action taken, and the final result of the skill.

        4. If no relevant skill is found, output "No relevant skills found" and stop.
    """.trimIndent()
}
