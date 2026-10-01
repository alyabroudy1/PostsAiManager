package com.postsaimanager.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.domain.usecase.ObserveSuggestedQuestionsUseCase
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeFormFillRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testDocument
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Where the chat's starter questions come from: the three the model wrote for the document, never a
 * fixed list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class ChatSuggestedQuestionsTest {

    private val documents = FakeDocumentRepository()
    private val source = ObserveSuggestedQuestionsUseCase(documents)
    private val conversations = mockk<ConversationRepository> {
        every { getMessages(any()) } returns emptyFlow()
    }
    private val engine = mockk<AiEngine>(relaxed = true) {
        every { state } returns MutableStateFlow(ModelLoadState.Idle)
        every { isBusy } returns false
    }

    private fun viewModel(documentId: String?) = ChatViewModel(
        savedStateHandle = SavedStateHandle(if (documentId == null) emptyMap() else mapOf("documentId" to documentId)),
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
        observeSuggestedQuestions = source,
        formFill = mockk(relaxed = true),
        formFills = FakeFormFillRepository(),
    )

    private fun doc(id: String, type: String?, questions: List<String>, createdAt: Long, deletedAt: Long? = null) =
        testDocument(id = id, createdAt = createdAt, deletedAt = deletedAt).copy(
            extractionType = type,
            suggestedQuestions = questions,
        )

    private val qs = listOf("When is it due?", "How much is it?", "Who is it from?")

    @Test
    fun `a document chat offers that document's own three questions`() = runTest {
        documents.seed(doc("d1", "bill", qs, createdAt = 1), doc("d2", "bill", listOf("Other?"), createdAt = 2))

        assertThat(source.forDocument("d1").first()).isEqualTo(qs)
    }

    @Test
    fun `a document with no questions yet offers none, there is no static fallback`() = runTest {
        documents.seed(doc("d1", null, emptyList(), createdAt = 1))

        assertThat(source.forDocument("d1").first()).isEmpty()
    }

    @Test
    fun `the view model exposes the document's questions`() = runTest {
        documents.seed(doc("d1", "authority_tax", qs, createdAt = 1))
        val vm = viewModel("d1")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.suggestedQuestions.collect { } }

        assertThat(vm.suggestedQuestions.value).isEqualTo(qs)
        job.cancel()
    }

    @Test
    fun `the all-documents chat shows the questions of the most recent actionable document`() = runTest {
        documents.seed(
            doc("old", "bill", listOf("Old?"), createdAt = 1),
            doc("new", "reminder_dunning", qs, createdAt = 5),
            // newer, but a receipt asks nothing of its reader
            doc("receipt", "receipt", listOf("What did I buy?"), createdAt = 9),
        )

        assertThat(source.forAllDocuments().first()).isEqualTo(qs)
    }

    @Test
    fun `the all-documents chat never surfaces a health letter's questions`() = runTest {
        documents.seed(doc("h", "health", listOf("What is my diagnosis?"), createdAt = 9))

        assertThat(source.forAllDocuments().first()).isEmpty()
    }

    @Test
    fun `the all-documents chat never surfaces the questions of a sensitive topic, whatever the family`() = runTest {
        documents.seed(
            doc("h", "official_letter", listOf("What is my diagnosis?"), createdAt = 9).copy(topics = listOf("health")),
            doc("ok", "bill", qs, createdAt = 1),
        )

        assertThat(source.forAllDocuments().first()).isEqualTo(qs)
    }

    @Test
    fun `a migrated document with a family id gets starter questions too`() = runTest {
        documents.seed(doc("m", "invoice_bill", qs, createdAt = 1))

        assertThat(source.forAllDocuments().first()).isEqualTo(qs)
    }

    @Test
    fun `the all-documents chat skips a document without questions and a trashed one`() = runTest {
        documents.seed(
            doc("empty", "bill", emptyList(), createdAt = 9),
            doc("trashed", "bill", listOf("Trashed?"), createdAt = 8, deletedAt = 100),
            doc("ok", "school", qs, createdAt = 2),
        )

        assertThat(source.forAllDocuments().first()).isEqualTo(qs)
    }

    @Test
    fun `the all-documents chat shows nothing when no document qualifies`() = runTest {
        assertThat(source.forAllDocuments().first()).isEmpty()
        documents.seed(doc("o", "other", qs, createdAt = 1), doc("u", null, qs, createdAt = 2))
        assertThat(source.forAllDocuments().first()).isEmpty()
    }

    @Test
    fun `the view model in an all-documents chat has no static list`() = runTest {
        val vm = viewModel(null)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.suggestedQuestions.collect { } }

        assertThat(vm.suggestedQuestions.value).isEmpty()
        job.cancel()
    }
}
