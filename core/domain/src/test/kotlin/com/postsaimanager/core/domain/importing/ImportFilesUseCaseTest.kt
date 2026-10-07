package com.postsaimanager.core.domain.importing

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.usecase.CreateDocumentFromPagesUseCase
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakePageImageSource
import com.postsaimanager.core.testing.stagedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The worker's flow with fakes: render, create through the scan path, clean up on success, failure and cancel. */
class ImportFilesUseCaseTest {

    private val source = FakePageImageSource()
    private val repository = FakeDocumentRepository()
    private val processor = FakeDocumentProcessor()
    private val useCase = ImportFilesUseCase(source, CreateDocumentFromPagesUseCase(repository, processor))

    private val pdf = stagedFile("a", ImportedKind.PDF, pages = 3)
    private val img1 = stagedFile("i1")
    private val img2 = stagedFile("i2")

    private fun request(vararg groups: ImportGroup, passwords: Map<String, String> = emptyMap()) =
        ImportRequest("batch-1", groups.toList(), passwords)

    @Test
    fun `a PDF becomes one PDF_IMPORT document with its pages in order, its hash and its original`() = runTest {
        val outcome = useCase(request(ImportGroup(listOf(pdf))))

        assertThat(outcome.problems).isEmpty()
        val document = repository.getDocuments().first().single()
        assertThat(outcome.documentIds).containsExactly(document.id)
        assertThat(document.sourceType).isEqualTo(SourceType.PDF_IMPORT)
        assertThat(document.pageCount).isEqualTo(3)
        assertThat(document.sourceHash).isEqualTo("sha-a")
        assertThat(document.originalFilePath).isEqualTo("file:///staged/a")
        assertThat(repository.getDocumentPages(document.id).let { (it as com.postsaimanager.core.common.result.PamResult.Success).data.map { p -> p.imagePath } })
            .containsExactly("file:///pages/a-1.jpg", "file:///pages/a-2.jpg", "file:///pages/a-3.jpg").inOrder()
    }

    @Test
    fun `the pipeline runs unchanged because the document is queued like a scan`() = runTest {
        val outcome = useCase(request(ImportGroup(listOf(pdf))))
        assertThat(processor.enqueueCalls.map { it.documentId }).containsExactlyElementsIn(outcome.documentIds)
    }

    @Test
    fun `images together are one UPLOAD document and keep no original`() = runTest {
        val outcome = useCase(request(ImportGroup(listOf(img1, img2))))

        val document = repository.getDocuments().first().single()
        assertThat(outcome.documentIds).hasSize(1)
        assertThat(document.sourceType).isEqualTo(SourceType.UPLOAD)
        assertThat(document.pageCount).isEqualTo(2)
        assertThat(document.originalFilePath).isNull()
    }

    @Test
    fun `a password reaches the render of its file`() = runTest {
        useCase(request(ImportGroup(listOf(pdf)), passwords = mapOf("a" to "secret")))
        assertThat(source.renderPasswords).containsExactly("secret")
    }

    @Test
    fun `a group that cannot be rendered makes nothing, is reported, and the others still import`() = runTest {
        source.renderResult = { file, _ ->
            if (file.id == "a") RenderResult.Failed(ImportProblem.Broken(file.displayName)) else RenderResult.Rendered(listOf("file:///pages/${file.id}.jpg"))
        }

        val outcome = useCase(request(ImportGroup(listOf(pdf)), ImportGroup(listOf(img1))))

        assertThat(outcome.problems).containsExactly(ImportProblem.Broken("a.pdf"))
        assertThat(outcome.documentIds).hasSize(1)
        assertThat(repository.getDocuments().first()).hasSize(1)
        assertThat(outcome.isComplete).isFalse()
    }

    @Test
    fun `when a later file of a group fails the earlier pages are deleted and nothing is stored`() = runTest {
        source.renderResult = { file, _ ->
            if (file.id == "i2") RenderResult.Failed(ImportProblem.Broken(file.displayName)) else RenderResult.Rendered(listOf("file:///pages/${file.id}.jpg"))
        }

        val outcome = useCase(request(ImportGroup(listOf(img1, img2))))

        assertThat(outcome.documentIds).isEmpty()
        assertThat(repository.getDocuments().first()).isEmpty()
        assertThat(source.deletedPages).containsExactly("file:///pages/i1.jpg")
    }

    @Test
    fun `a store failure is a problem and queues nothing`() = runTest {
        repository.failWith = com.postsaimanager.core.common.result.PamError.DatabaseError()

        val outcome = useCase(request(ImportGroup(listOf(img1))))

        assertThat(outcome.documentIds).isEmpty()
        assertThat(outcome.problems).hasSize(1)
        assertThat(processor.enqueueCalls).isEmpty()
    }

    @Test
    fun `the rendered pages and the batch are cleaned up after success`() = runTest {
        useCase(request(ImportGroup(listOf(pdf))))
        assertThat(source.deletedPages).hasSize(3)
        assertThat(source.discarded).containsExactly("batch-1")
    }

    @Test
    fun `the batch is cleaned up after a failure too`() = runTest {
        source.renderResult = { file, _ -> RenderResult.Failed(ImportProblem.Broken(file.displayName)) }
        useCase(request(ImportGroup(listOf(pdf))))
        assertThat(source.discarded).containsExactly("batch-1")
    }

    @Test
    fun `the batch is cleaned up when the job is cancelled`() = runTest {
        source.renderResult = { _, _ -> throw CancellationException("stopped") }

        assertThrows<CancellationException> { useCase(request(ImportGroup(listOf(pdf)))) }

        assertThat(source.discarded).containsExactly("batch-1")
        assertThat(repository.getDocuments().first()).isEmpty()
    }

    @Test
    fun `progress is reported after each document`() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        useCase(request(ImportGroup(listOf(pdf)), ImportGroup(listOf(img1)))) { done, total -> seen += done to total }
        assertThat(seen).containsExactly(1 to 2, 2 to 2).inOrder()
    }
}
