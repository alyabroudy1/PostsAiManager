package com.postsaimanager.feature.documents

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.MoveDocumentToTrashUseCase
import com.postsaimanager.core.domain.document.RestoreDocumentUseCase
import com.postsaimanager.core.domain.document.list.DueFieldsActionHint
import com.postsaimanager.core.domain.document.list.IdentityPartyNameResolver
import com.postsaimanager.core.domain.document.list.MatchDocumentPeopleUseCase
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.model.DocumentDateChip
import com.postsaimanager.core.model.DocumentListStatus
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** The Documents screen's state is the list use case's rows, searched and failing the way the screen expects. */
@ExtendWith(MainDispatcherExtension::class)
class DocumentsViewModelTest {

    private val repository = FakeDocumentRepository()
    private val clock = Clock.fixed(LocalDate.of(2026, 9, 30).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private fun viewModel() = DocumentsViewModel(
        documentRepository = repository,
        moveToTrashUseCase = MoveDocumentToTrashUseCase(repository),
        restoreDocumentUseCase = RestoreDocumentUseCase(repository),
        observeDocumentListItems = ObserveDocumentListItemsUseCase(
            repository, IdentityPartyNameResolver(), DueFieldsActionHint(), clock, FakeProfileRepository(), MatchDocumentPeopleUseCase(),
        ),
        documentProcessor = FakeDocumentProcessor(),
    )

    private fun field(id: String, docId: String, slot: String, value: String, confidence: Float = 0.9f) = ExtractedData(
        id = id, documentId = docId, fieldName = slot, fieldValue = value, fieldType = ExtractedFieldType.TEXT,
        confidence = confidence, slotKey = slot,
    )

    @Test
    fun `no documents is Empty`() = runTest {
        viewModel().uiState.test {
            assertThat(awaitItem()).isEqualTo(DocumentsUiState.Empty)
        }
    }

    @Test
    fun `documents arrive as rows with the parties, status, date chip and thumbnail the use case built`() = runTest {
        repository.seed(testDocument(id = "d1", title = "Strom", status = DocumentStatus.EXTRACTED))
        repository.seedExtracted(
            "d1",
            field("f1", "d1", "sender", "Stadtwerke"),
            field("f2", "d1", "addressee", "Familie Beispiel"),
            field("f3", "d1", "due_date", "15.10.2026", confidence = 0.5f),
        )
        repository.seedPages("d1", DocumentPage(id = "p1", documentId = "d1", pageNumber = 1, imagePath = "/files/p1.jpg"))

        viewModel().uiState.test {
            val state = expectMostRecentItem() as DocumentsUiState.Success
            val row = state.documents.single()
            assertThat(row.id).isEqualTo("d1")
            assertThat(row.sender).isEqualTo("Stadtwerke")
            assertThat(row.addressee).isEqualTo("Familie Beispiel")
            assertThat(row.status).isEqualTo(DocumentListStatus.NeedsReview(1))
            assertThat(row.dateChip.kind).isEqualTo(DocumentDateChip.Kind.DUE)
            assertThat(row.openActionCount).isEqualTo(1)
            assertThat(row.firstPagePath).isEqualTo("/files/p1.jpg")
        }
    }

    @Test
    fun `the order the repository lists is kept`() = runTest {
        repository.seed(testDocument(id = "a"), testDocument(id = "b"), testDocument(id = "c"))
        viewModel().uiState.test {
            val state = expectMostRecentItem() as DocumentsUiState.Success
            assertThat(state.documents.map { it.id }).containsExactly("a", "b", "c").inOrder()
        }
    }

    @Test
    fun `searching narrows the rows and clearing brings them back`() = runTest {
        repository.seed(testDocument(id = "d1", title = "Rent"), testDocument(id = "d2", title = "Electricity"))
        val vm = viewModel()
        vm.uiState.test {
            assertThat((expectMostRecentItem() as DocumentsUiState.Success).documents).hasSize(2)
            vm.onSearchQueryChanged("rent")
            assertThat((expectMostRecentItem() as DocumentsUiState.Success).documents.map { it.id }).containsExactly("d1")
            vm.onSearchQueryChanged("")
            assertThat((expectMostRecentItem() as DocumentsUiState.Success).documents).hasSize(2)
        }
    }

    @Test
    fun `a failing read is an Error state`() = runTest {
        repository.throwOnObserve = IllegalStateException("db closed")
        viewModel().uiState.test {
            val state = expectMostRecentItem()
            assertThat(state).isEqualTo(DocumentsUiState.Error("db closed"))
        }
    }
}
