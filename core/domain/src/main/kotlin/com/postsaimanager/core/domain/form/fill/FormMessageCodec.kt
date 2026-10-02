package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.domain.agent.string
import com.postsaimanager.core.domain.agent.strings
import com.postsaimanager.core.domain.form.agent.AskUserTool
import com.postsaimanager.core.domain.form.agent.FinishTool
import com.postsaimanager.core.domain.form.agent.ShowFillCardTool
import com.postsaimanager.core.domain.form.agent.ShowOnPageTool
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.FORM_MESSAGE_TOOL
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The one owner of how the form conversation's messages are stored as [AiMessage]s and read back for the chat.
 *
 * - A status line (progress, a pause, an error) is a TOOL_RESULT with tool name `form` and a [FormMessage] as JSON in the tool arguments.
 * - A tool call of the agent is a TOOL_CALL with its `toolCallId`, tool name and arguments; its result a TOOL_RESULT with the same
 *   `toolCallId` and the result in `toolResult`. Only the calls that show something in the chat render as a [FormMessage]: the
 *   question of `ask_user` with its chips (the question is the message content), the card of `show_fill_card`, the page chip of
 *   `show_on_page` and the closing message of `finish`. Every other step is protocol, shown to nobody ([isAgentStep]).
 *
 * All of them have a role the plain chat never sends to the model, so none becomes history of a normal question.
 */
object FormMessageCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun toMessage(id: String, conversationId: String, createdAt: Long, form: FormMessage, content: String = ""): AiMessage = AiMessage(
        id = id,
        conversationId = conversationId,
        role = MessageRole.TOOL_RESULT,
        content = content,
        toolName = FORM_MESSAGE_TOOL,
        toolArgs = json.encodeToString(FormMessage.serializer(), form),
        createdAt = createdAt,
    )

    /** What the chat renders for [message]: a stored status line, or an agent call that shows something; null for any other message. */
    fun parse(message: AiMessage): FormMessage? {
        if (message.toolName == FORM_MESSAGE_TOOL) {
            val payload = message.toolArgs ?: return null
            return runCatching { json.decodeFromString(FormMessage.serializer(), payload) }.getOrNull()
        }
        if (message.role != MessageRole.TOOL_CALL || message.toolCallId == null) return null
        val args = argsOf(message)
        return when (message.toolName) {
            AskUserTool.NAME -> FormMessage(
                FormMessageKind.QUESTION,
                chips = args.strings("chips").orEmpty().map { FormChip(FormChipAction.ANSWER, label = it, arg = it) },
            )
            FinishTool.NAME -> FormMessage(FormMessageKind.QUESTION)
            ShowFillCardTool.NAME -> FormMessage(FormMessageKind.CARD)
            ShowOnPageTool.NAME -> FormMessage(FormMessageKind.PAGE, fieldId = args.string("field_id"))
            else -> null
        }
    }

    /** A stored step of the agent's protocol (a call or its result): the chat shows it only through [parse], never as a message. */
    fun isAgentStep(message: AiMessage): Boolean = message.toolCallId != null

    /**
     * What the chat renders of [messages], in order: every message that is not an agent step, and the steps that show something
     * ([parse]). A question or closing message the agent's tool rejected (it has a stored result, a turn-ending call that worked has none)
     * was never shown to the user, so it is left out.
     */
    fun rendered(messages: List<AiMessage>): List<AiMessage> {
        val rejected = messages.filter { it.role == MessageRole.TOOL_RESULT && it.toolCallId != null }.mapNotNull { it.toolCallId }.toSet()
        return messages.filter { message ->
            val shown = !isAgentStep(message) || parse(message) != null
            val turnEnding = message.role == MessageRole.TOOL_CALL && (message.toolName == AskUserTool.NAME || message.toolName == FinishTool.NAME)
            shown && !(turnEnding && message.toolCallId in rejected)
        }
    }

    private fun argsOf(message: AiMessage): JsonObject =
        message.toolArgs?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
}
