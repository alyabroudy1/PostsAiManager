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
    /**
     * True for an assistant reply that was cut short — the user tapped Stop, or generation
     * crashed/errored mid-stream — rather than finishing normally. [content] is whatever was
     * produced up to that point, kept rather than discarded (modern chat UX), and the UI
     * renders a "Stopped" marker under it.
     *
     * **Never sent back to the model.** See `SendChatMessageUseCase.INCOMPLETE_REPLIES_ARE_NOT_SENT_TO_MODEL`
     * for the one place this exclusion is enforced, and its KDoc for why: the engine's own
     * chat-session KV cache had this reply's tokens rolled back (`AiEngine.discardPendingReply`)
     * precisely so the model never sees itself having said something it didn't finish saying;
     * replaying it into a rebuilt prompt (e.g. `primeChatSession` after a reload) would
     * silently reintroduce exactly that.
     */
    val incomplete: Boolean = false,
    /**
     * The passages, if any, that were injected into this turn's prompt and grounded the
     * answer (4.1/4.2 retrieval mode) — empty for a user message, for a reply generated
     * outside retrieval mode (the whole document already sat in the grounding), and for a
     * turn whose retrieval came back with nothing relevant.
     *
     * Persisted even for an [incomplete] reply: the passages were shown to the model before
     * generation was cut short, so they are still what grounded whatever text it produced —
     * see `SendChatMessageUseCase`'s KDoc on "Retrieval-augmented grounding" and
     * "Stopped / interrupted replies".
     *
     * Already filtered to "what to show": `SendChatMessageUseCase` decides, once generation
     * finishes, whether the answer actually cites specific passages (`CitationParser`) and
     * persists only those — falling back to every injected passage when the answer cites
     * none of them, or was never finished (an interrupted reply, where citation parsing
     * would be meaningless against a cut-off sentence).
     */
    val sources: List<MessageSource> = emptyList(),
)

/**
 * One passage cited or shown as grounding for an [AiMessage] — see [AiMessage.sources].
 *
 * Deliberately minimal: just enough to render a citation chip ("Page 2" or "<title>, p.2")
 * and navigate to it. The passage's own text is not duplicated here — [chunkId] is enough to
 * look it up again if a future feature needs the full excerpt, and re-fetching is cheap
 * (`DocumentChunkRepository` is a local Room table), so there is no reason to widen this
 * beyond what the UI actually needs.
 */
@Serializable
data class MessageSource(
    val documentId: String,
    /** Null for a passage indexed before page tracking (4.0) — see `StoredChunk.pageNumber`. */
    val pageNumber: Int?,
    val chunkId: String,
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
