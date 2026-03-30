package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * A conversation with an AI model about a document.
 */
@Serializable
data class AiConversation(
    val id: String,
    val documentId: String?,
    val aiModelId: String?,
    val modelType: AiModelType,
    val title: String,
    val lastMessageAt: Long,
    val messageCount: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long,
)

/**
 * A single message in a conversation.
 */
@Serializable
data class AiMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val mediaType: MediaType = MediaType.TEXT,
    val mediaPath: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val isStreaming: Boolean = false,
    val createdAt: Long,
)

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL_CALL,
    TOOL_RESULT,
}

@Serializable
enum class MediaType {
    TEXT,
    IMAGE,
    MARKDOWN,
    DOCUMENT,
}

/**
 * Represents an AI tool call request.
 */
@Serializable
data class AiToolCall(
    val id: String,
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false,
)
