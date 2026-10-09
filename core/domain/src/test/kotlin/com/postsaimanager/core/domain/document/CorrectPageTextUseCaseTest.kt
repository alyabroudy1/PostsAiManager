package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.layout.PlainTextBlocks
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.PageTextSource
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** A corrected page text is the user's, reaches the chat's passages, and is read as plain running text. */
class CorrectPageTextUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val chunks = FakeDocumentChunkRepository()
    private val correct = CorrectPageTextUseCase(documents, IndexDocumentUseCase(chunks, FakeEmbeddingService()))

    private suspend fun seed() {
        documents.seed(testDocument(id = "d1"))
        documents.seedPages(
            "d1",
            DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = "Rechnung Nr. 5 vom 3.10.2O26"),
            DocumentPage("p2", "d1", 2, "file:///2.jpg", ocrText = "Bitte zahlen Sie bis Freitag."),
        )
    }

    private suspend fun page(number: Int) = (documents.getDocumentPages("d1") as PamResult.Success).data.single { it.pageNumber == number }

    @Test
    fun `the corrected text is stored as the user's on that page only`() = runTest {
        seed()

        val result = correct("d1", 1, "  Rechnung Nr. 5 vom 3.10.2026 ")

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(page(1).ocrText).isEqualTo("Rechnung Nr. 5 vom 3.10.2026")
        assertThat(page(1).textSource).isEqualTo(PageTextSource.USER)
        assertThat(page(2).textSource).isEqualTo(PageTextSource.OCR)
        assertThat(page(2).ocrText).isEqualTo("Bitte zahlen Sie bis Freitag.")
    }

    @Test
    fun `the passages of the letter are made again from the corrected pages`() = runTest {
        seed()

        correct("d1", 1, "Rechnung Nr. 5 vom 3.10.2026")

        val stored = chunks.getForDocument("d1")
        assertThat(stored.joinToString(" ") { it.text }).contains("3.10.2026")
        assertThat(stored.joinToString(" ") { it.text }).doesNotContain("2O26")
        assertThat(stored.map { it.pageNumber }.toSet()).containsExactly(1, 2)
    }

    @Test
    fun `a page that is not there is refused`() = runTest {
        seed()

        assertThat(correct("d1", 9, "x")).isInstanceOf(PamResult.Error::class.java)
        assertThat(page(1).textSource).isEqualTo(PageTextSource.OCR)
    }

    @Test
    fun `corrected text is given to a reading as plain lines, top to bottom, one column`() {
        val blocks = PlainTextBlocks.of("Jobcenter Musterstadt\nPostfach 1\n\nBescheid\nBitte zahlen Sie bis Freitag.")

        assertThat(blocks.map { it.text }).containsExactly("Jobcenter Musterstadt\nPostfach 1", "Bescheid\nBitte zahlen Sie bis Freitag.").inOrder()
        val lines = blocks.flatMap { it.lines }
        assertThat(lines.map { it.text }).containsExactly("Jobcenter Musterstadt", "Postfach 1", "Bescheid", "Bitte zahlen Sie bis Freitag.").inOrder()
        // Every line spans the page's width and sits below the one before it: no column, no window, no field to find.
        assertThat(lines.map { it.bounds.left }.toSet()).hasSize(1)
        assertThat(lines.map { it.bounds.right }.toSet()).hasSize(1)
        assertThat(lines.zipWithNext().all { (a, b) -> a.bounds.bottom <= b.bounds.top + 0.0001f }).isTrue()
        assertThat(lines.all { it.bounds.top >= 0f && it.bounds.bottom <= 1f }).isTrue()
    }

    @Test
    fun `a text with no line gives no blocks`() {
        assertThat(PlainTextBlocks.of("  \n \n")).isEmpty()
    }
}
