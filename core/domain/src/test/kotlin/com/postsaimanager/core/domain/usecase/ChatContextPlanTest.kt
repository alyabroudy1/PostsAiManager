package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ToolExchange
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeChatImageStore
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Plan 16, A1: the transcript is not the model's context. A conversation built from what is stored gets the document card and the
 * last exchange, never the older turns; a live session keeps its turns; the size of a rebuilt prefix does not grow with the chat.
 */
class ChatContextPlanTest {

    private val documents = FakeDocumentRepository().apply { seed(testDocument(id = "d1")) }
    private val buildChatContext = BuildChatContextUseCase(documents, FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor())
    private val buildModelContext = BuildModelContextUseCase(buildChatContext)

    private fun message(i: Int, role: MessageRole, text: String = "message $i", trace: List<ToolExchange> = emptyList()) =
        AiMessage(id = "m$i", conversationId = "conv-d1", role = role, content = text, createdAt = i.toLong(), toolTrace = trace)

    /** [exchanges] question/answer pairs, oldest first. */
    private fun transcript(exchanges: Int, lastReplyTrace: List<ToolExchange> = emptyList()): List<AiMessage> = buildList {
        repeat(exchanges) { n ->
            add(message(2 * n, MessageRole.USER, "question $n"))
            add(message(2 * n + 1, MessageRole.ASSISTANT, "answer $n", if (n == exchanges - 1) lastReplyTrace else emptyList()))
        }
    }

    private suspend fun plan(transcript: List<AiMessage>, documentMemory: List<String> = emptyList()) =
        buildModelContext(documentId = "d1", contextTokens = 4096, historyTokens = 4096, transcript = transcript, documentMemory = documentMemory)

    @Test
    @DisplayName("a long history replays only its last exchange")
    fun `long history replays only the tail`() = runTest {
        val plan = plan(transcript(exchanges = 30))

        assertThat(plan.tail.map { it.content }).containsExactly("question 29", "answer 29").inOrder()
        assertThat(plan.tail.map { it.role }).containsExactly(AiChatRole.USER, AiChatRole.ASSISTANT).inOrder()
        assertThat(plan.tailMessages.map { it.id }).containsExactly("m58", "m59").inOrder()
    }

    @Test
    @DisplayName("a tail with a tool call keeps the call with its reply, for the engine to replay as the stub")
    fun `tail keeps the tool trace`() = runTest {
        val loadSkill = ToolExchange("load_skill", """{"skill_name":"schedule-reminder"}""", """{"skill_instructions":"1. Do it."}""")
        val runIntent = ToolExchange("run_intent", """{"intent":"schedule_notification"}""", """{"status":"proposed"}""")

        val plan = plan(transcript(exchanges = 5, lastReplyTrace = listOf(loadSkill, runIntent)))

        assertThat(plan.tail.last().toolTrace).containsExactly(loadSkill, runIntent).inOrder()
        // Older replies' calls are not replayed: they are not in the tail at all.
        assertThat(plan.tail.sumOf { it.toolTrace.size }).isEqualTo(2)
    }

    @Test
    @DisplayName("a new chat has no tail")
    fun `new chat has no tail`() = runTest {
        val plan = plan(emptyList())

        assertThat(plan.tail).isEmpty()
        assertThat(plan.tailMessages).isEmpty()
        assertThat(plan.card.text).isNotEmpty()
    }

    @Test
    @DisplayName("an unanswered question and a stopped reply are not a tail")
    fun `incomplete reply is not a tail`() = runTest {
        val stopped = listOf(message(0, MessageRole.USER), message(1, MessageRole.ASSISTANT).copy(incomplete = true))

        assertThat(plan(stopped).tail).isEmpty()
        assertThat(plan(transcript(2) + stopped).tail.map { it.content }).containsExactly("question 1", "answer 1").inOrder()
    }

    @Test
    @DisplayName("the rebuilt prefix is as big as for a short chat plus the tail, whatever the chat length")
    fun `prefix size is constant`() = runTest {
        val empty = plan(emptyList())
        val long = plan(transcript(200))
        // The same last exchange, with nothing before it.
        val short = plan(transcript(200).takeLast(2))

        assertThat(long.cardChars).isEqualTo(empty.cardChars)
        assertThat(long.tailChars).isEqualTo(short.tailChars)
        assertThat(long.totalChars).isEqualTo(empty.totalChars + long.tailChars)
        // The tail is one exchange, not the chat.
        assertThat(long.tail).hasSize(2)
    }

    @Test
    @DisplayName("the document memory is a capped slot of the card: empty now, bounded when filled")
    fun `memory slot is capped`() = runTest {
        val empty = plan(emptyList())
        assertThat(empty.documentMemoryChars).isEqualTo(0)

        val notes = List(40) { "Note number $it: the user said something durable about this letter." }
        val filled = plan(emptyList(), documentMemory = notes)

        assertThat(filled.documentMemoryChars).isAtMost(BuildModelContextUseCase.MEMORY_CAP_CHARS)
        assertThat(filled.documentMemoryChars).isGreaterThan(0)
        assertThat(filled.card.text).contains("Note number 0")
        assertThat(filled.card.text).doesNotContain("Note number 39")
        assertThat(filled.cardChars).isEqualTo(empty.cardChars)
    }

    @Test
    @DisplayName("an oversized message is cut in the replay, so one long reply cannot grow the prefix")
    fun `long tail messages are capped`() = runTest {
        val huge = listOf(message(0, MessageRole.USER, "q"), message(1, MessageRole.ASSISTANT, "x".repeat(50_000)))

        val plan = plan(huge)

        assertThat(plan.tail.last().content.length).isAtMost(ContinuityTail.MAX_MESSAGE_CHARS + 1)
    }

