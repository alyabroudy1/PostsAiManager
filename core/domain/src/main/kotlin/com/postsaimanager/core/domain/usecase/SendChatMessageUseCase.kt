package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.setup.DownloadActivity
import com.postsaimanager.core.domain.setup.NoDownloadActivity
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ChatImagePolicy
import com.postsaimanager.core.domain.ai.MessageImages
import com.postsaimanager.core.domain.ai.StreamSegment
import com.postsaimanager.core.domain.ai.ThinkingStreamParser
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.repository.StoredChunk
import com.postsaimanager.core.domain.skills.ChatToolsPolicy
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.MediaType
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.MessageSource
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ThinkingEffort
import com.postsaimanager.core.model.ToolExchange
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    data class PreparingModel(val reason: String? = null) : ChatTurn {
        companion object {
            /** [reason] when the engine is busy with another caller (a document being read). */
            const val WAITING_FOR_DOCUMENT = "Waiting for a document to finish reading…"
        }
    }

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

    /**
     * Finished; [message] is the persisted assistant message.
     *
     * @param sources every passage, if any, [RetrieveChunksUseCase] retrieved and injected
     *   into *this turn's* prompt (never into the grounding — see the class KDoc's
     *   "Retrieval-augmented grounding") — the same list persisted on [message]'s
     *   [AiMessage.sources] (4.3), exposed here too so a caller does not have to re-read it.
     */
    data class Complete(val message: AiMessage, val sources: List<RetrievedChunk> = emptyList()) : ChatTurn

    /** Something went wrong, phrased for a person, with an action where one exists. */
    data class Failed(val message: String, val action: ChatErrorAction?) : ChatTurn
}

enum class ChatErrorAction {
    /** Send the user to model management — nothing is installed. */
    INSTALL_MODEL,

    /** Transient; offer retry. */
    RETRY,

    /** The model is on its way (downloading in the background): nothing to fix, just wait. */
    MODEL_DOWNLOADING,
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
 *
 * ### Retrieval-augmented grounding (4.1/4.2)
 *
 * When [BuildChatContextUseCase] reports [ChatGrounding.retrievalMode] — the document did not
 * fit the grounding budget whole, or there is no single document (`documentId == null`) —
 * this use case runs [RetrieveChunksUseCase] on **every turn**, scoped to [documentId] (or the
 * whole corpus when it is null), against a query [FollowUpRetrievalQuery] builds (4.4: a
 * short follow-up is retrieved together with the previous user turn, since it rarely carries
 * enough signal alone), and prefixes the top passages to *that turn's* text before calling
 * [AiEngine.sendChatMessage]. They never touch [ChatGrounding.text]:
 *
 *  - [ChatGrounding.text] is the KV-cache prefix [AiEngine.ensureChatSession] primes the
 *    standing session with. It has to stay byte-identical across turns of the same
 *    conversation or the engine cannot reuse the cache and re-prefills on every send — the
 *    whole point of the standing session (documentation/02-architecture.md §5.3). Per-question
 *    passages are, definitionally, different every turn, so they can never live there.
 *  - The [AiMessage] persisted for the user's turn (see "Persistence brackets generation"
 *    above) is `text` itself, untouched — never the passage-prefixed version sent to the
 *    engine. [buildHistory] replays that same raw text into a rebuilt prompt on a future
 *    re-prime, which is exactly what keeps a re-prime a faithful replay of the conversation
 *    the user actually had, rather than one that silently re-injects every old turn's
 *    passages back into the window.
 *
 * A turn whose retrieval comes back empty (nothing relevant, or no chunks indexed yet) is sent
 * as plain `text` — passages are an addition, never a requirement to answer at all.
 *
 * ### Citations (4.3)
 *
 * Every passage injected into a turn — successful, stopped, or failed alike — is persisted on
 * [AiMessage.sources] (see [persistAssistant]): whatever was shown to the model grounded
 * whatever it produced, even a reply cut short. Deciding which of those to actually *show* as
 * a citation chip (all of them, versus just the ones the answer specifically cites) is a
 * presentation concern, not something this use case decides — see `CitationParser` in
 * `:feature:chat`'s `ChatViewModel`.
 */
class SendChatMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val engine: ChatEngine,
    private val activeModelProvider: ActiveModelProvider,
    private val buildChatContext: BuildChatContextUseCase,
    private val retrieveChunks: RetrieveChunksUseCase,
    /** Tells "no model yet" from "no model yet, but it is downloading". */
    private val downloads: DownloadActivity = NoDownloadActivity,
) {

    operator fun invoke(
        conversationId: String,
        documentId: String?,
        text: String,
        systemPrompt: String? = null,
        // Default OFF — see InferenceOverrides.thinkingEffort's KDoc: on-device CPU decode
        // is slow enough that an unbounded Qwen3/3.5 reasoning trace can add tens of seconds
        // before the first visible answer token.
        thinkingEffort: ThinkingEffort = ThinkingEffort.OFF,
        /**
         * False only for [regenerateLastReply] (5.1): [text] there is already the latest row
         * `ConversationRepository` holds for this conversation — the just-deleted reply's
         * preceding user turn — so persisting it again would leave two copies of the same
         * question in history. Everything else about the turn (grounding, retrieval,
         * priming, commit) runs exactly as a normal send.
         */
        persistUserMessage: Boolean = true,
        /**
         * The pictures the user attached to [text] (files of `ChatImageStore`, at most [MessageImages.MAX_PER_MESSAGE]), stored with
         * the user's message. A message that is sent again as it is (a retry, [regenerateLastReply]) keeps its pictures.
         */
        imagePaths: List<String> = emptyList(),
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
        // precede this one — see the class KDoc on "what is sent to the model". When
        // [persistUserMessage] is false, [text] is already the trailing row (regenerate), so
        // it is dropped here too — the rest of this function must see the same "turns before
        // this one" shape either way.
        val readTurns = conversationRepository.getMessages(conversationId).first()
        val priorTurns = if (persistUserMessage) readTurns else readTurns.dropLast(1)

        if (persistUserMessage) {
            // Persist the user's message first — see the class note on ordering.
            val userMessage = AiMessage(
                id = UuidGenerator.generate(),
                conversationId = conversationId,
                role = MessageRole.USER,
                content = text,
                mediaType = if (imagePaths.isNotEmpty()) MediaType.IMAGE else MediaType.TEXT,
                mediaPath = MessageImages.encode(imagePaths),
                createdAt = now,
            )
            conversationRepository.addMessage(userMessage)
        }
        // The pictures of this turn: the ones just attached, or the ones the re-sent message was stored with.
        val turnImages = imagePaths.ifEmpty {
            readTurns.lastOrNull()?.takeIf {
                !persistUserMessage && it.role == MessageRole.USER && it.mediaType == MediaType.IMAGE
            }?.let { MessageImages.decode(it.mediaPath) }.orEmpty()
        }.take(MessageImages.MAX_PER_MESSAGE)

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
            val downloading = downloads.summary.first()?.takeUnless { it.failed } != null
            emit(
                if (downloading) {
                    ChatTurn.Failed(
                        "The AI model is still downloading. Chat will work as soon as it is ready.",
                        ChatErrorAction.MODEL_DOWNLOADING,
                    )
                } else {
                    ChatTurn.Failed(
                        "No AI model is installed yet. Install one to chat about your documents.",
                        ChatErrorAction.INSTALL_MODEL,
                    )
                },
            )
            return@flow
        }

        val config = activeModelProvider.activeModelConfig()

        // Built up front, not gated on whether the session looks primed (unlike pre-4.1):
        // `chatContext.retrievalMode` has to be known on *every* turn — including one that
        // never re-primes — to decide whether this turn needs a retrieval pass at all. The
        // DB reads this does are cheap (Room, no model call); see the class KDoc,
        // "Retrieval-augmented grounding".
        com.postsaimanager.core.common.util.TimingLog.at("use case: db + model path done, building context")
        val chatContext = systemPrompt?.let { ChatGrounding(it, retrievalMode = false) }
            ?: buildChatContext(documentId, config.contextTokens)
        com.postsaimanager.core.common.util.TimingLog.at(
            "context built: grounding=${chatContext.text.length} chars retrievalMode=${chatContext.retrievalMode} contextTokens=${config.contextTokens}",
        )

        emit(ChatTurn.PreparingModel(reason = if (engine.isBusy) BUSY_REASON else null))

        // Retrieval overlaps `engine.load` (the slow part — can mean a multi-second cold
        // model load, or waiting behind an in-flight generate() on the shared engine, see
        // AiEngine.isBusy) exactly the way grounding used to (3.1) — retrieval is now the
        // expensive half of "what does this turn need", since it can itself call the
        // embedding model. Skipped entirely outside retrieval mode: nothing to inject when
        // the whole document already sits in the (stable) grounding.
        // 4.4: a short follow-up retrieves badly on its own — see FollowUpRetrievalQuery's
        // KDoc. Only the string handed to retrieval changes; `text` itself (persisted, and
        // what withPassages prefixes passages onto) is untouched.
        val retrievalQuery = FollowUpRetrievalQuery.build(
            text = text,
            previousUserText = priorTurns.lastOrNull { it.role == MessageRole.USER }?.content,
        )

        val (loaded, retrieved) = coroutineScope {
            val retrievalDeferred = when {
                chatContext.retrievalMode ->
                    async { retrieveChunks(retrievalQuery, limit = RETRIEVAL_LIMIT, documentId = documentId) }
                // The whole document is already in the grounding, but the answer should still cite where it lives: rank the
                // document's passages for the question, only to attach them as sources (they are never put in the prompt).
                documentId != null ->
                    async {
                        retrieveChunks(
                            retrievalQuery,
                            limit = WHOLE_DOCUMENT_SOURCE_LIMIT,
                            documentId = documentId,
                            readingOrderIfNoMatch = true,
                        )
                    }
                else -> null
            }
            val loadResult = try {
                engine.load(activeModelPath, config)
            } catch (e: Throwable) {
                // Cancel both on failure — an exception from `load` (as opposed to the
                // `PamResult.Error` value it normally returns) means this whole turn is being
                // torn down, and the retrieval call should not keep running orphaned.
                retrievalDeferred?.cancel()
                throw e
            }
            if (loadResult is PamResult.Error) {
                retrievalDeferred?.cancel()
                loadResult to null
            } else {
                loadResult to retrievalDeferred?.await()
            }
        }
        if (loaded is PamResult.Error) {
            emit(ChatTurn.Failed(loaded.error.userMessage, ChatErrorAction.RETRY))
            return@flow
        }

        // Re-checked after `load`: it may have triggered a reload (a config changed since
        // the last send), which is the one thing that can invalidate a previously-primed
        // session. This is the ground truth for whether `ensureChatSession` below is about
        // to re-prime.
        com.postsaimanager.core.common.util.TimingLog.at("load + retrieval done (retrieved=${retrieved?.chunks?.size})")
        val needsPriming = !engine.isChatSessionPrimed(conversationId)
        val contextTokens = when (val state = engine.state.value) {
            is ModelLoadState.Ready -> state.config.contextTokens
            else -> config.contextTokens
        }
        // 3.5: the grounding text itself is only ever sent to the engine when a (re)prime is
        // about to happen — an already-primed session's cache already holds it, and sending
        // it again would not even be wrong, just wasted decode work `ensureChatSession`
        // would have to notice and skip itself.
        val grounding = if (needsPriming) chatContext.text else ""

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
        primeMutex.withLock {
            engine.ensureChatSession(conversationId, grounding, buildHistory(priorTurns, historyWindow(config, contextTokens), grounding))
        }
        com.postsaimanager.core.common.util.TimingLog.at("ensureChatSession done (needsPriming=$needsPriming, prior turns=${priorTurns.size})")

        // 4.1/4.2: fold retrieved passages into *this turn's* text only — never into
        // `grounding` above, which must stay stable across turns. `sentText` is what the
        // engine actually sees; `text` (persisted a few lines up, and again in `buildHistory`
        // on a future re-prime) never changes. `sources` is every passage that made it in —
        // persisted verbatim on the assistant reply (4.3), see [persistAssistant].
        val (sentText, sources) = if (chatContext.retrievalMode) {
            withPassages(text, retrieved, documentId, contextTokens)
        } else {
            // Whole document in the grounding: the prompt is the bare question; the top passages are cited, not injected.
            text to retrieved?.chunks.orEmpty().take(WHOLE_DOCUMENT_SOURCE_LIMIT)
        }

        // With thinking on, the engine starts the reply inside an already-open `<think>` block
        // (see llama_jni.cpp's sendChatMessage), so the stream never carries the opening tag.
        // Read after `engine.load`, which picked the engine: one that cannot hand a reasoning trace back separately is asked for none.
        val effort = if (engine.supportsThinking) thinkingEffort else ThinkingEffort.OFF
        val parser = ThinkingStreamParser(startInThinking = effort != ThinkingEffort.OFF)
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
            // session's own history plus `sentText`; only the sampling/thinking fields
            // matter. `sentText` is `text` with any retrieved passages prefixed (4.1/4.2) —
            // see the class KDoc's "Retrieval-augmented grounding".
            // Agent Skills tools: only for a LiteRT-LM model whose catalogue entry declares them (ChatToolsPolicy). The letter the
            // actions are grounded on is the chat's, or the one the reply's passages all come from; null leaves the card flagged.
            val tools = ChatToolsPolicy.requestFor(config, documentId, sources.map { it.chunk.documentId })
            // The picture goes to a model that can look at it; any other model answers the words alone.
            val images = if (ChatImagePolicy.enabledFor(config)) turnImages else emptyList()
            com.postsaimanager.core.common.util.TimingLog.at("BEFORE ENGINE: sending ${sentText.length} chars, tools=${tools != null}")
            var tokensSeen = 0
            engine.sendChatMessage(
                sentText,
                ChatReplyBudget.request(effort, contextTokens, config.modelSampling).copy(tools = tools, imagePaths = images),
            )
                .collect { token ->
                    if (tokensSeen++ == 0) com.postsaimanager.core.common.util.TimingLog.at("app: first token received")
                    apply(parser.consume(token)).forEach { emit(it) }
                }
            com.postsaimanager.core.common.util.TimingLog.at("app: stream finished ($tokensSeen tokens, ${answerBuilder.length} chars)")
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
                    // The stop landed mid-sentence, possibly mid-citation — parsing what
                    // was and wasn't cited against unfinished text would be meaningless, so
                    // every passage the model was actually shown is kept (see class KDoc's
                    // "Stopped / interrupted replies" and [AiMessage.sources]).
                    sources = sources.toMessageSources(),
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
                        sources = sources.toMessageSources(),
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

        // "Model finished thinking but produced no answer" — a thinking trace with nothing
        // after it (ran out of budget still inside <think>, or hit EOS right after
        // </think> with no answer text). Never shown as a blank bubble: this follows the
        // same incomplete/Retry path a stopped or crashed reply takes — never committed to
        // the session's history (discardPendingReply, not commitChatReply), so it can never
        // re-enter a future prompt — rather than persisting a normal-looking answer with a
        // placeholder string, which is what this used to do (see git history/NO_ANSWER_PRODUCED).
        if (answerBuilder.isBlank() && thinkingBuilder.isNotBlank()) {
            persistAssistant(
                conversationId,
                content = RAN_OUT_OF_ROOM_WHILE_THINKING,
                thinking = thinkingBuilder.toString(),
                thinkingDurationMs = thinkingDurationMs,
                incomplete = true,
                sources = sources.toMessageSources(),
            )
            engine.discardPendingReply()
            emit(ChatTurn.Failed(RAN_OUT_OF_ROOM_WHILE_THINKING, ChatErrorAction.RETRY))
            return@flow
        }

        // The reply ended by reaching its token cap, not by EOS: it stops mid-thought. Same
        // incomplete/Retry path as a stopped reply (never committed to the session, never
        // replayed into a prompt), but flagged cutOff so the UI says "Answer was cut off".
        if (engine.lastReplyHitLimit()) {
            val cutOffMessage = persistAssistant(
                conversationId,
                answerBuilder.toString(),
                thinkingBuilder.toString(),
                thinkingDurationMs,
                incomplete = true,
                cutOff = true,
                sources = sources.toMessageSources(),
            )
            engine.discardPendingReply()
            emit(ChatTurn.Complete(cutOffMessage, sources = sources))
            return@flow
        }

        val assistant = persistAssistant(
            conversationId,
            answerBuilder.toString(),
            thinkingBuilder.toString(),
            thinkingDurationMs,
            sources = sources.toMessageSources(),
            // What the model called during this reply: stored with it so a rebuilt conversation replays the calls too.
            toolTrace = engine.lastReplyToolExchanges(),
        )
        // Records the (thinking-stripped) reply in the session's own history so the next
        // turn's diff renders correctly — see AiEngine.commitChatReply's KDoc. Uses
        // `assistant.content` rather than `answerBuilder` directly so the session's record
        // and what got persisted (and is shown as history next time) are always the same
        // string.
        engine.commitChatReply(assistant.content)
        emit(ChatTurn.Complete(assistant, sources = sources))
    }

    /**
     * Regenerates the LATEST assistant reply in [conversationId] (5.1).
     *
     * The KV-cache constraint this has to respect: [commitChatReply] already folded the
     * reply being regenerated into the engine's standing session — its tokens are sitting in
     * the cache and its text is in `chatHistory`. Simply deleting the persisted message and
     * sending again would leave the session out of sync with what is now in the DB (the
     * cache would still "remember" an answer that no longer exists), so this:
     *
     * 1. Deletes the latest assistant [AiMessage] (and its sources — `ON DELETE CASCADE`,
     *    see `MessageSourceEntity`) from [conversationRepository].
     * 2. Calls [AiEngine.resetChatSession] to drop the standing session outright, so the
     *    *next* [ensureChatSession][AiEngine.ensureChatSession] call — inside the [invoke] this
     *    delegates to — is forced to re-prime from scratch, replaying exactly what
     *    [conversationRepository] now holds (the deleted reply excluded).
     * 3. Re-runs [invoke] for the same user text, with `persistUserMessage = false` — that
     *    text is already the trailing row in the DB (the turn preceding the reply just
     *    deleted), so sending it again must not write a second copy.
     *
     * Not this use case's job to decide *whether* regeneration is offered — the caller
     * (`ChatViewModel`/`ChatScreen`) only ever offers it on the newest assistant reply. This
     * trusts that and looks up the newest one directly; regenerating anything else would
     * delete a reply with turns still after it, which nothing here reconciles.
     *
     * @return the same [ChatTurn] stream [invoke] emits for a fresh send.
     */
    fun regenerateLastReply(
        conversationId: String,
        documentId: String?,
        systemPrompt: String? = null,
        // Default OFF — same reasoning as invoke()'s own default; see its KDoc.
        thinkingEffort: ThinkingEffort = ThinkingEffort.OFF,
    ): Flow<ChatTurn> = flow {
        val messages = conversationRepository.getMessages(conversationId).first()
        val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val precedingUserText = lastAssistant
            ?.let { assistant -> messages.takeWhile { it.id != assistant.id } }
            ?.lastOrNull { it.role == MessageRole.USER }
            ?.content

        if (lastAssistant == null || precedingUserText == null) {
            emit(ChatTurn.Failed("Nothing to regenerate yet.", null))
            return@flow
        }

        conversationRepository.deleteMessage(lastAssistant.id)
        engine.resetChatSession()

        emitAll(
            invoke(
                conversationId = conversationId,
                documentId = documentId,
                text = precedingUserText,
                systemPrompt = systemPrompt,
                thinkingEffort = thinkingEffort,
                persistUserMessage = false,
            ),
        )
    }

    /**
     * Loads the active model and primes [conversationId]'s chat session — grounding plus
     * history — without sending a message. Used by the chat screen's pre-warm-on-open path
     * (`ChatViewModel.preWarmModel`) so the first real [invoke] only has to decode its own new
     * turn, instead of also paying the grounding/history prefill this normally does on the
     * first send after the screen opens (documentation/02-architecture.md §5.3's cold-prime
     * measurement).
     *
     * Mirrors exactly the subset of [invoke] that loads the model and calls
     * [AiEngine.ensureChatSession] — with `systemPrompt` always null (this project's only
     * caller of [invoke], [ChatViewModel], never passes one either), so the grounding text
     * this builds is byte-for-byte what a following [invoke] for the same conversation would
     * build, and [AiEngine.ensureChatSession]'s own "already primed for this conversation"
     * check treats them as the same session rather than re-priming.
     *
     * Safe to race a real [invoke] call: both go through the same [AiEngine.load] (single-
     * flight in `ModelLoadCoordinator`) and the same [AiEngine.ensureChatSession] (serialized
     * on the engine's own session lock — see `LocalAiEngine`). Whichever call reaches
     * `ensureChatSession` first does the real decode; the other blocks on the lock and then
     * finds the session already primed, a no-op — never two priming decodes, never a decode
     * racing a read of the session it is still building.
     *
     * A no-op, not an error, when nothing is installed or the load fails — same as
     * [PreloadActiveModelUseCase], which this replaces at that call site.
     */
    suspend fun primeConversation(
        conversationId: String,
        documentId: String?,
        /** Called once [AiEngine.load] returned — i.e. once any wait behind a document read is over. */
        onLoadFinished: () -> Unit = {},
    ) {
        val activeModelPath = activeModelProvider.activeModelPath() ?: return
        val config = activeModelProvider.activeModelConfig()
        val loaded = engine.load(activeModelPath, config)
        onLoadFinished()
        if (loaded is PamResult.Error) return

        // Serialised with the send path's own ensureChatSession call and re-checked under
        // the lock, so a send that raced this prime (both waiting behind an in-flight
        // document read, say) joins it instead of priming the same conversation twice.
        primeMutex.withLock {
            // Same ground truth invoke() re-checks after load — a config change during load
            // can still require a (re)prime even if this conversation looked primed a
            // moment ago.
            if (engine.isChatSessionPrimed(conversationId)) return

            val chatContext = buildChatContext(documentId, config.contextTokens)
            // A trailing user message is a turn a concurrent send has just persisted and is
            // about to append itself (or an orphan): replaying it here would put it in the
            // session twice.
            val priorTurns = conversationRepository.getMessages(conversationId).first()
                .let { turns -> if (turns.lastOrNull()?.role == MessageRole.USER) turns.dropLast(1) else turns }
            val contextTokens = when (val state = engine.state.value) {
                is ModelLoadState.Ready -> state.config.contextTokens
                else -> config.contextTokens
            }
            engine.ensureChatSession(
                conversationId,
                chatContext.text,
                buildHistory(priorTurns, historyWindow(config, contextTokens), chatContext.text),
            )
        }
    }

    /**
     * The window the history is budgeted against. A reply with the Agent Skills tools needs room the plain chat does not: the skills
     * in the system prompt, the tool schemas, a skill's text coming back as a tool result and the call itself. Without that
     * reserve a calendar request failed on the phone with "Prefill input length exceeds available state entries".
     */
    private fun historyWindow(config: com.postsaimanager.core.model.InferenceConfig, contextTokens: Int): Int =
        if (ChatToolsPolicy.enabledFor(config)) (contextTokens - TOOLS_RESERVE_TOKENS).coerceAtLeast(BuildChatContextUseCase.MIN_CONTEXT_TOKENS) else contextTokens

    /** See [primeConversation]. */
    private val primeMutex = Mutex()

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

        // A replayed tool trace is prompt too: the skill text a load_skill call returned is a thousand characters or more.
        fun promptChars(message: AiMessage) = message.content.length + message.toolTrace.sumOf { it.promptChars }
        var totalChars = eligible.sumOf(::promptChars)
        val trimmed = if (totalChars <= historyBudgetChars) {
            eligible
        } else {
            // Drop oldest-first down to HISTORY_TRIM_TARGET_RATIO of budget, not just under
            // it — see the class doc above on why a smaller, stabler cut avoids re-priming
            // every turn once a conversation is hovering near the limit.
            val targetChars = (historyBudgetChars * HISTORY_TRIM_TARGET_RATIO).toInt()
            val kept = eligible.toMutableList()
            while (kept.size > 1 && totalChars > targetChars) {
                totalChars -= promptChars(kept.removeAt(0))
            }
            kept
        }

        return trimmed.map { message ->
            AiChatMessage(
                role = if (message.role == MessageRole.USER) AiChatRole.USER else AiChatRole.ASSISTANT,
                content = withImageMarker(message),
                toolTrace = message.toolTrace,
            )
        }
    }

    /** A turn that had pictures is replayed with a text marker in their place (see [MessageImages.marker]). */
    private fun withImageMarker(message: AiMessage): String {
        if (message.role != MessageRole.USER || message.mediaType != MediaType.IMAGE) return message.content
        val count = MessageImages.decode(message.mediaPath).size
        return if (count == 0) message.content else "${MessageImages.marker(count)}\n${message.content}"
    }

    /**
     * Folds [retrieval]'s passages, if any, into a copy of [text] for the engine to see —
     * see the class KDoc's "Retrieval-augmented grounding" for why this must never touch
     * [text] itself or the grounding prefix.
     *
     * Passages are fit into their own budget — [PASSAGE_BUDGET_FRACTION] of [contextTokens]
     * — independent of, and on top of, the grounding/history budgets [buildHistory] already
     * enforces: those account for the *stable* prefix, this is purely this turn's addition,
     * dropped once the reply is generated. Passages are taken in ranked order and the first
     * one that would overflow the budget is where inclusion stops, so at least one passage
     * survives even a tight budget (unless even it alone does not fit).
     *
     * @return the text to actually send, and the passages that made it in — the latter is
     *   what [ChatTurn.Complete.sources] exposes, and what [persistAssistant] stores on
     *   [AiMessage.sources] (4.3).
     */
    private suspend fun withPassages(
        text: String,
        retrieval: RetrieveChunksUseCase.Result?,
        documentId: String?,
        contextTokens: Int,
    ): Pair<String, List<RetrievedChunk>> {
        val chunks = retrieval?.chunks.orEmpty()
        if (chunks.isEmpty()) return text to emptyList()

        val budgetChars = (contextTokens * PASSAGE_BUDGET_FRACTION).toInt() *
            BuildChatContextUseCase.CHARS_PER_TOKEN
        // Standalone chat (documentId == null) spans every document, so a passage needs its
        // source spelled out; a document-scoped chat already has exactly one source in the
        // grounding, so only the page matters. Cached per document id — several passages
        // routinely come from the same document.
        val titleCache = mutableMapOf<String, String?>()
        suspend fun label(chunk: StoredChunk): String {
            val where = chunk.pageNumber?.let { "p.$it" } ?: "part ${chunk.ordinal + 1}"
            if (documentId != null) return where
            val title = titleCache.getOrPut(chunk.documentId) { buildChatContext.documentTitle(chunk.documentId) }
            return "${title ?: "Untitled document"}, $where"
        }

        val included = mutableListOf<RetrievedChunk>()
        val body = StringBuilder()
        var used = 0
        for (retrievedChunk in chunks) {
            val entry = "[${label(retrievedChunk.chunk)}] ${retrievedChunk.chunk.text}\n\n"
            if (included.isNotEmpty() && used + entry.length > budgetChars) break
            body.append(entry)
            used += entry.length
            included += retrievedChunk
        }
        if (included.isEmpty()) return text to emptyList()

        val prefixed = PASSAGE_INSTRUCTION + "\n\n" + body.toString().trimEnd() + "\n\n---\n\n" + text
        return prefixed to included
    }

    private suspend fun persistAssistant(
        conversationId: String,
        content: String,
        thinking: String,
        thinkingDurationMs: Long?,
        incomplete: Boolean = false,
        cutOff: Boolean = false,
        sources: List<MessageSource> = emptyList(),
        toolTrace: List<ToolExchange> = emptyList(),
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
            cutOff = cutOff,
            sources = sources,
            toolTrace = toolTrace,
        )
        conversationRepository.addMessage(message)
        return message
    }

    /** [RetrievedChunk] -> the minimal shape [AiMessage.sources] persists — see [MessageSource]. */
    private fun List<RetrievedChunk>.toMessageSources(): List<MessageSource> = map {
        MessageSource(documentId = it.chunk.documentId, pageNumber = it.chunk.pageNumber, chunkId = it.chunk.id)
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

        /** Tokens kept free of history for the tools' prompt, schemas, a loaded skill and the call (see [historyWindow]). */
        const val TOOLS_RESERVE_TOKENS = 2000

        /**
         * When [buildHistory]'s token budget is exceeded, oldest turns are dropped down to
         * this fraction of the budget rather than to exactly the limit — see that function's
         * KDoc on why a bigger cut keeps the session's KV cache stable across several turns
         * instead of forcing a re-prime on every one.
         */
        const val HISTORY_TRIM_TARGET_RATIO = 0.6

        /** [ChatTurn.PreparingModel.reason] when the engine is busy with another caller. */
        const val BUSY_REASON = ChatTurn.PreparingModel.WAITING_FOR_DOCUMENT

        const val NANOS_PER_MILLI = 1_000_000L
        const val NO_ANSWER_PRODUCED =
            "The model finished thinking but did not produce an answer. You can try again."

        /** M5: shown instead of a blank bubble when thinking used the whole reply budget. */
        const val RAN_OUT_OF_ROOM_WHILE_THINKING =
            "The model ran out of room while thinking. Try again, or set Thinking to Off " +
                "for faster answers."

        /**
         * How many passages [RetrieveChunksUseCase] is asked for per turn (4.1/4.2). A
         * handful, smaller than [RetrieveChunksUseCase]'s own default limit —
         * [withPassages]'s char budget is the real limit on what actually gets used; this
         * just bounds the ranking work and keeps clearly-irrelevant tail results out of
         * contention.
         */
        const val RETRIEVAL_LIMIT = 4

        /** How many passages a whole-document chat cites as sources (the document is fully in the prompt, so this is only attribution). */
        const val WHOLE_DOCUMENT_SOURCE_LIMIT = 2

        /**
         * Retrieved passages get at most this fraction of the context window, in
         * [withPassages]. A turn's prompt is grounding + history + passages + the question
         * + the reply — passages competing for the same budget as everything else would let
         * one greedy retrieval starve the reply reserve [BuildChatContextUseCase] already
         * budgets for; capping them to a quarter leaves the rest for what was already
         * accounted for.
         */
        const val PASSAGE_BUDGET_FRACTION = 0.25

        /** Prefixed ahead of any turn that has passages to show the model. */
        const val PASSAGE_INSTRUCTION =
            "Use the following excerpts if they help answer the question below. Cite the " +
                "ones you use like [p.2] or [Document title, p.2]."
    }
}
