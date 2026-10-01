package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.AgentSpec
import com.postsaimanager.core.domain.agent.AgentTranscript
import com.postsaimanager.core.domain.agent.ToolRegistry
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.string
import com.postsaimanager.core.domain.form.fill.FillProgress
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * The form agent as the loop sees it: what the model is told once (the instructions, with the language to speak), the compact
 * summary of where the fill stands (read from the stored fill, so it is the same after a restart) and the nudge after two
 * failed steps. There is no question order and no wording in here: the instructions tell the model what the tools are for and
 * the model decides the rest.
 */
class FormAgentSpec(private val env: FormToolEnv, override val tools: ToolRegistry) : AgentSpec {

    override val conversationId: String = "agent-${env.documentId}"

    override suspend fun systemPrompt(): String = instructions(env.locale().getDisplayLanguage(Locale.ENGLISH))

    override suspend fun stateSummary(): String? {
        val fields = env.fields()
        if (fields.isEmpty()) return "The form has not been read yet: call read_form."
        val people = env.managed()
        val roles = env.fill()?.roleProfiles.orEmpty().mapNotNull { (role, id) ->
            people.firstOrNull { it.id == id }?.let { "${role.name.lowercase()}=${it.name} (${FormRefs.personAlias(people, it)})" }
        }
        val progress = FillProgress.of(fields)
        val open = FormRefs.open(fields)
        return buildString {
            append(if (roles.isEmpty()) "No person is chosen yet." else "People: ${roles.joinToString("; ")}.")
            append(" Filled ${progress.ready} of ${progress.total}.")
            if (open.isEmpty()) {
                append(" Nothing is open.")
            } else {
                append(" Open: ")
                append(open.take(MAX_OPEN_SHOWN).joinToString("; ") { "${FormRefs.fieldAlias(fields, it)} ${it.labelText}" })
                if (open.size > MAX_OPEN_SHOWN) append("; +${open.size - MAX_OPEN_SHOWN} more")
                append(".")
            }
        }
    }

    override suspend fun stuckHint(): String? {
        val fields = env.fields()
        val next = FormRefs.open(fields).firstOrNull() ?: return "Nothing is open: call show_fill_card and then finish."
        return "Next open field: ${FormRefs.fieldAlias(fields, next)} \"${next.labelText}\" (${next.kind.name.lowercase()}). " +
            "Ask the user about it with ask_user, or leave it with skip_field."
    }

    companion object {
        private const val MAX_OPEN_SHOWN = 8

        /** The standing instructions; [language] is the form's language, spoken until the user writes in another. */
        fun instructions(language: String): String = """
            You help the user fill in a paper form on their phone, together with them. You work only through the functions below: every reply is exactly one function call.
            - First call read_form, then list_people.
            - Find out who the form is for (ask_user with the people's names as chips if it is unclear). Then call fill_from_profile for each role of the form, with the person who has that role.
            - Ask about the fields still open, one question at a time, with ask_user (printed options as chips). Fill each answer with fill_field: source user with the user's own words, or source option with a printed option. Never invent a value. Copy stored details only from the tools' results; a ***token is passed unchanged.
            - After the user gives a detail a later form could use, ask whether to remember it for that person (ask_user with a yes chip and a no chip). Call remember_detail only after they said yes.
            - If the user does not want to answer a field, call skip_field. If they ask about the form, answer briefly with ask_user.
            - When nothing is open, call show_fill_card, then finish.
            Write in the language the user writes in; until they write, use $language. Keep it short.
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
        val start = messages.indexOfLast(::isRunStart)
        if (start < 0) return emptyList()
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
    }
}
