package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.model.AiMessage
import javax.inject.Inject

/**
 * What the model reads when a conversation is (re)built, in layers. The transcript the person sees is NOT it.
 *
 * @param card the document card and the instructions around it ([ChatGrounding]): what the reading found (category, sender, people,
 *   dates and amounts with their meanings, actions and their state), the document text or its retrieval marker, and the document
 *   memory section when there is one. [ChatGrounding.text] is the system prompt the engine is primed with.
 * @param documentMemoryChars how many characters of [card] are the document memory (a part of the card's text, kept apart for the
 *   budget log).
 * @param tail the last exchange, as history for the engine; empty for a new chat.
 * @param tailMessages the stored messages [tail] was made from (the UI marks where the model's context starts with them).
 */
data class ChatContextPlan(
    val card: ChatGrounding,
    val documentMemoryChars: Int,
    val tail: List<AiChatMessage>,
    val tailMessages: List<AiMessage>,
) {
    /** The characters of the system prompt without the memory. */
    val cardChars: Int get() = card.text.length - documentMemoryChars

    /** The characters the tail adds to a prompt (text and tool calls), as the history budget counts them. */
    val tailChars: Int get() = tail.sumOf { it.content.length + it.toolTrace.sumOf { call -> call.promptChars } }

    /** What a rebuilt conversation starts with, besides the skills the engine adds (logged as [describe] says). */
    val totalChars: Int get() = card.text.length + tailChars

    /** One line for the timing log: the size of each layer. */
    fun describe(): String =
        "context plan: card=$cardChars chars, memory=$documentMemoryChars chars, tail=$tailChars chars (${tail.size} messages), total=$totalChars chars, retrieval=${card.retrievalMode}"
}

/**
 * The one owner of "what the model reads" when a conversation is built from what is stored: the chat opening (its warm-up), a rebuild
 * after a new day, a fallback from the GPU, a process restart or a model reload, and a new session after the person left the chat or
 * was idle. The layers (plan 16):
 *
 *  1. the instructions: the system prompt and the skills (the engine adds the skill list to the card's prompt; unchanged here);
 *  2. the document card ([BuildChatContextUseCase], which uses [LetterReadingContext]): reused, not rebuilt;
 *  3. the document memory: durable notes about this document, a slot with its own cap, filled by a later phase (empty now);
 *  4. the continuity tail ([ContinuityTail]): only the last exchange.
 *
 * Older turns are never replayed, so the size of a rebuilt prefix is about the same for a chat of three messages and one of three
 * hundred. A live session is not built here: its conversation keeps growing inside the engine until the engine compacts it.
 */
class BuildModelContextUseCase @Inject constructor(
    private val buildChatContext: BuildChatContextUseCase,
) {

    /**
     * @param transcript the stored turns that PRECEDE the message about to be sent.
     * @param historyTokens the window the tail is budgeted against (the context minus what the tools need).
     * @param systemPrompt replaces the card (a caller with its own prompt); the tail is still added.
     * @param documentMemory the notes of the document memory, newest last; capped to [MEMORY_CAP_CHARS].
     */
    suspend operator fun invoke(
        documentId: String?,
        contextTokens: Int,
        historyTokens: Int,
        transcript: List<AiMessage>,
        systemPrompt: String? = null,
        documentMemory: List<String> = emptyList(),
    ): ChatContextPlan {
        val memory = if (systemPrompt == null && documentId != null) memorySection(documentMemory) else ""
        val card = systemPrompt?.let { ChatGrounding(it, retrievalMode = false) }
            ?: buildChatContext(documentId, contextTokens, documentMemory = memory)
        val totalBudgetChars = (
            (historyTokens - BuildChatContextUseCase.DEFAULT_REPLY_RESERVE - BuildChatContextUseCase.TEMPLATE_OVERHEAD_TOKENS)
                .coerceAtLeast(BuildChatContextUseCase.MIN_CONTEXT_TOKENS)
            ) * BuildChatContextUseCase.CHARS_PER_TOKEN
        val budgetChars = (totalBudgetChars - card.text.length).coerceAtLeast(0)
        val tailMessages = ContinuityTail.select(transcript)
        val tail = ContinuityTail.history(transcript, budgetChars)
        return ChatContextPlan(card, memory.length, tail, if (tail.isEmpty()) emptyList() else tailMessages)
    }

    companion object {
        /** The most the document memory adds to a prompt, whatever the notes are (about 200 tokens). */
        const val MEMORY_CAP_CHARS = 600

        /** The section of the notes, starting with a blank line, cut at a whole note under [MEMORY_CAP_CHARS]; empty for no notes. */
        internal fun memorySection(notes: List<String>): String {
            val clean = notes.map { it.trim() }.filter { it.isNotEmpty() }
            if (clean.isEmpty()) return ""
            val header = "\n## What you remember about this document\n"
            val body = StringBuilder()
            for (note in clean) {
                val line = "- $note\n"
                if (header.length + body.length + line.length > MEMORY_CAP_CHARS) break
                body.append(line)
            }
            return if (body.isEmpty()) "" else header + body
        }
    }
}
