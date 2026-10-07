package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.usecase.BuildChatContextUseCase
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveChatVisibleDocumentsUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.domain.usecase.RetrieveChunksUseCase
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.model.FormFillingFlag
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeFormFillRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** A failed send and its Retry, through the real send use case: one question in the chat, one answer, nothing doubled. */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class ChatViewModelRetryTest {

    private val documents = FakeDocumentRepository()
    private val conversations = FakeConversationRepository()
    private val chatEngine = FakeChatEngine(name = "chat", supportsThinking = false)
    private val sendChat = SendChatMessageUseCase(
        conversations,
        chatEngine,
        FakeActiveModelProvider(),
        BuildChatContextUseCase(documents, FakeProfileRepository()),
        RetrieveChunksUseCase(FakeDocumentChunkRepository(), FakeEmbeddingService(), ObserveChatVisibleDocumentsUseCase(documents)),
    )
    private val engine = mockk<AiEngine>(relaxed = true) {
        every { state } returns MutableStateFlow(ModelLoadState.Idle)
        every { isBusy } returns false
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
        startNewChat = io.mockk.mockk(relaxed = true),
        attachChatImage = io.mockk.mockk(relaxed = true),
        chatImageSupport = io.mockk.mockk(relaxed = true),
        jsSkillRelay = io.mockk.mockk(relaxed = true),
        formFillingFlag = FormFillingFlag.OFF,
    )

    @Test
    @DisplayName("a send that fails to load the model, then Retry: one user bubble and one answer, not a second copy of the question")
    fun `retry resends the same message`() = runTest {
        val vm = viewModel()
        chatEngine.failNextLoadWith = "The GPU engine could not start"
        chatEngine.reply = "The answer."

        vm.sendMessage("How much is it?")
        assertThat(vm.uiState.value.error).isNotNull()
        assertThat(vm.uiState.value.messages.count { it.isUser }).isEqualTo(1)

        vm.retry()

        val messages = vm.uiState.value.messages
        assertThat(vm.uiState.value.error).isNull()
        assertThat(messages.count { it.isUser }).isEqualTo(1)
        assertThat(messages.count { !it.isUser }).isEqualTo(1)
        assertThat(messages.first { !it.isUser }.text).isEqualTo("The answer.")
        // The failed attempt never reached the model's session, and the answer did exactly once.
        assertThat(chatEngine.sent).containsExactly("How much is it?")
        assertThat(chatEngine.committed).containsExactly("The answer.")
    }
}
