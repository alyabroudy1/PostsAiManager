package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.MessageImages
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MediaType
import com.postsaimanager.core.model.MessageRole

/**
 * The only part of the transcript a rebuilt conversation shows the model: the LAST exchange (the user's last message and the
 * assistant's reply to it), so that a reopened chat still answers "and the second one?". Everything older stays in the transcript
 * the person sees and is not replayed: the model reads the document card (and, later, the document memory), not the old chat.
 *
 * Within a live session nothing here applies: the engine keeps every turn of the visit in its conversation. This is for the
 * moments a conversation is built again from the stored transcript (a chat opened, a new day, a restart of the engine, a new
 * session after leaving or 10 idle minutes).
 *
 * A reply the user stopped or that failed ([AiMessage.incomplete]) is never part of it, as before ([isEligibleForModel]); its tool
 * calls travel with it ([AiMessage.toolTrace]) and the engine replays them (a loaded skill's text as a one-line stub).
 *
 * Pure.
 */
object ContinuityTail {

    /** A message longer than this is cut when replayed, so the rebuilt prefix stays about the same size whatever was said. */
    const val MAX_MESSAGE_CHARS = 1_500

    /**
     * INCOMPLETE_REPLIES_ARE_NOT_SENT_TO_MODEL: a message the user stopped or that failed mid-stream is kept for display but never
     * fed back into a prompt. The engine's own cache already had its tokens rolled back ([com.postsaimanager.core.domain.ai.ChatEngine.discardPendingReply]).
     */
    fun isEligibleForModel(message: AiMessage): Boolean = !message.incomplete

    /**
     * The messages of the last exchange of [transcript] (oldest first): the newest finished assistant reply and the user message it
     * answers; empty when there is no such pair (a new chat, or only an unanswered question). The caller passes the turns that
     * PRECEDE the message about to be sent.
     */
    fun select(transcript: List<AiMessage>): List<AiMessage> {
        val eligible = transcript
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filter(::isEligibleForModel)
        val reply = eligible.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (reply < 0) return emptyList()
        val question = eligible.subList(0, reply).indexOfLast { it.role == MessageRole.USER }
        if (question < 0) return emptyList()
        return listOf(eligible[question], eligible[reply])
    }

    /**
     * How many messages of [transcript] sit above the tail: what the divider "Earlier messages" says is not replayed. Zero when
     * everything is in the tail (or there is none).
     */
    fun olderCount(transcript: List<AiMessage>): Int {
        val tail = select(transcript)
        val first = tail.firstOrNull() ?: return transcript.size
        return transcript.indexOfFirst { it.id == first.id }.coerceAtLeast(0)
    }

    /**
     * [select] as history for the engine. Dropped whole when it would not fit [budgetChars] (a conversation never starts with an
     * answer whose question is gone).
     */
    fun history(transcript: List<AiMessage>, budgetChars: Int): List<AiChatMessage> {
        val tail = select(transcript)
        if (tail.isEmpty()) return emptyList()
        if (tail.sumOf(::promptChars) > budgetChars) return emptyList()
        return tail.map { message ->
            AiChatMessage(
                role = if (message.role == MessageRole.USER) AiChatRole.USER else AiChatRole.ASSISTANT,
                content = withImageMarker(message).capped(),
                toolTrace = message.toolTrace,
            )
        }
    }

    /** Characters a replayed message adds to a prompt: its (capped) text and its tool calls. */
    fun promptChars(message: AiMessage): Int = message.content.take(MAX_MESSAGE_CHARS).length + message.toolTrace.sumOf { it.promptChars }

    /** A turn that had pictures is replayed with a text marker in their place (see [MessageImages.marker]). */
    private fun withImageMarker(message: AiMessage): String {
        if (message.role != MessageRole.USER || message.mediaType != MediaType.IMAGE) return message.content
        val count = MessageImages.decode(message.mediaPath).size
        return if (count == 0) message.content else "${MessageImages.marker(count)}\n${message.content}"
    }

    private fun String.capped(): String = if (length <= MAX_MESSAGE_CHARS) this else take(MAX_MESSAGE_CHARS) + "…"
}
