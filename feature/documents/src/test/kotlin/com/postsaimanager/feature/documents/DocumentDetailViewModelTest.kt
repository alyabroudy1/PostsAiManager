package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.DocumentExporter
import com.postsaimanager.core.domain.document.EntityProposalService
import com.postsaimanager.core.domain.document.GetDocumentDetailUseCase
import com.postsaimanager.core.domain.document.ProfileMatchingService
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.FakeTimelineRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testDocument
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import com.postsaimanager.core.model.DocumentStatus

/**
 * Tests for [DocumentDetailViewModel]'s processing lifecycle.
 *
 * The pipeline is no longer run inline on this screen — it is enqueued as background work
 * that survives navigation (documentation/07-document-pipeline.md §7). These pin the two
 * behaviours that boundary depends on: a `NEW` document enqueues itself once on open, and a
 * `FAILED` document's retry enqueues with `force = true`.
 */
@ExtendWith(MainDispatcherExtension::class)
class DocumentDetailViewModelTest {

    private val documentRepository = FakeDocumentRepository()
    private val profileRepository = FakeProfileRepository()
    private val timelineRepository = FakeTimelineRepository()
    private val documentProcessor = FakeDocumentProcessor()
    private val profileMatchingService = mockk<ProfileMatchingService>()
    private val entityProposalService = mockk<EntityProposalService> {
        every { pendingProposals(any()) } returns flowOf(emptyList())
    }
    private val documentExporter = mockk<DocumentExporter>()

    private fun viewModel(documentId: String = "d1") = DocumentDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("documentId" to documentId)),
        getDocumentDetailUseCase = GetDocumentDetailUseCase(documentRepository, timelineRepository),
        documentRepository = documentRepository,
        profileRepository = profileRepository,
        documentProcessor = documentProcessor,
        profileMatchingService = profileMatchingService,
        entityProposalService = entityProposalService,
        documentExporter = documentExporter,
    )

    @Nested
    @DisplayName("Opening a document")
    inner class Opening {

        @Test
        @DisplayName("a NEW document enqueues processing exactly once")
        fun `opening a NEW document enqueues it`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.NEW))
            val vm = viewModel("d1")

            vm.uiState.test {
                awaitItem() // Success
                cancelAndIgnoreRemainingEvents()
            }

            assertThat(documentProcessor.enqueueCalls).hasSize(1)
            assertThat(documentProcessor.enqueueCalls.single())
                .isEqualTo(FakeDocumentProcessor.EnqueueCall("d1", force = false))
        }

        @Test
        @DisplayName("an already-EXTRACTED document does not auto-enqueue")
        fun `opening an EXTRACTED document does not enqueue`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            val vm = viewModel("d1")

            vm.uiState.test {
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            assertThat(documentProcessor.enqueueCalls).isEmpty()
        }
    }

    @Nested
    @DisplayName("Manual retry / reprocess")
    inner class ManualRetry {

        @Test
        @DisplayName("startProcessing(force = true) enqueues with force")
        fun `retry enqueues with force`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.FAILED))
            val vm = viewModel("d1")
            vm.uiState.test {
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            documentProcessor.enqueueCalls.clear()

            vm.startProcessing(force = true)

            assertThat(documentProcessor.enqueueCalls).hasSize(1)
            assertThat(documentProcessor.enqueueCalls.single())
                .isEqualTo(FakeDocumentProcessor.EnqueueCall("d1", force = true))
        }
    }

    @Nested
    @DisplayName("Processing progress")
    inner class Progress {

        @Test
        @DisplayName("progress for a different document is not shown on this screen")
        fun `unrelated document progress is filtered out`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            val vm = viewModel("d1")
            vm.uiState.test {
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            documentProcessor.emit(
                com.postsaimanager.core.model.ProcessingState.Running(
                    documentId = "other-doc",
                    stage = com.postsaimanager.core.model.ProcessingStage.READ,
                    progress = 0.1f,
                ),
            )

            assertThat(vm.processingProgress.value)
                .isEqualTo(com.postsaimanager.core.model.ProcessingState.Idle)
        }
    }
}
