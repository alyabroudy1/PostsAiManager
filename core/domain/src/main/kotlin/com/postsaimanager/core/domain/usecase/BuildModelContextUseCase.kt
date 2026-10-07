package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.memory.DocumentMemoryFormat
import com.postsaimanager.core.domain.memory.ObserveDocumentMemoryUseCase
import com.postsaimanager.core.model.AiMessage
import kotlinx.coroutines.flow.first
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
 *  3. the document memory: durable notes about this document ([ObserveDocumentMemoryUseCase]), a slot capped at
 *     [MEMORY_CAP_CHARS]; read each time a plan is built, so a rebuild after a session's notes were written has them;
 *  4. the continuity tail ([ContinuityTail]): only the last exchange.
 *
 * Older turns are never replayed, so the size of a rebuilt prefix is about the same for a chat of three messages and one of three
 * hundred. A live session is not built here: its conversation keeps growing inside the engine until the engine compacts it.
 */
class BuildModelContextUseCase internal constructor(
    private val buildChatContext: BuildChatContextUseCase,
    /** The document's notes, already formatted ([ObserveDocumentMemoryUseCase]); read once per plan. */
    private val memoryOf: suspend (documentId: String) -> String,
) {

    @Inject
    constructor(
        buildChatContext: BuildChatContextUseCase,
        observeDocumentMemory: ObserveDocumentMemoryUseCase,
    ) : this(buildChatContext, { documentId -> observeDocumentMemory(documentId).first() })

    /** A builder without a memory (tests, and callers that never have notes). */
    constructor(buildChatContext: BuildChatContextUseCase) : this(buildChatContext, { "" })

    /**
     * @param transcript the stored turns that PRECEDE the message about to be sent.
     * @param historyTokens the window the tail is budgeted against (the context minus what the tools need).
     * @param systemPrompt replaces the card (a caller with its own prompt); the tail is still added.
     *
     * The document memory comes from the notes of [documentId] at this moment (so a plan built after a session's notes were written
     * has them). A chat without a document (all documents) has none yet.
     */
    suspend operator fun invoke(
        documentId: String?,
        contextTokens: Int,
        historyTokens: Int,
        transcript: List<AiMessage>,
        systemPrompt: String? = null,
    ): ChatContextPlan {
        val memory = if (systemPrompt == null && documentId != null) memorySection(memoryOf(documentId)) else ""
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
        /** The most the notes add to a prompt: the one cap of the document memory, shared with what the user sees (about 270 tokens). */
        const val MEMORY_CAP_CHARS = DocumentMemoryFormat.MAX_CHARS

        /** The heading of the slot, starting with a blank line. */
        internal const val MEMORY_HEADER = "\n## What you remember about this document\n"

        /**
         * The section of the formatted notes ([DocumentMemoryFormat]: one `- note` line each), cut at a whole line under
         * [MEMORY_CAP_CHARS] (the heading not counted); empty for no notes.
         */
        internal fun memorySection(notes: String): String {
            val body = StringBuilder()
            for (line in notes.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                if (body.length + line.length + 1 > MEMORY_CAP_CHARS) break
                body.append(line).append('\n')
            }
            return if (body.isEmpty()) "" else MEMORY_HEADER + body
        }
    }
}
