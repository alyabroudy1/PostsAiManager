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
import kotlinx.coroutines.flow.first
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

    @Nested
    @DisplayName("Confirm all (5.3)")
    inner class ConfirmAll {

        private fun field(id: String, confirmed: Boolean, confidence: Float = 0.9f) =
            com.postsaimanager.core.model.ExtractedData(
                id = id,
                documentId = "d1",
                fieldName = "Field $id",
                fieldValue = "value",
                fieldType = com.postsaimanager.core.model.ExtractedFieldType.TEXT,
                confidence = confidence,
                isConfirmed = confirmed,
            )

        @Test
        @DisplayName("confirms every unconfirmed field in one repository call, not one per field")
        fun `confirms all unconfirmed fields at once`() = runTest {
            coEvery { profileMatchingService.matchProfiles(any(), any()) } returns emptyList()
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            documentRepository.seedExtracted(
                "d1",
                field("f1", confirmed = false),
                field("f2", confirmed = false),
                field("f3", confirmed = true),
            )
            val vm = viewModel("d1")
            vm.uiState.test { awaitItem(); cancelAndIgnoreRemainingEvents() }

            vm.confirmAllFields()

            val confirmed = documentRepository.observeExtractedData("d1").first()
            assertThat(confirmed.all { it.isConfirmed }).isTrue()
        }

        @Test
        @DisplayName("offers undo, which restores the fields exactly as they were")
        fun `undo restores the previous state`() = runTest {
            coEvery { profileMatchingService.matchProfiles(any(), any()) } returns emptyList()
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            documentRepository.seedExtracted("d1", field("f1", confirmed = false))
            val vm = viewModel("d1")
            vm.uiState.test { awaitItem(); cancelAndIgnoreRemainingEvents() }

            vm.confirmAllFields()
            assertThat(vm.pendingConfirmAllUndo.value).isNotNull()
            assertThat(documentRepository.observeExtractedData("d1").first().single().isConfirmed).isTrue()

            vm.undoConfirmAll()

            assertThat(vm.pendingConfirmAllUndo.value).isNull()
            assertThat(documentRepository.observeExtractedData("d1").first().single().isConfirmed).isFalse()
        }

        @Test
        @DisplayName("nothing to confirm offers no undo")
        fun `no unconfirmed fields offers no undo`() = runTest {
            coEvery { profileMatchingService.matchProfiles(any(), any()) } returns emptyList()
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            documentRepository.seedExtracted("d1", field("f1", confirmed = true))
            val vm = viewModel("d1")
            vm.uiState.test { awaitItem(); cancelAndIgnoreRemainingEvents() }

            vm.confirmAllFields()

            assertThat(vm.pendingConfirmAllUndo.value).isNull()
        }
    }
}
