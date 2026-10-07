package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.skills.JsSkillExecutor
import com.postsaimanager.core.domain.skills.JsSkillRequest
import com.postsaimanager.core.domain.usecase.AttachChatImageUseCase
import com.postsaimanager.core.domain.usecase.BuildChatContextUseCase
import com.postsaimanager.core.domain.usecase.ChatImageSupportUseCase
import com.postsaimanager.core.domain.usecase.ChatSessionEnd
import com.postsaimanager.core.domain.usecase.ChatSessionEnded
import com.postsaimanager.core.domain.usecase.ChatSessionTracker
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveChatVisibleDocumentsUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.domain.usecase.RetrieveChunksUseCase
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.domain.usecase.StartNewChatUseCase
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.FormFillingFlag
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import com.postsaimanager.core.testing.FakeChatImageStore
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeFormFillRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.feature.chat.skills.JsSkillRelay
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The chat screen as a session (plan 16, A1): it ends on leave and on 10 idle minutes, and the divider sits above the tail. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSessionTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val id = "conv-standalone"
    private var now = 1_000_000L
    private val tracker = ChatSessionTracker { now }
    private val documents = FakeDocumentRepository()
    private val conversations = FakeConversationRepository()
    private val chatEngine = FakeChatEngine(name = "chat", supportsThinking = false)
    private val models = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM)
    private val store = FakeChatImageStore()
    private val sendChat = SendChatMessageUseCase(
        conversations,
        chatEngine,
        models,
        BuildChatContextUseCase(documents, FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor()),
        RetrieveChunksUseCase(FakeDocumentChunkRepository(), FakeEmbeddingService(), ObserveChatVisibleDocumentsUseCase(documents)),
        sessions = tracker,
    )
    private val engine = mockk<AiEngine>(relaxed = true) {
        every { state } returns MutableStateFlow(ModelLoadState.Idle)
        every { isBusy } returns false
    }
    private val noSandbox = object : JsSkillExecutor {
        override suspend fun run(request: JsSkillRequest): String = "{}"
    }

    private fun viewModel() = ChatViewModel(
        savedStateHandle = SavedStateHandle(),
        sendChatMessage = sendChat,
        conversationRepository = conversations,
        documentRepository = documents,
        engine = engine,
        observeInstalledModels = mockk(relaxed = true),
        selectActiveModel = mockk(relaxed = true),
        observeInferenceSettings = mockk(relaxed = true),
        updateInferenceSetting = mockk(relaxed = true),
        resetInferenceSettings = mockk(relaxed = true),
        unblockGpu = mockk(relaxed = true),
        getDocumentPreview = GetDocumentPreviewUseCase(documents, FakeDocumentChunkRepository()),
        observeSuggestedQuestions = ObserveSuggestedQuestionsUseCase(documents),
        formFill = mockk(relaxed = true),
        formFills = FakeFormFillRepository(),
        searchModelHint = testSearchModelHint(),
        startNewChat = StartNewChatUseCase(conversations, chatEngine, store, tracker),
        attachChatImage = AttachChatImageUseCase(store),
        chatImageSupport = ChatImageSupportUseCase(models),
        jsSkillRelay = JsSkillRelay(chatEngine, noSandbox),
        formFillingFlag = FormFillingFlag.OFF,
        sessions = tracker,
    )

    private suspend fun seed(exchanges: Int, from: Int = 0) {
        if (from == 0) {
            conversations.createConversation(
                AiConversation(id = id, documentId = null, aiModelId = null, modelType = AiModelType.LOCAL, title = "t", lastMessageAt = 1, createdAt = 1),
            )
        }
        repeat(exchanges) { n ->
            val i = from + 2 * n
            conversations.addMessage(AiMessage(id = "m$i", conversationId = id, role = MessageRole.USER, content = "q$i", createdAt = i.toLong()))
            conversations.addMessage(AiMessage(id = "m${i + 1}", conversationId = id, role = MessageRole.ASSISTANT, content = "a$i", createdAt = i + 1L))
        }
    }

    private fun clear(vm: ChatViewModel) {
        val onCleared = ChatViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(vm)
    }

    @Test
    @DisplayName("the divider sits above the last exchange, stays there while the live session grows, and moves when the session ends idle")
    fun `divider position and idle end`() = runTest {
        seed(exchanges = 3)
        val events = mutableListOf<ChatSessionEnded>()
        val collector = backgroundScope.launch(main.dispatcher) { tracker.ended.collect { events += it } }
        val vm = viewModel()

        assertThat(vm.uiState.value.contextStartMessageId).isEqualTo("m4")

        // The live session grows below the line.
        seed(exchanges = 1, from = 6)
        assertThat(vm.uiState.value.contextStartMessageId).isEqualTo("m4")
        assertThat(events).isEmpty()

        // Ten idle minutes: the session ends, with its event, and the context will start at the newest exchange.
        now += 11 * 60_000L
        main.dispatcher.scheduler.advanceTimeBy(11 * 60_000L)
        assertThat(events).containsExactly(ChatSessionEnded(id, ChatSessionEnd.IDLE, startedAt = 1_000_000L))
        assertThat(vm.uiState.value.contextStartMessageId).isEqualTo("m6")
        collector.cancel()
    }

    @Test
    @DisplayName("leaving the chat screen ends the session and fires the event")
    fun `leaving ends the session`() = runTest {
        seed(exchanges = 2)
        val events = mutableListOf<ChatSessionEnded>()
        val collector = backgroundScope.launch(main.dispatcher) { tracker.ended.collect { events += it } }
        val vm = viewModel()
        tracker.begin(id)

        clear(vm)

        assertThat(events).containsExactly(ChatSessionEnded(id, ChatSessionEnd.LEFT, startedAt = 1_000_000L))
        assertThat(tracker.isLive(id)).isFalse()
        collector.cancel()
    }

    @Test
    @DisplayName("a chat with only one exchange has no older messages to mark")
    fun `no tail start beyond the chat`() = runTest {
        seed(exchanges = 1)

        val vm = viewModel()

        // The tail is the whole chat: the id is set, and the timeline shows no divider for it (see ChatContextDividerTest).
        assertThat(vm.uiState.value.contextStartMessageId).isEqualTo("m0")
    }
}
