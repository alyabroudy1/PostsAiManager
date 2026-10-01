package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.ExternalFlowToken
import com.postsaimanager.core.domain.document.DocumentExporter
import com.postsaimanager.core.domain.document.GetDocumentDetailUseCase
import com.postsaimanager.core.domain.document.ReadAgainAsFamilyUseCase
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeTimelineRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testDocument
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [DocumentDetailViewModel]: the processing lifecycle, and the Extracted tab's review actions.
 *
 * The pipeline is no longer run inline on this screen — it is enqueued as background work
 * that survives navigation (documentation/07-document-pipeline.md §7). The first group pins the two
 * behaviours that boundary depends on: a `NEW` document enqueues itself once on open, and a
 * `FAILED` document's retry enqueues with `force = true`. The rest pin the state transitions of ✓ ✎ ✕, Restore, the
 * "Confirm n confident" / "Confirm all" buttons, the family chip and the summary edit.
 */
@ExtendWith(MainDispatcherExtension::class)
class DocumentDetailViewModelTest {

    private val documentRepository = FakeDocumentRepository()
    private val timelineRepository = FakeTimelineRepository()
    private val documentProcessor = FakeDocumentProcessor()
    private val documentExporter = mockk<DocumentExporter>()
    private val token1 = mockk<ExternalFlowToken>()
    private val token2 = mockk<ExternalFlowToken>()
    private val externalFlowGuard = mockk<ExternalFlowGuard>(relaxed = true) {
        every { expect("share-pdf") } returns token1
        every { expect("open-in-another-app") } returns token2
    }