    @Test
    @DisplayName("the number of messages above the tail is what the divider is about")
    fun `older count`() {
        assertThat(ContinuityTail.olderCount(emptyList())).isEqualTo(0)
        assertThat(ContinuityTail.olderCount(transcript(1))).isEqualTo(0)
        assertThat(ContinuityTail.olderCount(transcript(5))).isEqualTo(8)
    }

    // ── Applied by the use case that opens and rebuilds a conversation ──

    private val engine = FakeAiEngine()
    private val models = FakeActiveModelProvider()
    private val conversations = FakeConversationRepository()
    private val sessions = ChatSessionTrackerClock()
    private val send = SendChatMessageUseCase(
        conversations,
        engine,
        models,
        buildChatContext,
        RetrieveChunksUseCase(FakeDocumentChunkRepository(), FakeEmbeddingService(), ObserveChatVisibleDocumentsUseCase(documents)),
        buildModelContext = buildModelContext,
        sessions = sessions.tracker,
    )

    private suspend fun seedConversation(exchanges: Int) {
        conversations.createConversation(
            AiConversation(id = "conv-d1", documentId = "d1", aiModelId = null, modelType = AiModelType.LOCAL, title = "t", lastMessageAt = 1, createdAt = 1),
        )
        transcript(exchanges).forEach { conversations.addMessage(it) }
    }

    @Test
    @DisplayName("opening a chat with a long history primes the card and the last exchange (the warm-up)")
    fun `warm-up replays the tail`() = runTest {
        seedConversation(exchanges = 25)

        send.primeConversation("conv-d1", "d1")

        assertThat(engine.lastSessionHistory.map { it.content }).containsExactly("question 24", "answer 24").inOrder()
    }

    @Test
    @DisplayName("within a live session nothing is rebuilt: every turn stays in the engine")
    fun `live session keeps all turns`() = runTest {
        seedConversation(exchanges = 25)
        engine.response = "ok"
        send.primeConversation("conv-d1", "d1")

        val first = send("conv-d1", "d1", "one more").toList()
        val second = send("conv-d1", "d1", "and another").toList()
        val third = send("conv-d1", "d1", "and a third").toList()

        // The engine's session was primed once (at the open); the three sends only added their turns to it.
        assertThat(engine.isChatSessionPrimed("conv-d1")).isTrue()
        assertThat(listOf(first, second, third).flatten().filterIsInstance<ChatTurn.PreparingConversation>()).isEmpty()
        assertThat(engine.committedReplies).hasSize(3)
        assertThat(conversations.getMessages("conv-d1").first()).hasSize(50 + 6)
    }

    @Test
    @DisplayName("after the visit ended, the next send rebuilds from the card and the last exchange, not from the chat")
    fun `send after leaving rebuilds from the plan`() = runTest {
        seedConversation(exchanges = 3)
        engine.response = "ok"
        send("conv-d1", "d1", "first of this visit").toList()
        send("conv-d1", "d1", "second of this visit").toList()

        sessions.tracker.leave("conv-d1")
        val turns = send("conv-d1", "d1", "back again").toList()

        // The conversation is primed again, and only with the last finished exchange of the transcript.
        assertThat(turns.filterIsInstance<ChatTurn.PreparingConversation>()).hasSize(1)
        assertThat(engine.lastSessionHistory.map { it.content }).containsExactly("second of this visit", "ok").inOrder()
    }

    @Test
    @DisplayName("ten idle minutes end the visit: the next send is a new session over the plan")
    fun `send after idle rebuilds from the plan`() = runTest {
        seedConversation(exchanges = 3)
        engine.response = "ok"
        send("conv-d1", "d1", "first").toList()

        sessions.advance(11 * 60_000L)
        val turns = send("conv-d1", "d1", "much later").toList()

        assertThat(turns.filterIsInstance<ChatTurn.PreparingConversation>()).hasSize(1)
        assertThat(engine.lastSessionHistory.map { it.content }).containsExactly("first", "ok").inOrder()
    }

    @Test
    @DisplayName("a new chat has an empty tail: the next conversation is the card alone")
    fun `new chat then send has no tail`() = runTest {
        seedConversation(exchanges = 10)
        engine.response = "ok"
        send.primeConversation("conv-d1", "d1")
        assertThat(engine.lastSessionHistory).isNotEmpty()

        StartNewChatUseCase(conversations, engine, FakeChatImageStore(), sessions.tracker)("conv-d1")
        send("conv-d1", "d1", "fresh start").toList()

        assertThat(engine.lastSessionHistory).isEmpty()
    }

    @Test
    @DisplayName("the llama.cpp chat engine is given the same plan as the LiteRT one: the card plus the last exchange")
    fun `the same rule for every engine`() = runTest {
        seedConversation(exchanges = 12)
        engine.response = "ok"

        send("conv-d1", "d1", "hello").toList()

        val (_, system, history) = engine.ensureChatSessionCalls.last()
        assertThat(system).contains("Current document")
        assertThat(history.map { it.content }).containsExactly("question 11", "answer 11").inOrder()
    }

    /** A tracker over a clock the test moves. */
    private class ChatSessionTrackerClock {
        private var now = 1_000_000L
        val tracker = ChatSessionTracker { now }
        fun advance(ms: Long) {
            now += ms
        }
    }
}
