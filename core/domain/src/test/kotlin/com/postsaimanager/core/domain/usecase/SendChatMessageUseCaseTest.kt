package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
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
    private val sendChatMessage = SendChatMessageUseCase(conversations, engine, models, buildChatContext)

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
    @DisplayName("a stream that never closes its think block yields a friendly no-answer message")
    fun `unclosed thinking produces a friendly fallback answer`() = runTest {
        engine.response = "<think>still reasoning and the stream just stops here"

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        val complete = turns.filterIsInstance<ChatTurn.Complete>().single()
        assertThat(complete.message.thinking)
            .isEqualTo("still reasoning and the stream just stops here")
        assertThat(complete.message.content).contains("did not produce an answer")
        assertThat(turns.filterIsInstance<ChatTurn.Token>()).isEmpty()
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
}
