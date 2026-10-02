package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.AgentSpec
import com.postsaimanager.core.domain.agent.AgentTranscript
import com.postsaimanager.core.domain.agent.ToolRegistry
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.string
import com.postsaimanager.core.domain.agent.strings
import com.postsaimanager.core.domain.form.fill.FillProgress
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The form agent as the loop sees it: what the model is told once (the instructions, with the language to speak), the compact
 * summary of where the fill stands (read from the stored fill, so it is the same after a restart) and the nudge after two
 * failed steps. There is no question order and no wording in here: the instructions tell the model what the tools are for and
 * the model decides the rest.
 */
class FormAgentSpec(
    private val env: FormToolEnv,
    private val guidance: FormGuidance,
    override val tools: ToolRegistry,
    /** Which tools the model may use in the current state; null exposes them all (tests of the tools alone). */
    private val exposure: FormToolExposure? = null,
) : AgentSpec {

    override val conversationId: String = "agent-${env.documentId}"

    override suspend fun systemPrompt(): String = instructions(guidance.languageName())

    /** The STATE block (language, people, counts, the open fields and the suggested next step) that ends every tool result. */
    override suspend fun stateSummary(): String? = guidance.state()

    /** The same, aware of the user's latest answer (a role answered with typed text is suggested the step that uses it). */
    override suspend fun stateSummary(entries: List<AgentEntry>): String? = guidance.state(UserReply.of(AgentContext.of(entries)))

    override suspend fun allowedTools(entries: List<AgentEntry>): List<String>? = exposure?.allowed(entries)

    override suspend fun replyResult(call: AgentEntry.Call, text: String): ToolResult? =
        if (call.name == AskUserTool.NAME) guidance.replyResult(call, text) else null

    /**
     * The filled and open counts after every tool, and for an ask its question and chips (written by the model; never a stored value
     * or the user's answer).
     */
    override suspend fun traceNote(call: AgentEntry.Call): String? {
        val fields = env.fields()
        val counts = if (fields.isEmpty()) "filled=0/0 open=0" else FillProgress.of(fields).let { "filled=${it.ready}/${it.total} open=${FormRefs.open(fields).size}" }
        if (call.name != AskUserTool.NAME) return counts
        val chips = call.args.strings("chips").orEmpty().joinToString("|")
        return "$counts question=\"${call.args.string("question").orEmpty()}\" chips=[$chips]"
    }

    override suspend fun stuckHint(): String? {
        val fields = env.fields()
        val next = FormRefs.open(fields).firstOrNull() ?: return "Nothing is open: call show_fill_card and then finish."
        return "Next open field: ${FormRefs.fieldAlias(fields, next)} \"${next.labelText}\" (${next.kind.name.lowercase()}). " +
            "Ask the user about it with ask_user, or leave it with skip_field."
    }

    companion object {
        /**
         * The version of the agent as stored runs know it: bumped whenever the instructions or the way the tools talk change, so a run
         * written by an older agent (or by the earlier code-driven chat, which has none) is not carried on, but a new one begins.
         */
        const val VERSION = "agent-4"

        /** The standing instructions; [language] is the form's language: every question and message is written in it. */
        fun instructions(language: String): String = """
            You help the user fill in a paper form. You work only through the functions below: every reply is exactly one function call.
            The form is in $language: write every question in $language, not English (another language only if the user writes in it). Chips are names or options, never ids.
            - First read_form, then list_people. Find out who the form is for (ask_user, the people's names as chips, if unclear), then fill_from_profile for each role with the person who has it.
            - Ask about open fields one question at a time with ask_user (printed options as chips). Fill each answer with fill_field: source user = the user's own words, source option = a printed option. Never invent a value; take stored details only from the tools' results (a ***token unchanged).
            - ask_user's result is the user's answer: use it, never ask again. Every result ends with the state, a "suggested next" step (follow it unless you know better) and "tools_now": the only functions you may call now.
            - After the user gives a detail a later form could use, ask (yes and no chips) whether to remember it for that person; remember_detail only after yes.
            - If the user will not answer a field, call skip_field. If they ask about the form, answer briefly with ask_user.
            - When nothing is open, call show_fill_card, then finish.
            Example (invented pool-pass form, user's answers in brackets; <...> stands for your own words, always in $language):
            read_form() list_people() ask_user(question="<who is the pass for, in $language>", chips=["Me","Lena"]) [Lena]
            fill_from_profile(person_id=p2, role=subject) ask_user(question="<which shoe size, in $language>") [38]
            fill_field(field_id=f4, value=38, source=user) show_fill_card() finish(summary="<done, only the signature is left, in $language>")
        """.trimIndent()
    }
}

