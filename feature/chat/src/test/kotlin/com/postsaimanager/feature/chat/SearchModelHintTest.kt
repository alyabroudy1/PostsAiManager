package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeFormFillRepository
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.domain.usecase.SearchModelHint
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** The one-time "answers can show their sources once the search model is installed" hint: when it is owed, and that a dismissal sticks. */
@ExtendWith(MainDispatcherExtension::class)
class SearchModelHintTest {

    private val documents = FakeDocumentRepository()
    private val conversations = mockk<ConversationRepository> { every { getMessages(any()) } returns emptyFlow() }
    private val engine = mockk<AiEngine>(relaxed = true) {
        every { state } returns MutableStateFlow(ModelLoadState.Idle)
        every { isBusy } returns false
    }
    private val preferences = FakeUserPreferencesRepository()
    private val embedder = FakeEmbeddingService().apply { isReady = false }
    private val hint = SearchModelHint(preferences, embedder)

    private fun viewModel() = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("documentId" to "d1")),
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
        searchModelHint = hint,
    )

    @Test
    fun `the hint is owed while the search model is missing and not dismissed`() = runTest {
        assertThat(hint.observe().first()).isTrue()
    }

    @Test
    fun `no hint when the search model is installed`() = runTest {
        embedder.isReady = true
        assertThat(hint.observe().first()).isFalse()
    }

    @Test
    fun `dismissing is stored in the preferences and ends the hint for good, even though the model is still missing`() = runTest {
        hint.dismiss()
        assertThat(preferences.current.searchModelHintDismissed).isTrue()
        assertThat(hint.observe().first()).isFalse()
    }

    @Test
    fun `a new reading sees a search model installed since`() = runTest {
        assertThat(hint.observe().first()).isTrue()
        embedder.isReady = true
        assertThat(hint.observe().first()).isFalse()
    }

    @Test
    fun `the view model shows the hint, hides it after a dismissal, and shows it after nothing else`() = runTest {
        val vm = viewModel()
        val collect = vm.searchModelHintVisible.launchIn(backgroundScope)
        assertThat(vm.searchModelHintVisible.first { it }).isTrue()
        vm.dismissSearchModelHint()
        assertThat(vm.searchModelHintVisible.first { !it }).isFalse()
        assertThat(preferences.current.searchModelHintDismissed).isTrue()
        collect.cancel()
    }

    @Test
    fun `the view model drops the hint when the search model was installed and the screen refreshes`() = runTest {
        val vm = viewModel()
        val collect = vm.searchModelHintVisible.launchIn(backgroundScope)
        assertThat(vm.searchModelHintVisible.first { it }).isTrue()
        embedder.isReady = true
        vm.refreshSearchModelHint()
        assertThat(vm.searchModelHintVisible.first { !it }).isFalse()
        collect.cancel()
    }
}
