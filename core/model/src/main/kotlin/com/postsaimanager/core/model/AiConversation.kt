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
 *
 * [content] is the answer — the only part of an assistant message that is ever fed back
 * into a future prompt (see `SendChatMessageUseCase`'s KDoc on "what is sent to the
 * model"). [thinking] is the model's own reasoning trace, parsed out of `<think>…</think>`
 * by `ThinkingStreamParser`; it is stored purely for display (a collapsible "Thought for
 * N s" section) and must never be read back into [content] or into a prompt. Both are null
 * for a user message and for any assistant reply from a model that never emits a thinking
 * block.
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
    /** The model's reasoning trace for this turn, if any. Display-only — see class doc. */
    val thinking: String? = null,
    /** Wall-clock time spent in the thinking phase, if any — powers "Thought for N s". */
    val thinkingDurationMs: Long? = null,
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