/**
 * The agent's conversation read from, and written to, the document's chat messages: the run begins at the newest beta-notice line,
 * what the user wrote is its USER messages, and each step is a stored tool call and result. So a run that was stopped, or whose
 * process died, resumes from exactly what is stored.
 */
class FormAgentTranscript(
    private val documentId: String,
    private val log: FormChatLog,
    private val newId: () -> String = UuidGenerator::generate,
) : AgentTranscript {

    override suspend fun entries(): List<AgentEntry> {
        val messages = log.messages(documentId)
        // The newest run start decides: a run of another agent version (or of the earlier code-driven chat) is history, never context.
        val start = messages.indexOfLast(::isRunStart)
        if (start < 0 || !isCurrentRun(messages[start])) return emptyList()
        val entries = ArrayList<AgentEntry>()
        entries += AgentEntry.UserText(START_INSTRUCTION, isStart = true)
        for (message in messages.drop(start + 1)) {
            val callId = message.toolCallId
            when {
                message.role == MessageRole.USER -> entries += AgentEntry.UserText(message.content)
                message.role == MessageRole.TOOL_CALL && callId != null ->
                    entries += AgentEntry.Call(callId, message.toolName.orEmpty(), jsonOf(message.toolArgs))
                message.role == MessageRole.TOOL_RESULT && callId != null ->
                    entries += AgentEntry.Result(callId, message.toolName.orEmpty(), ToolResult.fromJson(jsonOf(message.toolResult)))
            }
        }
        return entries
    }

    override suspend fun record(call: AgentEntry.Call, result: AgentEntry.Result?) {
        val conversation = log.ensureConversation(documentId)
        // What the chat shows for the call is its content: the question of ask_user, the closing message of finish.
        val shown = when (call.name) {
            AskUserTool.NAME -> call.args.string("question")
            FinishTool.NAME -> call.args.string("summary")
            else -> null
        }.orEmpty()
        log.add(
            AiMessage(
                id = newId(), conversationId = conversation, role = MessageRole.TOOL_CALL, content = shown,
                toolCallId = call.id, toolName = call.name, toolArgs = call.args.toString(), createdAt = 0L,
            ),
        )
        if (result != null) {
            log.add(
                AiMessage(
                    id = newId(), conversationId = conversation, role = MessageRole.TOOL_RESULT, content = "",
                    toolCallId = call.id, toolName = call.name, toolResult = result.result.toModelText(), createdAt = 0L,
                ),
            )
        }
    }

    private fun jsonOf(text: String?): JsonObject =
        text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    companion object {
        /** What the agent is told when a run begins without the user typing a request (the card's "Help me fill it"). */
        const val START_INSTRUCTION = "Help me fill in this form."

        /** A new run begins with the beta-notice line (it is also what the user sees first). */
        fun isRunStart(message: AiMessage): Boolean =
            FormMessageCodec.parse(message)?.let { it.kind == FormMessageKind.STATUS && it.text == FormText.BETA_NOTICE } == true

        /** Whether the run that [start] opens was written by this agent version (it stores [FormAgentSpec.VERSION] in the line's arguments). */
        fun isCurrentRun(start: AiMessage): Boolean = FormMessageCodec.parse(start)?.args?.firstOrNull() == FormAgentSpec.VERSION

        /** Whether the newest run of [messages] is one of this agent version; false when there is no run, or the newest is an older one. */
        fun hasCurrentRun(messages: List<AiMessage>): Boolean = messages.lastOrNull(::isRunStart)?.let(::isCurrentRun) == true
    }
}
