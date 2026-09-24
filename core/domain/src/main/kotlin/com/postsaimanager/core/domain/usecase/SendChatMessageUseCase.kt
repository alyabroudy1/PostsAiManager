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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Progress of one assistant turn, as the UI needs to render it. */
sealed interface ChatTurn {
    /**
     * A model is being loaded — first message after app start (or a model/config switch)
     * pays this cost.
     *
     * @param reason overrides the default "Loading model…" caption for a more specific one —
     *   today, only "waiting behind an in-flight document read" (see [AiEngine.isBusy]). Null
     *   is the common case and renders the default text.
     */
    data class PreparingModel(val reason: String? = null) : ChatTurn

    /**
     * The engine's chat session for this conversation is about to be (re)primed — a full
     * decode of the grounding prompt plus history, not the fast per-turn diff. Emitted only
     * when [AiEngine.isChatSessionPrimed] says a re-prime will actually happen (3.4/3.5), so
     * it never shows on the common case of a subsequent send in an already-primed
     * conversation.
     */
    data object PreparingConversation : ChatTurn

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
 *
 * ### Stopped / interrupted replies
 *
 * A reply the user stops, or one that fails mid-stream, is still persisted — with whatever
 * text was produced — but marked [AiMessage.incomplete]. [isEligibleForModel]
 * is the one place that exclusion is enforced: [buildHistory] drops such messages before they
 * ever become part of a prompt, whether replayed fresh into a reloaded engine session
 * ([ensureChatSession]) or read here on the next send. The engine's own chat-session KV cache
 * is kept in sync on the same event: [AiEngine.discardPendingReply] is called instead of
 * [AiEngine.commitChatReply] for an incomplete reply, rolling back the reply's sampled
 * tokens so the session's cache and `chatHistory` never disagree with what got persisted.
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

        val config = activeModelProvider.activeModelConfig()

        // 3.5: skip rebuilding the grounding prompt when the session is already primed for
        // this conversation. Safe because of the guarantee documented on AiEngine — "One
        // native context, two callers" §2: ANY reload/unload/one-shot generate() that could
        // change what grounding should say (a different model, context window, accelerator…)
        // clears the tracked session first, so `isChatSessionPrimed` staying true is proof
        // nothing that would change the document/config this grounding was built for has
        // happened since. Read *before* `engine.load` below purely as a hint for whether to
        // bother starting the concurrent build (3.1) at all — re-checked after load actually
        // runs, below, since that is the one call that could invalidate it.
        val primedBeforeLoad = engine.isChatSessionPrimed(conversationId)

        emit(ChatTurn.PreparingModel(reason = if (engine.isBusy) BUSY_REASON else null))

        // 3.1: overlaps `buildChatContext` (a handful of DB reads) with `engine.load` (the
        // slow part — can mean a multi-second cold model load, or now, also, waiting behind
        // an in-flight generate() on the shared engine, see AiEngine.isBusy) instead of
        // waiting for the load to finish first. Skipped when the session already looks primed
        // (3.5) — grounding built here would go unused in the common case, since
        // `ensureChatSession` below no-ops without ever reading it.
        val (loaded, groundingIfBuilt) = coroutineScope {
            val groundingDeferred = if (primedBeforeLoad) {
                null
            } else {
                async { systemPrompt ?: buildChatContext(documentId, config.contextTokens) }
            }
            val loadResult = try {
                engine.load(activeModelPath, config)
            } catch (e: Throwable) {
                // Cancel both on failure — an exception from `load` (as opposed to the
                // `PamResult.Error` value it normally returns) means this whole turn is being
                // torn down, and the grounding build should not keep running orphaned.
                groundingDeferred?.cancel()
                throw e
            }
            if (loadResult is PamResult.Error) {
                groundingDeferred?.cancel()
                loadResult to null
            } else {
                loadResult to groundingDeferred?.await()
            }
        }
        if (loaded is PamResult.Error) {
            emit(ChatTurn.Failed(loaded.error.userMessage, ChatErrorAction.RETRY))
            return@flow
        }

