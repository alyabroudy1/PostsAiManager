package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ThinkingEffort
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testChunk
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [SendChatMessageUseCase], focused on the defect this file was fixed for: a
 * config change (accelerator, threads, context) made in the chat header sheet while the
 * engine already reports Ready on the old one used to be silently ignored, because
 * `engine.load` was only called when the model *path* changed or the engine was not ready.
 * That also left `ModelLoadCoordinator.lastRequested` stale, so crash recovery replayed the
 * abandoned config instead of the one the user actually asked for.
 */
class SendChatMessageUseCaseTest {

    private val engine = FakeAiEngine()
    private val models = FakeActiveModelProvider()
    private val conversations = FakeConversationRepository()
    private val buildChatContext = BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository())
    private val chunkRepository = FakeDocumentChunkRepository()
    private val retrieveChunks = RetrieveChunksUseCase(chunkRepository, FakeEmbeddingService())
    private val sendChatMessage =
        SendChatMessageUseCase(conversations, engine, models, buildChatContext, retrieveChunks)

    @Test
    @DisplayName("calls engine.load with the current config on every send, not just the first")
    fun `reconciles config on every send`() = runTest {
        engine.response = "hi"

        sendChatMessage("conv-1", documentId = null, text = "first").toList()
        sendChatMessage("conv-1", documentId = null, text = "second").toList()

        assertThat(engine.loadCalls).hasSize(2)
        // Same model, same config both times — but the use case must not skip the second
        // call just because the engine already reports Ready on that model.
        assertThat(engine.loadCalls[0].first).isEqualTo(models.path)
        assertThat(engine.loadCalls[1].first).isEqualTo(models.path)
    }

    @Test
    @DisplayName("a changed accelerator is reflected in the very next load call")
    fun `accelerator change is picked up immediately`() = runTest {
        engine.response = "hi"

        sendChatMessage("conv-1", documentId = null, text = "first").toList()
        assertThat(engine.loadCalls.last().second.accelerator).isEqualTo(Accelerator.CPU)

        // The user opens the chat header sheet and switches to GPU — nothing about the
        // model path changes, only the config `activeModelProvider` now reports.
        models.accelerator = Accelerator.GPU

        sendChatMessage("conv-1", documentId = null, text = "second").toList()
        assertThat(engine.loadCalls.last().second.accelerator).isEqualTo(Accelerator.GPU)
    }

    @Test
    @DisplayName("no AI model installed surfaces an install action instead of hanging")
    fun `no model installed fails loudly`() = runTest {
        models.path = null

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        assertThat(turns).hasSize(1)
        assertThat(turns.single()).isInstanceOf(ChatTurn.Failed::class.java)
        assertThat(engine.loadCalls).isEmpty()
    }

    @Test
    @DisplayName("a <think> block is split from the answer and both are persisted separately")
    fun `thinking and answer are persisted separately`() = runTest {
        engine.response = "<think>The user wants a greeting.</think>\n\nHi there!"

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        val complete = turns.filterIsInstance<ChatTurn.Complete>().single()
        assertThat(complete.message.content).isEqualTo("Hi there!")
        assertThat(complete.message.thinking).isEqualTo("The user wants a greeting.")
        assertThat(complete.message.thinkingDurationMs).isNotNull()

        assertThat(turns.filterIsInstance<ChatTurn.ThinkingToken>()).isNotEmpty()
        assertThat(turns.filterIsInstance<ChatTurn.ThinkingComplete>()).hasSize(1)
        assertThat(turns.filterIsInstance<ChatTurn.Token>().joinToString("") { it.text })
            .isEqualTo("Hi there!")
    }

    @Test
    @DisplayName("a stream that never closes its think block never shows a blank bubble — it fails, marked incomplete, with a Retry action")
    fun `unclosed thinking is never a blank bubble`() = runTest {
        engine.response = "<think>still reasoning and the stream just stops here"

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        // M5: "model finished thinking but produced no answer" is never persisted as a
        // normal-looking Complete turn with placeholder content — it follows the same
        // incomplete/Retry path a stopped or crashed reply takes.
        val failed = turns.filterIsInstance<ChatTurn.Failed>().single()
        assertThat(failed.action).isEqualTo(ChatErrorAction.RETRY)
        assertThat(turns.filterIsInstance<ChatTurn.Complete>()).isEmpty()
        assertThat(turns.filterIsInstance<ChatTurn.Token>()).isEmpty()

        val persisted = conversations.getMessages("conv-1").first()
        val assistantMessage = persisted.last { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessage.incomplete).isTrue()
        assertThat(assistantMessage.thinking)
            .isEqualTo("still reasoning and the stream just stops here")

        // Never committed into the session's own history either — excluded from future
        // prompts, same as any other interrupted reply.
        assertThat(engine.committedReplies).isEmpty()
        assertThat(engine.discardedReplies).hasSize(1)
    }

    @Test
    @DisplayName("thinking that closes properly but leaves nothing after it is the same as never producing an answer")
    fun `closed thinking with no trailing answer is never a blank bubble`() = runTest {
        // The exact defect this was filed for: a small model spends its whole reply budget
        // reasoning and hits </think> with the turn already over — properly closed, but
        // nothing follows it.
        engine.response = "<think>I have used my whole budget reasoning about this.</think>"

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        val failed = turns.filterIsInstance<ChatTurn.Failed>().single()
        assertThat(failed.action).isEqualTo(ChatErrorAction.RETRY)
        assertThat(turns.filterIsInstance<ChatTurn.Complete>()).isEmpty()

        val persisted = conversations.getMessages("conv-1").first()
        val assistantMessage = persisted.last { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessage.incomplete).isTrue()
    }

    // ── M1/M2: reply/thinking token budgets ──

    @Test
    @DisplayName("thinking Off sends no thinking budget and thinkingEnabled=false")
    fun `off effort sends no thinking budget`() = runTest {
        engine.response = "hi"
        sendChatMessage("conv-1", documentId = null, text = "hello", thinkingEffort = ThinkingEffort.OFF)
            .toList()

        val request = engine.lastRequest
        assertThat(request).isNotNull()
        assertThat(request!!.thinkingEnabled).isFalse()
        assertThat(request.thinkingBudgetTokens).isEqualTo(0)
    }

    @Test
    @DisplayName("thinking Low requests the low sub-budget with thinkingEnabled=true")
    fun `low effort requests the low sub-budget`() = runTest {
        engine.response = "hi"
        sendChatMessage("conv-1", documentId = null, text = "hello", thinkingEffort = ThinkingEffort.LOW)
            .toList()

        val request = engine.lastRequest
        assertThat(request).isNotNull()
        assertThat(request!!.thinkingEnabled).isTrue()
        assertThat(request.thinkingBudgetTokens).isEqualTo(256)
    }

    @Test
    @DisplayName("thinking High requests the high sub-budget with thinkingEnabled=true")
    fun `high effort requests the high sub-budget`() = runTest {
        engine.response = "hi"
        sendChatMessage("conv-1", documentId = null, text = "hello", thinkingEffort = ThinkingEffort.HIGH)
            .toList()

        val request = engine.lastRequest
        assertThat(request).isNotNull()
        assertThat(request!!.thinkingEnabled).isTrue()
        assertThat(request.thinkingBudgetTokens).isEqualTo(1024)
    }

    @Test
    @DisplayName("every thinking effort level requests the same reply cap")
    fun `reply cap is independent of thinking effort`() = runTest {
        engine.response = "hi"

        sendChatMessage("conv-1", documentId = null, text = "a", thinkingEffort = ThinkingEffort.OFF).toList()
        val offMaxTokens = engine.lastRequest!!.maxTokens

        sendChatMessage("conv-1", documentId = null, text = "b", thinkingEffort = ThinkingEffort.HIGH).toList()
        val highMaxTokens = engine.lastRequest!!.maxTokens

        // The reply cap is the answer/output budget, sized independently of thinking effort
        // (M1) — the thinking budget is a *sub*-budget inside it, never a separate addend, so
        // a higher thinking effort never grows the ceiling native clamps against.
        assertThat(offMaxTokens).isEqualTo(highMaxTokens)
        assertThat(offMaxTokens).isEqualTo(1024)
    }

    @Test
    @DisplayName("a user who explicitly picked Off via regenerate keeps thinking off there too")
    fun `regenerate honours the requested thinking effort`() = runTest {
        engine.response = "hi"
        sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        engine.response = "regenerated"
        sendChatMessage.regenerateLastReply(
            "conv-1",
            documentId = null,
            thinkingEffort = ThinkingEffort.HIGH,
        ).toList()

        assertThat(engine.lastRequest!!.thinkingBudgetTokens).isEqualTo(1024)
    }

    @Test
    @DisplayName("a model that never emits <think> tags persists no thinking at all")
    fun `no tags means no thinking is persisted`() = runTest {
        engine.response = "Just a plain answer, no reasoning trace."

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        val complete = turns.filterIsInstance<ChatTurn.Complete>().single()
        assertThat(complete.message.thinking).isNull()
        assertThat(complete.message.thinkingDurationMs).isNull()
        assertThat(complete.message.content).isEqualTo("Just a plain answer, no reasoning trace.")
    }

    @Test
    @DisplayName("thinking is never sent back to the model")
    fun `thinking is never sent back to the model`() = runTest {
        // First turn: the model thinks out loud, then answers.
        engine.response = "<think>Scratch notes nobody should ever see again.</think>\n\nFirst answer."
        sendChatMessage("conv-1", documentId = null, text = "first question").toList()

        // The reply committed into the session's own history — what SendChatMessageUseCase
        // told the engine to remember for future turns — must be the answer only.
        assertThat(engine.committedReplies).containsExactly("First answer.")

        // Second turn: `ensureChatSession`'s `history` (what a fresh/reloaded session would
        // be re-primed with) must carry the *answer* from the previous turn, and must not
        // contain so much as a fragment of its thinking — the only path any prior turn's
        // text reaches the model through is `SendChatMessageUseCase.buildHistory`, which
        // reads `AiMessage.content` only.
        engine.response = "Second answer, no thinking this time."
        sendChatMessage("conv-1", documentId = null, text = "second question").toList()

        val history = engine.lastSessionHistory
        val historyText = history.joinToString("\n") { it.content }

        assertThat(historyText).contains("First answer.")
        assertThat(historyText).doesNotContain("Scratch notes")
        assertThat(history.any { it.role == com.postsaimanager.core.domain.ai.AiChatRole.ASSISTANT })
            .isTrue()
        assertThat(engine.committedReplies)
            .containsExactly("First answer.", "Second answer, no thinking this time.")
    }

    @Test
    @DisplayName("stopping generation persists the partial reply as incomplete and discards the pending session reply, not commits it")
    fun `stopped generation persists a partial incomplete reply`() = runTest {
        engine.response = "This is a long answer that will be interrupted"
        // Stands in for the real `ChatViewModel.stopGeneration()` cancelling the collecting
        // job: `SendChatMessageUseCase` reacts identically to any `CancellationException`
        // raised while collecting `engine.sendChatMessage`, so raising one directly here
        // (after some text has already streamed) exercises that same catch block
        // deterministically, without depending on exactly when cooperative cancellation
        // happens to be observed mid-collection.
        engine.failAfterResponse = CancellationException("user tapped Stop")

        val caught = runCatching {
            sendChatMessage("conv-1", documentId = null, text = "tell me a story").toList()
        }.exceptionOrNull()
        assertThat(caught).isInstanceOf(CancellationException::class.java)

        // Never committed as if the model had actually said it in full.
        assertThat(engine.committedReplies).isEmpty()
        // The KV cache's pending-reply tokens were rolled back exactly once.
        assertThat(engine.discardedReplies).hasSize(1)

        val persisted = conversations.getMessages("conv-1").first()
        val assistantMessage = persisted.last { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessage.incomplete).isTrue()
        assertThat(assistantMessage.content).isEqualTo(engine.response)
    }

    @Test
    @DisplayName("a crash mid-stream follows the same path as a stop: partial kept, incomplete, pending reply discarded")
    fun `crash mid-stream persists a partial incomplete reply`() = runTest {
        engine.response = "Partial answer before the crash"
        engine.failAfterResponse = IllegalStateException("native crash")

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        assertThat(turns.last()).isInstanceOf(ChatTurn.Failed::class.java)
        assertThat(engine.committedReplies).isEmpty()
        assertThat(engine.discardedReplies).hasSize(1)

        val persisted = conversations.getMessages("conv-1").first()
        val assistantMessage = persisted.last { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessage.incomplete).isTrue()
        assertThat(assistantMessage.content).isEqualTo("Partial answer before the crash")
    }

    @Test
    @DisplayName("incomplete replies are never sent back to the model as history")
    fun `incomplete replies are excluded from history sent to the model`() = runTest {
        val now = System.currentTimeMillis()
        conversations.createConversation(
            com.postsaimanager.core.model.AiConversation(
                id = "conv-1",
                documentId = null,
                aiModelId = null,
                modelType = com.postsaimanager.core.model.AiModelType.LOCAL,
                title = "Chat",
                lastMessageAt = now,
                createdAt = now,
            ),
        )
        conversations.addMessage(
            AiMessage(
                id = "u1",
                conversationId = "conv-1",
                role = MessageRole.USER,
                content = "What's the weather?",
                createdAt = now,
            ),
        )
        conversations.addMessage(
            AiMessage(
                id = "a1",
                conversationId = "conv-1",
                role = MessageRole.ASSISTANT,
                content = "It's rain",
                createdAt = now,
                incomplete = true,
            ),
        )

        engine.response = "Sunny today."
        sendChatMessage("conv-1", documentId = null, text = "and tomorrow?").toList()

        val history = engine.lastSessionHistory
        assertThat(history.map { it.content }).doesNotContain("It's rain")
        assertThat(history.map { it.content }).contains("What's the weather?")
    }

    // ── 3.4 / 3.5: session priming visibility and the grounding-skip it enables ──

    @Test
    @DisplayName("PreparingConversation is emitted only when the session actually needs (re)priming")
    fun `preparing conversation is emitted only on an actual re-prime`() = runTest {
        engine.response = "hi"

        val first = sendChatMessage("conv-1", documentId = null, text = "first").toList()
        assertThat(first.any { it is ChatTurn.PreparingConversation }).isTrue()

        // Same conversation, nothing invalidated the session in between: no re-prime, so no
        // "Preparing conversation…" the second time.
        val second = sendChatMessage("conv-1", documentId = null, text = "second").toList()
        assertThat(second.any { it is ChatTurn.PreparingConversation }).isFalse()
    }

    @Test
    @DisplayName("a config change that forces a reload re-primes the session on the very next send")
    fun `config change re-primes the session`() = runTest {
        engine.response = "hi"
        sendChatMessage("conv-1", documentId = null, text = "first").toList()

        // Same shape as the accelerator-change test above: nothing about the conversation
        // changed, but the engine now needs a different config, which the fake's `load`
        // treats as a reload — see FakeAiEngine.load's doc on why it invalidates the session.
        models.accelerator = Accelerator.GPU

        val turns = sendChatMessage("conv-1", documentId = null, text = "second").toList()
        assertThat(turns.any { it is ChatTurn.PreparingConversation }).isTrue()
    }

    @Test
    @DisplayName("grounding is not rebuilt once the session is already primed for this conversation")
    fun `grounding is skipped when the session is already primed`() = runTest {
        engine.response = "hi"

        sendChatMessage("conv-1", documentId = null, text = "first").toList()
        val firstGrounding = engine.ensureChatSessionCalls[0].second
        assertThat(firstGrounding).isNotEmpty()

        sendChatMessage("conv-1", documentId = null, text = "second").toList()
        // ensureChatSession is still called every send (it is a cheap no-op on the real
        // engines when nothing changed) — but SendChatMessageUseCase itself never bothered
        // rebuilding the grounding text for it, since the session was already primed.
        val secondGrounding = engine.ensureChatSessionCalls[1].second
        assertThat(secondGrounding).isEmpty()
    }

    @Test
    @DisplayName("an engine busy with another caller surfaces why chat is waiting")
    fun `busy engine surfaces a waiting reason`() = runTest {
        engine.isBusy = true
        engine.response = "hi"

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        val preparing = turns.filterIsInstance<ChatTurn.PreparingModel>().first()
        assertThat(preparing.reason).isNotNull()
    }

    @Test
    @DisplayName("an idle engine reports no waiting reason")
    fun `idle engine reports no waiting reason`() = runTest {
        engine.isBusy = false
        engine.response = "hi"

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        val preparing = turns.filterIsInstance<ChatTurn.PreparingModel>().first()
        assertThat(preparing.reason).isNull()
    }

    // ── 3.3: token-aware history budgeting ──

    @Test
    @DisplayName("long history is trimmed by estimated tokens, in a chunk, not to the exact limit")
    fun `history is trimmed by token budget`() = runTest {
        // A small context window makes the character budget easy to reason about:
        // (contextTokens - DEFAULT_REPLY_RESERVE - TEMPLATE_OVERHEAD_TOKENS)
        //     .coerceAtLeast(MIN_CONTEXT_TOKENS) * CHARS_PER_TOKEN
        // = (300 - 512 - 128).coerceAtLeast(256) * 3 = 256 * 3 = 768 total budget characters.
        models.contextTokens = 300
        val now = System.currentTimeMillis()
        conversations.createConversation(
            com.postsaimanager.core.model.AiConversation(
                id = "conv-1",
                documentId = null,
                aiModelId = null,
                modelType = com.postsaimanager.core.model.AiModelType.LOCAL,
                title = "Chat",
                lastMessageAt = now,
                createdAt = now,
            ),
        )
        // Six 200-character turns = 1200 characters of eligible history, comfortably over the
        // budget above (768 chars total, minus the standalone grounding prompt's own length).
        repeat(6) { i ->
            conversations.addMessage(
                AiMessage(
                    id = "u$i",
                    conversationId = "conv-1",
                    role = MessageRole.USER,
                    content = "turn$i:" + "x".repeat(193),
                    createdAt = now + i,
                ),
            )
        }

        val totalBudgetChars = ((300 - BuildChatContextUseCase.DEFAULT_REPLY_RESERVE -
            BuildChatContextUseCase.TEMPLATE_OVERHEAD_TOKENS)
            .coerceAtLeast(BuildChatContextUseCase.MIN_CONTEXT_TOKENS)) *
            BuildChatContextUseCase.CHARS_PER_TOKEN
        val standaloneGroundingLength = buildChatContext(null, 300).text.length
        val historyBudgetChars = totalBudgetChars - standaloneGroundingLength

        engine.response = "final answer"
        sendChatMessage("conv-1", documentId = null, text = "final question").toList()

        val history = engine.lastSessionHistory
        val totalChars = history.sumOf { it.content.length }

        // Never exceeds the budget...
        assertThat(totalChars).isAtMost(historyBudgetChars)
        // ...oldest turns are the ones dropped...
        assertThat(history.map { it.content }).doesNotContain("turn0:" + "x".repeat(193))
        assertThat(history.map { it.content }).contains("turn5:" + "x".repeat(193))
        // ...and at least one turn survives — the cut keeps recent context, it does not empty
        // history outright.
        assertThat(history).isNotEmpty()
    }

    @Test
    @DisplayName("history well within budget is not trimmed at all")
    fun `history within budget is untouched`() = runTest {
        engine.response = "hi"
        val now = System.currentTimeMillis()
        conversations.createConversation(
            com.postsaimanager.core.model.AiConversation(
                id = "conv-1",
                documentId = null,
                aiModelId = null,
                modelType = com.postsaimanager.core.model.AiModelType.LOCAL,
                title = "Chat",
                lastMessageAt = now,
                createdAt = now,
            ),
        )
        conversations.addMessage(
            AiMessage(
                id = "u1",
                conversationId = "conv-1",
                role = MessageRole.USER,
                content = "A short question",
                createdAt = now,
            ),
        )

        sendChatMessage("conv-1", documentId = null, text = "another short question").toList()

        assertThat(engine.lastSessionHistory.map { it.content }).contains("A short question")
    }

    // ── 4.1/4.2: retrieval-augmented grounding ──

    /** A document long enough that [BuildChatContextUseCase] switches it to retrieval mode. */
    private fun seedOverflowingDocument(documents: FakeDocumentRepository, id: String = "d1") {
        documents.seed(testDocument(id = id))
        documents.seedPages(
            id,
            DocumentPage(
                id = "$id-p1", documentId = id, pageNumber = 1,
                imagePath = "/tmp/$id-p1.jpg",
                ocrText = "Sehr geehrte Damen und Herren, ".repeat(2_000),
                width = 0, height = 0,
            ),
        )
    }

    @Test
    @DisplayName("retrieved passages are injected into the turn's prompt, never into the stable grounding")
    fun `retrieved passages go into the user turn only`() = runTest {
        val documents = FakeDocumentRepository()
        seedOverflowingDocument(documents)
        val chunks = FakeDocumentChunkRepository()
        chunks.seed(
            testChunk(
                "c1", documentId = "d1", ordinal = 0, pageNumber = 3,
                text = "The deadline is 31.01.2026.",
            ),
        )
        val useCase = SendChatMessageUseCase(
            conversations, engine, models,
            BuildChatContextUseCase(documents, FakeProfileRepository()),
            RetrieveChunksUseCase(chunks, FakeEmbeddingService()),
        )

        engine.response = "The deadline is 31.01.2026."
        useCase("conv-1", documentId = "d1", text = "When is the deadline?").toList()

        // The passage and its page label reached the engine...
        assertThat(engine.lastChatUserText).contains("The deadline is 31.01.2026.")
        assertThat(engine.lastChatUserText).contains("[p.3]")
        assertThat(engine.lastChatUserText).contains("When is the deadline?")

        // ...but the persisted user message is the raw question, untouched.
        val persisted = conversations.getMessages("conv-1").first()
        val userMessage = persisted.first { it.role == MessageRole.USER }
        assertThat(userMessage.content).isEqualTo("When is the deadline?")
        assertThat(userMessage.content).doesNotContain("The deadline is")
    }

    @Test
    @DisplayName("the grounding prefix stays stable across turns even as retrieved passages change")
    fun `grounding is unchanged turn to turn while passages differ`() = runTest {
        val documents = FakeDocumentRepository()
        seedOverflowingDocument(documents)
        val chunks = FakeDocumentChunkRepository()
        chunks.seed(
            testChunk("c1", documentId = "d1", ordinal = 0, pageNumber = 1, text = "Widerspruch eingelegt."),
            testChunk("c2", documentId = "d1", ordinal = 1, pageNumber = 2, text = "IBAN: DE89370400440532013000"),
        )
        val useCase = SendChatMessageUseCase(
            conversations, engine, models,
            BuildChatContextUseCase(documents, FakeProfileRepository()),
            RetrieveChunksUseCase(chunks, FakeEmbeddingService()),
        )

        engine.response = "answer one"
        useCase("conv-1", documentId = "d1", text = "Was steht zum Widerspruch im Dokument?").toList()
        val firstGrounding = engine.ensureChatSessionCalls[0].second
        assertThat(firstGrounding).isNotEmpty()

        engine.response = "answer two"
        // Long and self-contained on purpose: FollowUpRetrievalQuery (4.4) would otherwise
        // widen this turn's retrieval query with the previous turn's text — exactly what a
        // *short* follow-up needs, but not what this test is pinning (retrieval scoping to
        // this turn's own passages while the grounding prefix stays stable).
        useCase("conv-1", documentId = "d1", text = "What is the IBAN number mentioned in the document?").toList()

        // Second turn: already primed, so the (stable) grounding sent to the engine is
        // empty — see the 3.5 test above. The point here is that this holds *even though*
        // this turn's retrieval surfaces a completely different passage than the first.
        val secondGrounding = engine.ensureChatSessionCalls[1].second
        assertThat(secondGrounding).isEmpty()
        assertThat(engine.lastChatUserText).contains("IBAN: DE89370400440532013000")
        assertThat(engine.lastChatUserText).doesNotContain("Widerspruch eingelegt")
    }

    @Test
    @DisplayName("no matching passages falls back to sending the raw question")
    fun `empty retrieval sends the raw text`() = runTest {
        val documents = FakeDocumentRepository()
        seedOverflowingDocument(documents)
        val useCase = SendChatMessageUseCase(
            conversations, engine, models,
            BuildChatContextUseCase(documents, FakeProfileRepository()),
            RetrieveChunksUseCase(FakeDocumentChunkRepository(), FakeEmbeddingService()),
        )

        engine.response = "hi"
        useCase("conv-1", documentId = "d1", text = "hello").toList()

        assertThat(engine.lastChatUserText).isEqualTo("hello")
    }

    @Test
    @DisplayName("a document that fits whole never triggers retrieval")
    fun `no retrieval when the document fits in the grounding`() = runTest {
        val documents = FakeDocumentRepository()
        documents.seed(testDocument(id = "d1"))
        documents.seedPages(
            "d1",
            DocumentPage(
                id = "p1", documentId = "d1", pageNumber = 1,
                imagePath = "/tmp/p1.jpg", ocrText = "Kurzer Brief.", width = 0, height = 0,
            ),
        )
        val chunks = FakeDocumentChunkRepository()
        chunks.seed(testChunk("c1", documentId = "d1", text = "Should never be injected."))
        val useCase = SendChatMessageUseCase(
            conversations, engine, models,
            BuildChatContextUseCase(documents, FakeProfileRepository()),
            RetrieveChunksUseCase(chunks, FakeEmbeddingService()),
        )

        engine.response = "hi"
        useCase("conv-1", documentId = "d1", text = "hello").toList()

        assertThat(engine.lastChatUserText).isEqualTo("hello")
    }

    @Test
    @DisplayName("standalone chat labels a passage with its document's title")
    fun `standalone passages are labelled with the document title`() = runTest {
        val documents = FakeDocumentRepository()
        documents.seed(testDocument(id = "d1", title = "Bescheid über Leistungen"))
        val chunks = FakeDocumentChunkRepository()
        chunks.seed(
            testChunk("c1", documentId = "d1", pageNumber = 2, text = "Wichtiger Hinweis zur Frist."),
        )
        val useCase = SendChatMessageUseCase(
            conversations, engine, models,
            BuildChatContextUseCase(documents, FakeProfileRepository()),
            RetrieveChunksUseCase(chunks, FakeEmbeddingService()),
        )

        engine.response = "hi"
        useCase("conv-1", documentId = null, text = "Was ist die Frist?").toList()

        assertThat(engine.lastChatUserText).contains("[Bescheid über Leistungen, p.2]")
    }

    @Test
    @DisplayName("the completed turn exposes the passages it was grounded in, for future citation persistence")
    fun `complete turn exposes its retrieved sources`() = runTest {
        val documents = FakeDocumentRepository()
        seedOverflowingDocument(documents)
        val chunks = FakeDocumentChunkRepository()
        chunks.seed(testChunk("c1", documentId = "d1", pageNumber = 1, text = "Die Antwort auf diese Frage."))
        val useCase = SendChatMessageUseCase(
            conversations, engine, models,
            BuildChatContextUseCase(documents, FakeProfileRepository()),
            RetrieveChunksUseCase(chunks, FakeEmbeddingService()),
        )

        engine.response = "answer"
        val turns = useCase("conv-1", documentId = "d1", text = "Frage").toList()

        val complete = turns.filterIsInstance<ChatTurn.Complete>().single()
        assertThat(complete.sources.map { it.chunk.id }).containsExactly("c1")
    }

    // ── 5.1: regenerate the latest assistant reply ──

    @Test
    @DisplayName("regenerating replaces the latest reply without duplicating the user's message")
    fun `regenerate replaces the latest reply without duplicating the question`() = runTest {
        engine.response = "first answer"
        sendChatMessage("conv-1", documentId = null, text = "What's the weather?").toList()

        val beforeUserCount = conversations.getMessages("conv-1").first()
            .count { it.role == MessageRole.USER }

        engine.response = "second, better answer"
        val turns = sendChatMessage.regenerateLastReply("conv-1", documentId = null).toList()

        val persisted = conversations.getMessages("conv-1").first()
        val afterUserCount = persisted.count { it.role == MessageRole.USER }
        val assistantMessages = persisted.filter { it.role == MessageRole.ASSISTANT }

        // No duplicate user message.
        assertThat(afterUserCount).isEqualTo(beforeUserCount)
        // Old answer gone, new answer persisted.
        assertThat(assistantMessages).hasSize(1)
        assertThat(assistantMessages.single().content).isEqualTo("second, better answer")
        assertThat(persisted.first { it.role == MessageRole.USER }.content)
            .isEqualTo("What's the weather?")

        // Session was invalidated (so it re-primes) and the new reply was committed (after
        // the original one, from the first send).
        assertThat(engine.chatSessionWasReset).isTrue()
        assertThat(engine.committedReplies).containsExactly("first answer", "second, better answer")

        val complete = turns.filterIsInstance<ChatTurn.Complete>().single()
        assertThat(complete.message.content).isEqualTo("second, better answer")
    }

    @Test
    @DisplayName("regenerate re-primes the session from what remains in the DB")
    fun `regenerate re-primes the session`() = runTest {
        engine.response = "first answer"
        sendChatMessage("conv-1", documentId = null, text = "hi there").toList()

        engine.response = "second answer"
        sendChatMessage.regenerateLastReply("conv-1", documentId = null).toList()

        // A re-prime happened (the reset session forces ensureChatSession to prime again).
        val primedCalls = sendChatMessage.let { engine.ensureChatSessionCalls }
        assertThat(primedCalls.last().second).isNotEmpty()
        // The history that was re-primed with does not contain the deleted reply.
        assertThat(engine.lastSessionHistory.map { it.content }).doesNotContain("first answer")
    }

    @Test
    @DisplayName("regenerating with nothing to regenerate fails instead of crashing")
    fun `regenerate with no assistant reply fails gracefully`() = runTest {
        val turns = sendChatMessage.regenerateLastReply("conv-none", documentId = null).toList()

        assertThat(turns).hasSize(1)
        assertThat(turns.single()).isInstanceOf(ChatTurn.Failed::class.java)
    }
}
