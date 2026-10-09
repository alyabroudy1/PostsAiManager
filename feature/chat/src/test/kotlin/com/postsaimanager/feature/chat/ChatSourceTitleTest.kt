package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.MessageSource
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeFormFillRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testDocument
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** A citation chip of the all-documents chat names its document by the document's title now: a rename shows on the chip. */
@ExtendWith(MainDispatcherExtension::class)
class ChatSourceTitleTest {

    private val documents = FakeDocumentRepository()
    private val conversations = FakeConversationRepository()
    private val engine = mockk<AiEngine>(relaxed = true) {
        every { state } returns MutableStateFlow(ModelLoadState.Idle)
        every { isBusy } returns false
    }

    private fun viewModel(documentId: String?) = ChatViewModel(
        savedStateHandle = SavedStateHandle(buildMap { documentId?.let { put("documentId", it) } }),
        sendChatMessage = mockk(relaxed = true),
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
        startNewChat = mockk(relaxed = true),
        attachChatImage = mockk(relaxed = true),
        chatImageSupport = mockk(relaxed = true),
        jsSkillRelay = mockk(relaxed = true),
    )

    private suspend fun answerCiting(conversationId: String) {
        conversations.addMessage(
            AiMessage(
                id = "m1", conversationId = conversationId, role = MessageRole.ASSISTANT, content = "It is due on Friday.", createdAt = 1L,
                sources = listOf(MessageSource("d1", 2, "c1")),
            ),
        )
    }

    @Test
    fun `a renamed document shows its new title on the chip of the all-documents chat`() = runTest {
        documents.seed(testDocument(id = "d1", title = "Bescheid"))
        answerCiting(ChatViewModel.conversationIdFor(null))
        val vm = viewModel(documentId = null)
        assertThat(vm.uiState.value.messages.single().sources.single().title).isEqualTo("Bescheid")

        documents.renameDocument("d1", "Mein Bescheid vom Jobcenter")

        assertThat(vm.uiState.value.messages.single().sources.single().title).isEqualTo("Mein Bescheid vom Jobcenter")
    }

    @Test
    fun `a document-scoped chat names no document on its chips, renamed or not`() = runTest {
        documents.seed(testDocument(id = "d1", title = "Bescheid"))
        answerCiting(ChatViewModel.conversationIdFor("d1"))
        val vm = viewModel(documentId = "d1")

        documents.renameDocument("d1", "Mein Bescheid")

        assertThat(vm.uiState.value.messages.single().sources.single().title).isNull()
    }
}