        // Re-checked after `load`: it may have triggered a reload the pre-load snapshot above
        // could not have anticipated (a config changed since the last send), which would have
        // invalidated the very session `primedBeforeLoad` reported as fine. This is the actual
        // ground truth for whether `ensureChatSession` below is about to re-prime.
        val needsPriming = !engine.isChatSessionPrimed(conversationId)
        val contextTokens = when (val state = engine.state.value) {
            is ModelLoadState.Ready -> state.config.contextTokens
            else -> config.contextTokens
        }
        // Falls back to building grounding here — losing the 3.1 overlap for this one turn —
        // only in the rare case load() invalidated a session this call believed, before load,
        // was still good. Correctness over the overlap in that edge case.
        val grounding = groundingIfBuilt
            ?: if (needsPriming) (systemPrompt ?: buildChatContext(documentId, contextTokens)) else ""

        if (needsPriming) {
            // 3.4: without this, re-priming a long conversation (a full decode of the
            // grounding + history, not the fast per-turn diff) looks like a frozen screen —
            // see documentation/02-architecture.md §5.3's 41.4 s cold-prime measurement.
            emit(ChatTurn.PreparingConversation)
        }

        // The engine's chat session IS the conversation from here on — its KV cache holds
        // the decoded history, so only the new user turn below gets tokenised and decoded
        // (see AiEngine.ensureChatSession's KDoc and documentation/02-architecture.md §5.3).
        // Cheap to call on every send, same as `engine.load` above: a no-op when this
        // conversation's session is already primed and valid.
        engine.ensureChatSession(conversationId, grounding, buildHistory(priorTurns, contextTokens, grounding))

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
            // but the collector is gone (and this coroutine is itself already cancelled),
            // so persistence runs under NonCancellable: without it, the repository's
            // suspend insert can itself be torn down mid-write, racing the cancellation
            // and silently dropping the partial reply instead of persisting it.
            withContext(NonCancellable) {
                apply(parser.finish())
                thinkingStartNanos?.let { start ->
                    if (thinkingDurationMs == null) thinkingDurationMs = elapsedMs(start)
                }
                persistAssistant(
                    conversationId,
                    answerBuilder.toString(),
                    thinkingBuilder.toString(),
                    thinkingDurationMs,
                    incomplete = true,
                )
                // Not commitChatReply(): the reply was never finished, so it must not be
                // recorded as something the model actually said — see the class KDoc on
                // "Stopped / interrupted replies". Rolls the session's KV cache back to
                // right before this reply's tokens instead.
                engine.discardPendingReply()
            }
            throw e
        } catch (e: Exception) {
            // Same reasoning as the cancellation path above: an error mid-stream (a native
            // crash, a binder death) leaves exactly the same half-formed reply and the same
            // stale pending-turn tokens in the session's KV cache, so it is finalised the
            // same way rather than being silently discarded.
            withContext(NonCancellable) {
                apply(parser.finish())
                thinkingStartNanos?.let { start ->
                    if (thinkingDurationMs == null) thinkingDurationMs = elapsedMs(start)
                }
                if (answerBuilder.isNotBlank() || thinkingBuilder.isNotBlank()) {
                    persistAssistant(
                        conversationId,
                        answerBuilder.toString(),
                        thinkingBuilder.toString(),
                        thinkingDurationMs,
                        incomplete = true,
                    )
                }
                engine.discardPendingReply()
            }
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
     *
     * [AiMessage.incomplete] turns are dropped here too — see
     * [isEligibleForModel]. This covers the "replay history" path
     * ([ensureChatSession]'s `history` parameter, via `primeChatSession`); the live-session
     * path is covered separately by [AiEngine.discardPendingReply], called instead of
     * [AiEngine.commitChatReply] for the same messages when they were first produced.
     *
     * ### Token budget (3.3)
     *
     * [MAX_HISTORY_TURNS] alone is a coarse, turn-count cap — a long-winded 20-turn
     * conversation can still overflow the context window once [grounding] and the reply
     * reserve are accounted for. After that cap, history is additionally trimmed by estimated
     * *tokens*, using the same chars-per-token heuristic [BuildChatContextUseCase] budgets
     * grounding against (reused, not re-derived, so the two halves of one prompt can never
     * silently disagree about how many characters a token costs).
     *
     * Trimming drops the *oldest* eligible turns first — same rule [MAX_HISTORY_TURNS]
     * already follows — and, when the budget is exceeded, cuts down to
     * [HISTORY_TRIM_TARGET_RATIO] of it rather than to exactly the limit. The KV cache reason:
     * the standing chat session's cache holds exactly the history last primed with, so trimming
     * to the very edge of the budget would make the next turn (one message longer) overflow
     * again and force another re-prime — a conversation hovering near the limit would re-prime
     * on every single turn instead of settling into the fast per-turn diff path. Cutting
     * further leaves headroom for several more turns before the next re-prime.
     *
     * @param contextTokens the window this turn is budgeting against — see
     *   [SendChatMessageUseCase.invoke] on why this is read *after* [engine.load][AiEngine.load]
     *   rather than guessed.
     * @param grounding the system prompt this history will sit alongside, so its token cost is
     *   subtracted from the budget rather than double-spent.
     */
    private fun buildHistory(
        priorTurns: List<AiMessage>,
        contextTokens: Int,
        grounding: String,
    ): List<AiChatMessage> {
        val eligible = priorTurns
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filter(::isEligibleForModel)
            .takeLast(MAX_HISTORY_TURNS)

        if (eligible.isEmpty()) return emptyList()

        val totalBudgetChars = (
            (contextTokens - BuildChatContextUseCase.DEFAULT_REPLY_RESERVE - BuildChatContextUseCase.TEMPLATE_OVERHEAD_TOKENS)
                .coerceAtLeast(BuildChatContextUseCase.MIN_CONTEXT_TOKENS)
            ) * BuildChatContextUseCase.CHARS_PER_TOKEN
        val historyBudgetChars = (totalBudgetChars - grounding.length).coerceAtLeast(0)

        var totalChars = eligible.sumOf { it.content.length }
        val trimmed = if (totalChars <= historyBudgetChars) {
            eligible
        } else {
            // Drop oldest-first down to HISTORY_TRIM_TARGET_RATIO of budget, not just under
            // it — see the class doc above on why a smaller, stabler cut avoids re-priming
            // every turn once a conversation is hovering near the limit.
            val targetChars = (historyBudgetChars * HISTORY_TRIM_TARGET_RATIO).toInt()
            val kept = eligible.toMutableList()
            while (kept.size > 1 && totalChars > targetChars) {
                totalChars -= kept.removeAt(0).content.length
            }
            kept
        }

        return trimmed.map { message ->
            AiChatMessage(
                role = if (message.role == MessageRole.USER) AiChatRole.USER else AiChatRole.ASSISTANT,
                content = message.content,
            )
        }
    }

    private suspend fun persistAssistant(
        conversationId: String,
        content: String,
        thinking: String,
        thinkingDurationMs: Long?,
        incomplete: Boolean = false,
    ): AiMessage {
        val message = AiMessage(
            id = UuidGenerator.generate(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = if (content.isBlank() && thinking.isNotBlank()) NO_ANSWER_PRODUCED else content,
            createdAt = System.currentTimeMillis(),
            thinking = thinking.ifBlank { null },
            thinkingDurationMs = thinkingDurationMs,
            incomplete = incomplete,
        )
        conversationRepository.addMessage(message)
        return message
    }

    private fun elapsedMs(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / NANOS_PER_MILLI

    private companion object {
        /**
         * INCOMPLETE_REPLIES_ARE_NOT_SENT_TO_MODEL.
         *
         * The single rule, enforced in the single place: a message the user stopped or that
         * failed mid-stream ([AiMessage.incomplete]) is kept for display but never fed back
         * into a prompt. See the class KDoc's "Stopped / interrupted replies" section for
         * why — the engine's own KV cache already had this reply's tokens rolled back via
         * [AiEngine.discardPendingReply], and replaying it here would silently reintroduce
         * exactly what that call exists to prevent.
         */
        fun isEligibleForModel(message: AiMessage): Boolean = !message.incomplete

        const val CONVERSATION_TITLE_LENGTH = 60

        /**
         * Caps how many prior turns are replayed into the prompt, before the token-aware
         * budget in [buildHistory] (3.3) does its own, finer-grained trim. Kept as an outer
         * cap regardless — a bound on how much history is even considered before estimating
         * its size is cheap insurance against a pathological conversation.
         */
        const val MAX_HISTORY_TURNS = 20

        /**
         * When [buildHistory]'s token budget is exceeded, oldest turns are dropped down to
         * this fraction of the budget rather than to exactly the limit — see that function's
         * KDoc on why a bigger cut keeps the session's KV cache stable across several turns
         * instead of forcing a re-prime on every one.
         */
        const val HISTORY_TRIM_TARGET_RATIO = 0.6

        /** [ChatTurn.PreparingModel.reason] when the engine is busy with another caller. */
        const val BUSY_REASON = "Waiting for a document to finish reading…"

        const val NANOS_PER_MILLI = 1_000_000L
        const val NO_ANSWER_PRODUCED =
            "The model finished thinking but did not produce an answer. You can try again."
    }
}
