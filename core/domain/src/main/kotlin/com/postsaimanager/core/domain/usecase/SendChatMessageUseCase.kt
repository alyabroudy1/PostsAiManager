package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.StreamSegment
import com.postsaimanager.core.domain.ai.ThinkingStreamParser
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** Progress of one assistant turn, as the UI needs to render it. */
sealed interface ChatTurn {
    /** A model is being loaded — first message after app start pays this cost. */
    data object PreparingModel : ChatTurn

    /** An incremental chunk of the model's reasoning trace — never the answer. */
    data class ThinkingToken(val text: String) : ChatTurn

    /**
     * The thinking phase ended — either the model closed `</think>` and the answer is
     * starting, or generation finished with the stream still inside `<think>`.
     * [durationMs] is how long it ran.
     */
    data class ThinkingComplete(val durationMs: Long) : ChatTurn

    /** An incremental token of the answer. */
    data class Token(val text: String) : ChatTurn

    /** Finished; [message] is the persisted assistant message. */
    data class Complete(val message: AiMessage) : ChatTurn

    /** Something went wrong, phrased for a person, with an action where one exists. */
    data class Failed(val message: String, val action: ChatErrorAction?) : ChatTurn
}

enum class ChatErrorAction {
    /** Send the user to model management — nothing is installed. */
    INSTALL_MODEL,

    /** Transient; offer retry. */
    RETRY,
}

/**
 * Sends a user message and streams the assistant's reply.
 *
 * Orchestration lives here rather than in the ViewModel so the same behaviour is reachable
 * from anywhere — including, later, an `AiTool` (architecture: the LLM is a client of the
 * domain layer exactly as the UI is).
 *
 * Persistence brackets generation: the user's message is stored **before** the model runs,
 * so a crash mid-generation cannot lose what the user typed. Spike Q3 established that a
 * native abort kills the whole process, which makes that ordering load-bearing rather than
 * merely tidy.
 *
 * ### What is sent to the model
 *
 * The prompt for a turn is built from exactly three things, in this order: the grounding
 * system prompt ([BuildChatContextUseCase]), the prior turns of *this* conversation, and
 * the new user message. Prior turns are read straight off [AiMessage.content] —
 * [AiMessage.thinking] is never referenced here, anywhere else in [buildHistory], or
 * anywhere in this file. That is deliberate and is the single code path this rule is
 * enforced on: a reasoning model's `<think>…</think>` trace is display-only (a collapsible
 * "Thought for N s" block in the UI) and must never re-enter a future prompt — re-sending
 * it would silently balloon every later prompt with text the user never asked the model to
 * reconsider, and models were never trained to receive their own past reasoning as input.
 * See `ThinkingStreamParser` for where thinking is split out of the raw stream in the first
 * place, and `SendChatMessageUseCaseTest`'s "thinking is never sent back to the model" for
 * the test that pins this.
 */
class SendChatMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val engine: AiEngine,
    private val activeModelProvider: ActiveModelProvider,
    private val buildChatContext: BuildChatContextUseCase,
) {

    operator fun invoke(
        conversationId: String,
        documentId: String?,
        text: String,
        systemPrompt: String? = null,
        thinkingEnabled: Boolean = true,
    ): Flow<ChatTurn> = flow {
        val now = System.currentTimeMillis()

        // Ensure a conversation exists before anything can reference it.
        if (conversationRepository.getConversationById(conversationId) is PamResult.Error) {
            conversationRepository.createConversation(
                AiConversation(
                    id = conversationId,
                    documentId = documentId,
                    aiModelId = null,
                    modelType = AiModelType.LOCAL,
                    title = text.take(CONVERSATION_TITLE_LENGTH),
                    lastMessageAt = now,
                    createdAt = now,
                ),
            )
        }

        // Read before the new user message is appended, so it is exactly the turns that
        // precede this one — see the class KDoc on "what is sent to the model".
        val priorTurns = conversationRepository.getMessages(conversationId).first()

        // Persist the user's message first — see the class note on ordering.
        val userMessage = AiMessage(
            id = UuidGenerator.generate(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = text,
            createdAt = now,
        )
        conversationRepository.addMessage(userMessage)

        // Always reconcile against the active path AND config, on every send — not just
        // when the engine reports not-ready or a different model path. The user may have
        // switched the active model, or only changed a setting (accelerator, threads,
        // context) in the chat header sheet, since the last message; either way the engine
        // may still be Ready on stale config. `engine.load` is cheap to call even when
        // nothing changed at all: `ModelLoadCoordinator` dispatches a same-model,
        // same-config request to `ReloadScope.NONE`, a genuine no-op. This also keeps
        // `ModelLoadCoordinator.lastRequested` current, so crash recovery via
        // `ensureLoaded()` replays the config the user actually asked for rather than a
        // stale one — see the coordinator's docs on `lastRequested`.
        val activeModelPath = activeModelProvider.activeModelPath()
        if (activeModelPath == null) {
            emit(
                ChatTurn.Failed(
                    "No AI model is installed yet. Install one to chat about your documents.",
                    ChatErrorAction.INSTALL_MODEL,
                ),
            )
            return@flow
        }

        emit(ChatTurn.PreparingModel)
        val loaded = engine.load(activeModelPath, activeModelProvider.activeModelConfig())
        if (loaded is PamResult.Error) {
            emit(ChatTurn.Failed(loaded.error.userMessage, ChatErrorAction.RETRY))
            return@flow
        }

        // Ground the conversation in the document. Built *after* the model is loaded so
        // the real context window is known — budgeting against a guess would either waste
        // capacity or overflow it.
        val contextTokens = when (val state = engine.state.value) {
            is ModelLoadState.Ready -> state.config.contextTokens
            else -> AiEngine.DEFAULT_CONTEXT_TOKENS
        }
        val grounding = systemPrompt ?: buildChatContext(documentId, contextTokens)

        // The engine's chat session IS the conversation from here on — its KV cache holds
        // the decoded history, so only the new user turn below gets tokenised and decoded
        // (see AiEngine.ensureChatSession's KDoc and documentation/02-architecture.md §5.3).
        // Cheap to call on every send, same as `engine.load` above: a no-op when this
        // conversation's session is already primed and valid.
        engine.ensureChatSession(conversationId, grounding, buildHistory(priorTurns))

        val parser = ThinkingStreamParser()
        val thinkingBuilder = StringBuilder()
        val answerBuilder = StringBuilder()
        var thinkingStartNanos: Long? = null
        var thinkingDurationMs: Long? = null

        // Pure — appends to the builders and returns the ChatTurns those segments imply,
        // without emitting. Kept separate from emission so the cancellation path below can
        // flush the parser and finalise the persisted text *without* trying to emit from a
        // flow whose collector has already been cancelled.
        fun apply(segments: List<StreamSegment>): List<ChatTurn> {
            val turns = mutableListOf<ChatTurn>()
            for (segment in segments) {
                when (segment) {
                    is StreamSegment.Thinking -> {
                        if (thinkingStartNanos == null) thinkingStartNanos = System.nanoTime()
                        thinkingBuilder.append(segment.delta)
                        turns += ChatTurn.ThinkingToken(segment.delta)
                    }
                    is StreamSegment.Answer -> {
                        val start = thinkingStartNanos
                        if (start != null && thinkingDurationMs == null) {
                            thinkingDurationMs = elapsedMs(start)
                            turns += ChatTurn.ThinkingComplete(thinkingDurationMs!!)
                        }
                        answerBuilder.append(segment.delta)
                        turns += ChatTurn.Token(segment.delta)
                    }
                }
            }
            return turns
        }

        try {
            // `prompt` is unused here — sendChatMessage renders the turn itself from the
            // session's own history plus `text`; only the sampling/thinking fields matter.
            engine.sendChatMessage(text, AiRequest(prompt = "", thinkingEnabled = thinkingEnabled))
                .collect { token -> apply(parser.consume(token)).forEach { emit(it) } }
            apply(parser.finish()).forEach { emit(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The user stopped generation. Whatever was produced is still worth keeping —
            // but the collector is gone, so only finalise state, never emit.
            apply(parser.finish())
            thinkingStartNanos?.let { start ->
                if (thinkingDurationMs == null) thinkingDurationMs = elapsedMs(start)
            }
            val assistant = persistAssistant(
                conversationId,
                answerBuilder.toString(),
                thinkingBuilder.toString(),
                thinkingDurationMs,
            )
            engine.commitChatReply(assistant.content)
            throw e
        } catch (e: Exception) {
            emit(ChatTurn.Failed(e.message ?: "Generation failed.", ChatErrorAction.RETRY))
            return@flow
        }

        thinkingStartNanos?.let { start ->
            if (thinkingDurationMs == null) thinkingDurationMs = elapsedMs(start)
        }

        val assistant = persistAssistant(
            conversationId,
            answerBuilder.toString(),
            thinkingBuilder.toString(),
            thinkingDurationMs,
        )
        // Records the (thinking-stripped) reply in the session's own history so the next
        // turn's diff renders correctly — see AiEngine.commitChatReply's KDoc. Uses
        // `assistant.content` rather than `answerBuilder` directly so the session's record
        // and what got persisted (and is shown as history next time) are always the same
        // string, including the NO_ANSWER_PRODUCED fallback below.
        engine.commitChatReply(assistant.content)
        emit(ChatTurn.Complete(assistant))
    }

    /**
     * Prior turns of this conversation, as history the model can see — user text and
     * assistant *answers* only. See the class KDoc: [AiMessage.thinking] is intentionally
     * never touched here.
     */
    private fun buildHistory(priorTurns: List<AiMessage>): List<AiChatMessage> =
        priorTurns
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(MAX_HISTORY_TURNS)
            .map { message ->
                AiChatMessage(
                    role = if (message.role == MessageRole.USER) AiChatRole.USER else AiChatRole.ASSISTANT,
                    content = message.content,
                )
            }

    private suspend fun persistAssistant(
        conversationId: String,
        content: String,
        thinking: String,
        thinkingDurationMs: Long?,
    ): AiMessage {
        val message = AiMessage(
            id = UuidGenerator.generate(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = if (content.isBlank() && thinking.isNotBlank()) NO_ANSWER_PRODUCED else content,
            createdAt = System.currentTimeMillis(),
            thinking = thinking.ifBlank { null },
            thinkingDurationMs = thinkingDurationMs,
        )
        conversationRepository.addMessage(message)
        return message
    }

    private fun elapsedMs(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / NANOS_PER_MILLI

    private companion object {
        const val CONVERSATION_TITLE_LENGTH = 60

        /**
         * Caps how many prior turns are replayed into the prompt. Unbounded history would
         * eventually starve the context window `BuildChatContextUseCase` budgets against —
         * this is a coarse, cheap guard ahead of any real token-aware trimming.
         */
        const val MAX_HISTORY_TURNS = 20
        const val NANOS_PER_MILLI = 1_000_000L
        const val NO_ANSWER_PRODUCED =
            "The model finished thinking but did not produce an answer. You can try again."
    }
}
