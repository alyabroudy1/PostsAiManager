package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.skills.JsSkillExecutor
import com.postsaimanager.core.domain.skills.JsSkillRequest
import com.postsaimanager.core.domain.usecase.AttachChatImageUseCase
import com.postsaimanager.core.domain.usecase.BuildChatContextUseCase
import com.postsaimanager.core.domain.usecase.ChatImageSupportUseCase
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveChatVisibleDocumentsUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.domain.usecase.RetrieveChunksUseCase
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.domain.usecase.StartNewChatUseCase
import com.postsaimanager.core.model.FormFillingFlag
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.model.ToolExchange
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** Pictures and the new chat, through the real use cases: what the screen shows after each. */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class ChatViewModelPiecesTest {

    private val documents = FakeDocumentRepository()
    private val conversations = FakeConversationRepository()
    private val chatEngine = FakeChatEngine(name = "chat", supportsThinking = false)
    private val models = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM, supportsImages = true)
    private val store = FakeChatImageStore(unreadable = setOf("content://not-a-picture"))
    private val sendChat = SendChatMessageUseCase(
        conversations,
        chatEngine,
        models,
        BuildChatContextUseCase(documents, FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor()),
        RetrieveChunksUseCase(FakeDocumentChunkRepository(), FakeEmbeddingService(), ObserveChatVisibleDocumentsUseCase(documents)),
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
        startNewChat = StartNewChatUseCase(conversations, chatEngine, store),
        attachChatImage = AttachChatImageUseCase(store),
        chatImageSupport = ChatImageSupportUseCase(models),
        jsSkillRelay = JsSkillRelay(chatEngine, noSandbox),
        formFillingFlag = FormFillingFlag.OFF,
    )

    @Test
    @DisplayName("the attach button is offered only when the chat model declares image input")
    fun `image support is read from the model`() = runTest {
        val vm = viewModel()
        assertThat(vm.uiState.value.imageInputSupported).isFalse()

        vm.refreshImageSupport()
        assertThat(vm.uiState.value.imageInputSupported).isTrue()

        models.supportsImages = false
        vm.refreshImageSupport()
        assertThat(vm.uiState.value.imageInputSupported).isFalse()
    }

    @Test
    @DisplayName("pictures are attached, can be removed, and are sent and shown with the next message, then cleared")
    fun `attach send and show`() = runTest {
        val vm = viewModel()
        vm.attachImage("content://photo/1")
        vm.attachImage("content://photo/2")
        vm.attachImage("content://not-a-picture")

        val attached = vm.uiState.value.attachments
        assertThat(attached).hasSize(2)
        // The unreadable file did not attach, and said so.
        assertThat(vm.uiState.value.error?.messageRes).isEqualTo(R.string.chat_error_image_unreadable)

        vm.removeAttachment(attached[0])
        assertThat(vm.uiState.value.attachments).containsExactly(attached[1])

        chatEngine.reply = "It is a letter."
        vm.sendMessage("What is this?")

        assertThat(chatEngine.requests.single().imagePaths).containsExactly(attached[1])
        assertThat(vm.uiState.value.attachments).isEmpty()
        val user = vm.uiState.value.messages.first { it.isUser }
        assertThat(user.images).containsExactly(attached[1])
        assertThat(vm.uiState.value.messages.first { !it.isUser }.images).isEmpty()
    }

    @Test
    fun `no more than ten pictures are attached to one message`() = runTest {
        val vm = viewModel()
        repeat(12) { vm.attachImage("content://photo/$it") }
        assertThat(vm.uiState.value.attachments).hasSize(10)
    }

    @Test
    @DisplayName("the stored tool calls of a reply reach the screen as steps, with the webview a script asked for")
    fun `tool calls become steps`() = runTest {
        val vm = viewModel()
        conversations.addMessage(
            com.postsaimanager.core.model.AiMessage(
                id = "a1",
                conversationId = "conv-standalone",
                role = MessageRole.ASSISTANT,
                content = "Done.",
                createdAt = 2,
                toolTrace = listOf(
                    ToolExchange("load_skill", """{"skill_name":"text-spinner"}""", """{"skill_instructions":"..."}"""),
                    ToolExchange("run_js", """{"skill_name":"text-spinner","script_name":"index.html","data":"{}"}""", """{"status":"succeeded"}""",
                        """{"webview":{"url":"text-spinner/assets/webview.html","aspectRatio":1.0}}"""),
                ),
            ),
        )

        val steps = vm.uiState.value.messages.single().toolSteps
        assertThat(steps).hasSize(2)
        assertThat(steps[1].webview?.url).isEqualTo("text-spinner/assets/webview.html")
    }

    @Test
    @DisplayName("a new chat clears the visible history and the pictures, and the next message starts a fresh conversation")
    fun `new chat`() = runTest {
        val vm = viewModel()
        vm.attachImage("content://photo/1")
        chatEngine.reply = "First answer."
        vm.sendMessage("First question")
        vm.attachImage("content://photo/2")
        assertThat(vm.uiState.value.messages).hasSize(2)

        vm.newChat()

        assertThat(vm.uiState.value.messages).isEmpty()
        assertThat(vm.uiState.value.attachments).isEmpty()
        assertThat(store.stored["conv-standalone"]).isNull()
        assertThat(chatEngine.isChatSessionPrimed("conv-standalone")).isFalse()
        // It stays deleted: nothing comes back from the repository.
        assertThat(conversations.getConversationById("conv-standalone")).isInstanceOf(com.postsaimanager.core.common.result.PamResult.Error::class.java)

        chatEngine.reply = "Second answer."
        vm.sendMessage("A new question")
        assertThat(vm.uiState.value.messages.map { it.text }).containsExactly("A new question", "Second answer.").inOrder()
    }
}
