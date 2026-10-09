package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.PageChange
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Deleting and reordering pages: what refers to a page by its number follows its page, and the passages are made again. */
class EditPagesUseCasesTest {

    private val documents = FakeDocumentRepository()
    private val chunks = FakeDocumentChunkRepository()
    private val index = IndexDocumentUseCase(chunks, FakeEmbeddingService())
    private val delete = DeleteDocumentPageUseCase(documents, index)
    private val reorder = ReorderDocumentPagesUseCase(documents, index)

    private fun field(id: String, page: Int?) = ExtractedData(
        id = id, documentId = "d1", fieldName = id, fieldValue = "v$id", fieldType = ExtractedFieldType.TEXT, confidence = 0.9f,
        pageNumber = page, bbox = page?.let { TextBounds(0.1f, 0.1f, 0.2f, 0.2f) },
    )

    private suspend fun seed() {
        documents.seed(testDocument(id = "d1").copy(pageCount = 3))
        documents.seedPages(
            "d1",
            DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = "Erste Seite"),
            DocumentPage("p2", "d1", 2, "file:///2.jpg", ocrText = "Zweite Seite"),
            DocumentPage("p3", "d1", 3, "file:///3.jpg", ocrText = "Dritte Seite"),
        )
        documents.seedExtracted("d1", field("a", 1), field("b", 2), field("c", 3), field("none", null))
    }

    private suspend fun pages() = (documents.getDocumentPages("d1") as PamResult.Success).data
    private suspend fun fields() = documents.observeExtractedData("d1").first().associateBy { it.id }

    @Test
    fun `the page numbers a change gives are the one rule for everything that refers to a page`() {
        val delete = PageChange.Delete(2)
        assertThat(listOf(1, 2, 3).map(delete::newNumber)).containsExactly(1, null, 2).inOrder()
        val reorder = PageChange.Reorder(listOf(3, 1, 2))
        assertThat(listOf(1, 2, 3).map(reorder::newNumber)).containsExactly(2, 3, 1).inOrder()
        assertThat(reorder.newNumber(9)).isNull()
    }

    @Test
    fun `deleting a page renumbers the later ones, moves what refers to them, and keeps the text of what was read from it`() = runTest {
        seed()

        val result = delete("d1", 2)

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(pages().map { it.id to it.pageNumber }).containsExactly("p1" to 1, "p3" to 2).inOrder()
        val rows = fields()
        assertThat(rows.getValue("a").pageNumber).isEqualTo(1)
        assertThat(rows.getValue("c").pageNumber).isEqualTo(2)
        // Read from the deleted page: the text stays, the place does not.
        assertThat(rows.getValue("b").fieldValue).isEqualTo("vb")
        assertThat(rows.getValue("b").pageNumber).isNull()
        assertThat(rows.getValue("b").bbox).isNull()
        assertThat(rows.getValue("none").pageNumber).isNull()
        assertThat((documents.getDocumentById("d1") as PamResult.Success).data.pageCount).isEqualTo(2)
    }

    @Test
    fun `the passages are made again from the pages that are left, each with its new page number`() = runTest {
        seed()

        delete("d1", 1)

        val stored = chunks.getForDocument("d1")
        assertThat(stored.joinToString(" ") { it.text }).doesNotContain("Erste")
        assertThat(stored.map { it.pageNumber }.toSet()).containsExactly(1, 2)
        assertThat(stored.single { it.text.contains("Dritte") }.pageNumber).isEqualTo(2)
    }

    @Test
    fun `the last page of a letter cannot be deleted, nor a page that is not there`() = runTest {
        documents.seed(testDocument(id = "d1"))
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = "Eine"))

        assertThat(delete("d1", 1)).isInstanceOf(PamResult.Error::class.java)
        assertThat(delete("d1", 7)).isInstanceOf(PamResult.Error::class.java)
        assertThat(pages()).hasSize(1)
    }

    @Test
    fun `reordering renumbers the pages and moves what refers to them with them`() = runTest {
        seed()

        val result = reorder("d1", listOf(3, 1, 2))

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(pages().map { it.id }).containsExactly("p3", "p1", "p2").inOrder()
        assertThat(pages().map { it.pageNumber }).containsExactly(1, 2, 3).inOrder()
        val rows = fields()
        assertThat(rows.getValue("a").pageNumber).isEqualTo(2)
        assertThat(rows.getValue("b").pageNumber).isEqualTo(3)
        assertThat(rows.getValue("c").pageNumber).isEqualTo(1)
        assertThat(rows.getValue("a").bbox).isNotNull()
    }

    @Test
    fun `an order that is not every page once is refused, and the same order changes nothing`() = runTest {
        seed()

        assertThat(reorder("d1", listOf(1, 2))).isInstanceOf(PamResult.Error::class.java)
        assertThat(reorder("d1", listOf(1, 2, 2))).isInstanceOf(PamResult.Error::class.java)
        assertThat(reorder("d1", listOf(1, 2, 3))).isInstanceOf(PamResult.Success::class.java)

        assertThat(pages().map { it.id }).containsExactly("p1", "p2", "p3").inOrder()
    }

    @Test
    fun `moving a page by one place gives the new order, and null at the ends`() = runTest {
        seed()

        assertThat(reorder.moved("d1", 2, -1)).containsExactly(2, 1, 3).inOrder()
        assertThat(reorder.moved("d1", 2, 1)).containsExactly(1, 3, 2).inOrder()
        assertThat(reorder.moved("d1", 1, -1)).isNull()
        assertThat(reorder.moved("d1", 3, 1)).isNull()
    }
}
