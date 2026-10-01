package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeFormFillRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testDocument
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class ChatViewModelPreviewTest {

    private val documents = FakeDocumentRepository()
    private val conversations = mockk<ConversationRepository> {
        every { getMessages(any()) } returns emptyFlow()
    }
    private val engine = mockk<AiEngine>(relaxed = true) {
        every { state } returns MutableStateFlow(ModelLoadState.Idle)
        every { isBusy } returns false
    }

    private fun viewModel() = ChatViewModel(
        savedStateHandle = SavedStateHandle(),
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
    )

    private fun seedThreePages() {
        documents.seed(testDocument(id = "d1", title = "Bescheid"))
        documents.seedPages(
            "d1",
            DocumentPage("p1", "d1", 1, "file:///1.jpg"),
            DocumentPage("p2", "d1", 2, "file:///2.jpg"),
            DocumentPage("p3", "d1", 3, "file:///3.jpg"),
        )
    }

    @Test
    fun `opening a chip loads the document positioned on the cited page`() {
        seedThreePages()
        val vm = viewModel()
        assertThat(vm.preview.value).isNull()

        vm.openPreview(ChatSource("d1", pageNumber = 3, title = null))

        val state = vm.preview.value!!
        assertThat(state.loading).isFalse()
        assertThat(state.preview!!.pages).hasSize(3)
        assertThat(state.initialPageIndex).isEqualTo(2)
    }

    @Test
    fun `closing clears the preview`() {
        seedThreePages()
        val vm = viewModel()
        vm.openPreview(ChatSource("d1", pageNumber = 2, title = null))

        vm.closePreview()

        assertThat(vm.preview.value).isNull()
    }

    @Test
    fun `a document that cannot be previewed says so instead of crashing`() {
        val vm = viewModel()

        vm.openPreview(ChatSource("gone", pageNumber = 1, title = null))

        assertThat(vm.preview.value!!.unavailable).isTrue()
    }

    @Test
    fun `a deleted source never opens`() {
        seedThreePages()
        val vm = viewModel()

        vm.openPreview(ChatSource("d1", pageNumber = 1, title = null, documentDeleted = true))

        assertThat(vm.preview.value).isNull()
    }

    @Test
    fun `a page number outside the document falls back to the first page`() {
        seedThreePages()
        val vm = viewModel()

        vm.openPreview(ChatSource("d1", pageNumber = 9, title = null))

        assertThat(vm.preview.value!!.initialPageIndex).isEqualTo(0)
    }
}
