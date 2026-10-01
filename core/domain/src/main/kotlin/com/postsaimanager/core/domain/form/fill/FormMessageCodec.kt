package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.FORM_MESSAGE_TOOL
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.MessageRole
import kotlinx.serialization.json.Json

/**
 * The one owner of how a [FormMessage] is stored as an [AiMessage] and read back: role TOOL_RESULT (so it is never part of the
 * model's history), tool name `form`, the payload as JSON in the tool arguments, the question the model wrote (if any) as the content.
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

    /** The form payload of [message], or null for an ordinary message (or one that cannot be read). */
    fun parse(message: AiMessage): FormMessage? {
        if (message.toolName != FORM_MESSAGE_TOOL) return null
        val payload = message.toolArgs ?: return null
        return runCatching { json.decodeFromString(FormMessage.serializer(), payload) }.getOrNull()
    }
}