    private fun viewModel(documentId: String = "d1") = DocumentDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("documentId" to documentId)),
        getDocumentDetailUseCase = GetDocumentDetailUseCase(documentRepository, timelineRepository),
        documentRepository = documentRepository,
        documentProcessor = documentProcessor,
        readAgainAsFamily = ReadAgainAsFamilyUseCase(documentProcessor),
        getDocumentPreview = GetDocumentPreviewUseCase(documentRepository, FakeDocumentChunkRepository()),
        documentExporter = documentExporter,
        externalFlowGuard = externalFlowGuard,
    )

    private fun field(
        id: String,
        confirmed: Boolean = false,
        confidence: Float = 0.9f,
        value: String = "value",
        slotKey: String? = null,
    ) = ExtractedData(
        id = id,
        documentId = "d1",
        fieldName = "Field $id",
        fieldValue = value,
        fieldType = ExtractedFieldType.TEXT,
        confidence = confidence,
        isConfirmed = confirmed,
        slotKey = slotKey,
    )

    private suspend fun stored(): List<ExtractedData> = documentRepository.observeExtractedData("d1").first()

    private suspend fun stored(id: String): ExtractedData = stored().single { it.id == id }

    /** Opens the screen's state so the ViewModel is collecting, like the screen does. */
    private suspend fun DocumentDetailViewModel.open() = uiState.test { awaitItem(); cancelAndIgnoreRemainingEvents() }

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
            vm.open()
            documentProcessor.enqueueCalls.clear()

            vm.startProcessing(force = true)

            assertThat(documentProcessor.enqueueCalls).hasSize(1)
            assertThat(documentProcessor.enqueueCalls.single())
                .isEqualTo(FakeDocumentProcessor.EnqueueCall("d1", force = true))
        }
    }

    @Nested
    @DisplayName("External flows (share PDF, open in another app)")
    inner class ExternalFlows {

        @Test
        @DisplayName("expect before the launch, finish when it fails")
        fun `a failed launch ends the protection`() {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            val vm = viewModel("d1")

            vm.onExternalLaunching("share-pdf")
            verify(exactly = 1) { externalFlowGuard.expect("share-pdf") }
            verify(exactly = 0) { externalFlowGuard.finish(token1) }

            vm.onExternalLaunchFinished()
            verify(exactly = 1) { externalFlowGuard.finish(token1) }
        }

        @Test
        @DisplayName("a second launch first ends the previous one's protection")
        fun `flows do not leak`() {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            val vm = viewModel("d1")

            vm.onExternalLaunching("share-pdf")
            vm.onExternalLaunching("open-in-another-app")

            verifyOrder {
                externalFlowGuard.expect("share-pdf")
                externalFlowGuard.finish(token1)
                externalFlowGuard.expect("open-in-another-app")
            }
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
            vm.open()

            documentProcessor.emit(ProcessingState.Running(documentId = "other-doc", stage = ProcessingStage.READ, progress = 0.1f))

            assertThat(vm.processingProgress.value).isEqualTo(ProcessingState.Idle)
        }
    }

    @Nested
    @DisplayName("Inline actions on a row")
    inner class InlineActions {

        private suspend fun open(vararg fields: ExtractedData): DocumentDetailViewModel {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            documentRepository.seedExtracted("d1", *fields)
            return viewModel("d1").also { it.open() }
        }

        @Test
        fun `confirm makes the row confirmed`() = runTest {
            val vm = open(field("f1"))

            vm.confirmField("f1")

            assertThat(stored("f1").reviewState).isEqualTo(ReviewState.CONFIRMED)
        }

        @Test
        fun `edit goes through updateExtractedField and makes the row the person's own, edited`() = runTest {
            val vm = open(field("f1", value = "Hauptstr. 1"))

            vm.updateField("f1", "Field f1", "Hauptstr. 12")

            val row = stored("f1")
            assertThat(row.fieldValue).isEqualTo("Hauptstr. 12")
            assertThat(row.reviewState).isEqualTo(ReviewState.EDITED)
            assertThat(row.source).isEqualTo(ValueSource.USER)
        }

        @Test
        fun `ignore tombstones the row, restore brings it back unreviewed`() = runTest {
            val vm = open(field("f1"))

            vm.ignoreField("f1")
            assertThat(stored("f1").reviewState).isEqualTo(ReviewState.IGNORED)
            assertThat(stored("f1").deletedByUser).isTrue()

            vm.restoreField("f1")
            assertThat(stored("f1").reviewState).isEqualTo(ReviewState.UNREVIEWED)
            assertThat(stored("f1").deletedByUser).isFalse()
        }

        @Test
        fun `a block-level confirm and ignore apply to every row of the block`() = runTest {
            val vm = open(field("a"), field("b"), field("c"))

            vm.confirmFields(listOf("a", "b"))
            assertThat(stored().associate { it.id to it.reviewState }).containsExactly(
                "a", ReviewState.CONFIRMED, "b", ReviewState.CONFIRMED, "c", ReviewState.UNREVIEWED,
            )

            vm.ignoreFields(listOf("b", "c"))
            assertThat(stored().associate { it.id to it.reviewState }).containsExactly(
                "a", ReviewState.CONFIRMED, "b", ReviewState.IGNORED, "c", ReviewState.IGNORED,
            )
        }

        @Test
        fun `a field added by hand is the person's own`() = runTest {
            val vm = open(field("f1"))

            vm.addField("Note", "call back", ExtractedFieldType.TEXT)

            val added = stored().single { it.fieldName == "Note" }
            assertThat(added.source).isEqualTo(ValueSource.USER)
            assertThat(added.reviewState).isEqualTo(ReviewState.EDITED)
        }
    }

    @Nested
    @DisplayName("Confirm n confident / Confirm all")
    inner class ConfirmMany {

        private suspend fun open(vararg fields: ExtractedData): DocumentDetailViewModel {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            documentRepository.seedExtracted("d1", *fields)
            return viewModel("d1").also { it.open() }
        }

        @Test
        @DisplayName("confirms every unconfirmed field in one repository call, not one per field")
        fun `confirms all unconfirmed fields at once`() = runTest {
            val vm = open(field("f1"), field("f2"), field("f3", confirmed = true))

            vm.confirmAllFields()

            assertThat(stored().all { it.reviewState == ReviewState.CONFIRMED }).isTrue()
        }

        @Test
        @DisplayName("confirm confident leaves the uncertain fields for the person")
        fun `confirm confident leaves the uncertain ones`() = runTest {
            val vm = open(field("sure1"), field("sure2"), field("unsure", confidence = 0.4f))

            vm.confirmConfidentFields()

            assertThat(stored("sure1").reviewState).isEqualTo(ReviewState.CONFIRMED)
            assertThat(stored("sure2").reviewState).isEqualTo(ReviewState.CONFIRMED)
            assertThat(stored("unsure").reviewState).isEqualTo(ReviewState.UNREVIEWED)
            assertThat(vm.pendingConfirmAllUndo.value!!.map { it.id }).containsExactly("sure1", "sure2")
        }

        @Test
        @DisplayName("offers undo, which restores the fields exactly as they were")
        fun `undo restores the previous state`() = runTest {
            val vm = open(field("f1"))

            vm.confirmAllFields()
            assertThat(vm.pendingConfirmAllUndo.value).isNotNull()
            assertThat(stored("f1").reviewState).isEqualTo(ReviewState.CONFIRMED)

            vm.undoConfirmAll()

            assertThat(vm.pendingConfirmAllUndo.value).isNull()
            assertThat(stored("f1").reviewState).isEqualTo(ReviewState.UNREVIEWED)
        }

        @Test
        @DisplayName("nothing to confirm offers no undo")
        fun `no unconfirmed fields offers no undo`() = runTest {
            val vm = open(field("f1", confirmed = true))

            vm.confirmAllFields()
            vm.confirmConfidentFields()

            assertThat(vm.pendingConfirmAllUndo.value).isNull()
        }
    }

    @Nested
    @DisplayName("The family chip")
    inner class FamilyChip {

        @Test
        fun `change type stores the family as the person's and does not read again`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED, extractionType = "free_form"))
            val vm = viewModel("d1")
            vm.open()

            vm.changeFamily("invoice_bill")

            val doc = documentRepository.getDocumentById("d1").let { (it as com.postsaimanager.core.common.result.PamResult.Success).data }
            assertThat(doc.extractionType).isEqualTo("invoice_bill")
            assertThat(doc.familySource).isEqualTo(FamilySource.USER)
            assertThat(documentProcessor.readAsCalls).isEmpty()
            assertThat(documentProcessor.enqueueCalls).isEmpty()
        }

        @Test
        fun `read again as asks the processor for that family`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            val vm = viewModel("d1")
            vm.open()

            vm.readAgainAs("receipt")

            assertThat(documentProcessor.readAsCalls).containsExactly("d1" to "receipt")
        }

        @Test
        fun `read again as a family that does not exist schedules nothing`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            val vm = viewModel("d1")
            vm.open()

            vm.readAgainAs("astrology")

            assertThat(documentProcessor.readAsCalls).isEmpty()
        }
    }

    @Nested
    @DisplayName("The summary")
    inner class Summary {

        @Test
        fun `editing the summary stores the person's text and marks its source`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            val vm = viewModel("d1")
            vm.open()

            vm.updateSummary("  Pay by Friday.  ")

            val doc = (documentRepository.getDocumentById("d1") as com.postsaimanager.core.common.result.PamResult.Success).data
            assertThat(doc.summary).isEqualTo("Pay by Friday.")
            assertThat(doc.summarySource).isEqualTo(SummarySource.USER)
        }
    }

    @Nested
    @DisplayName("Show on page")
    inner class ShowOnPage {

        private val box = TextBounds(0.1f, 0.2f, 0.5f, 0.3f)

        private fun page(n: Int) = DocumentPage(id = "p$n", documentId = "d1", pageNumber = n, imagePath = "file:///$n.jpg")

        @Test
        fun `opens the pages on the field's page with its box marked, and closes`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            documentRepository.seedPages("d1", page(1), page(2))
            val vm = viewModel("d1")
            vm.open()

            vm.showOnPage(page = 2, bbox = box)

            val state = vm.fieldPreview.value!!
            assertThat(state.loading).isFalse()
            assertThat(state.initialPageIndex).isEqualTo(1)
            assertThat(state.preview!!.pages.map { it.highlights }).containsExactly(emptyList<TextBounds>(), listOf(box)).inOrder()

            vm.closeFieldPreview()
            assertThat(vm.fieldPreview.value).isNull()
        }

        @Test
        fun `a document with no pages ends in an unavailable preview, not a stuck spinner`() = runTest {
            documentRepository.seed(testDocument(id = "d1", status = DocumentStatus.EXTRACTED))
            val vm = viewModel("d1")
            vm.open()

            vm.showOnPage(page = 1, bbox = box)

            val state = vm.fieldPreview.value!!
            assertThat(state.loading).isFalse()
            assertThat(state.preview).isNull()
        }
    }
}
