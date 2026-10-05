package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.testChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GetDocumentPreviewUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val chunks = FakeDocumentChunkRepository()
    private val useCase = GetDocumentPreviewUseCase(documents, chunks)

    private val bounds = TextBounds(0.1f, 0.2f, 0.9f, 0.3f)

    private fun page(n: Int, text: String? = null) = DocumentPage(
        id = "p$n",
        documentId = "d1",
        pageNumber = n,
        imagePath = "file:///pages/$n.jpg",
        ocrBlocks = text?.let { listOf(OcrBlock(it, bounds, 0.9f)) }.orEmpty(),
    )

    private fun seed(trashedAt: Long? = null) {
        documents.seed(
            Document(
                id = "d1", title = "Bescheid", sourceType = SourceType.CAMERA,
                createdAt = 1, modifiedAt = 1, deletedAt = trashedAt,
            ),
        )
        documents.seedPages(
            "d1",
            page(2, "Anderer Text auf Seite zwei"),
            page(1, "Die Zahlung ist fällig am 15.03.2026"),
        )
    }

    @Test
    fun `returns every page in order and highlights only the cited one`() = runTest {
        seed()
        chunks.seed(testChunk("d1#0", "d1", 0, "Die Zahlung ist fällig am 15.03.2026", pageNumber = 1))

        val preview = useCase("d1", "d1#0")!!

        assertThat(preview.title).isEqualTo("Bescheid")
        assertThat(preview.pages.map { it.pageNumber }).containsExactly(1, 2).inOrder()
        assertThat(preview.pages[0].highlights).containsExactly(bounds)
        assertThat(preview.pages[1].highlights).isEmpty()
    }

    @Test
    fun `an unknown chunk gives pages without highlights`() = runTest {
        seed()
        val preview = useCase("d1", "missing")!!
        assertThat(preview.pages.flatMap { it.highlights }).isEmpty()
    }

    @Test
    fun `no chunk id gives plain pages`() = runTest {
        seed()
        assertThat(useCase("d1")!!.pages).hasSize(2)
    }

    @Test
    fun `a chunk that matches nothing highlights nothing`() = runTest {
        seed()
        chunks.seed(testChunk("d1#0", "d1", 0, "Kindergeld bewilligt", pageNumber = 1))
        assertThat(useCase("d1", "d1#0")!!.pages.flatMap { it.highlights }).isEmpty()
    }

    @Test
    fun `trashed and missing documents give null`() = runTest {
        seed(trashedAt = 5)
        assertThat(useCase("d1")).isNull()
        assertThat(useCase("nope")).isNull()
        assertThat(useCase.forField("d1", 1, bounds)).isNull()
    }

    @Test
    fun `forField marks the box on its page only`() = runTest {
        seed()
        val preview = useCase.forField("d1", page = 2, bbox = bounds)!!
        assertThat(preview.pages.map { it.highlights }).containsExactly(emptyList<TextBounds>(), listOf(bounds)).inOrder()
    }

    @Test
    fun `forField without a box gives plain pages`() = runTest {
        seed()
        assertThat(useCase.forField("d1", page = 1, bbox = null)!!.pages.flatMap { it.highlights }).isEmpty()
        assertThat(useCase.forField("d1", page = null, bbox = bounds)!!.pages.flatMap { it.highlights }).isEmpty()
    }
}
