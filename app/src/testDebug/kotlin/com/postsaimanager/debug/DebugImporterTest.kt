package com.postsaimanager.debug

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import kotlinx.coroutines.flow.first
import com.postsaimanager.core.domain.usecase.CreateDocumentFromPagesUseCase
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The debug import hands the pushed pages to the scanner's own use case, as one document, and never touches paths outside the inbox. */
class DebugImporterTest {

    @TempDir
    lateinit var dir: File

    private val repo = FakeDocumentRepository()
    private val processor = FakeDocumentProcessor()

    private fun importer(): DebugImporter =
        DebugImporter(File(dir, "inbox").apply { mkdirs() }, File(dir, "pages"), CreateDocumentFromPagesUseCase(repo, processor))

    private fun inboxFile(name: String) = File(dir, "inbox/$name").apply { parentFile.mkdirs(); writeText("jpg") }

    @Test
    fun `plain file names pass, separators and parent references do not`() {
        assertThat(DebugImporter.isPlainName("page-1.jpg")).isTrue()
        assertThat(DebugImporter.isPlainName("../page-1.jpg")).isFalse()
        assertThat(DebugImporter.isPlainName("a/b.jpg")).isFalse()
        assertThat(DebugImporter.isPlainName("a\\b.jpg")).isFalse()
        assertThat(DebugImporter.isPlainName("..")).isFalse()
        assertThat(DebugImporter.isPlainName("")).isFalse()
    }

    @Test
    fun `two files become one two-page document, queued for reading, and the inbox is emptied`() = runTest {
        val first = inboxFile("page-1.jpg")
        val second = inboxFile("page-2.jpg")

        val outcome = importer().import(listOf("page-1.jpg", "page-2.jpg")) as DebugImporter.Outcome.Imported

        assertThat(outcome.pages).isEqualTo(2)
        assertThat((repo.getDocumentById(outcome.documentId) as PamResult.Success).data.sourceType).isEqualTo(SourceType.UPLOAD)
        val pages = (repo.getDocumentPages(outcome.documentId) as PamResult.Success).data
        assertThat(pages.map { it.pageNumber }).containsExactly(1, 2).inOrder()
        assertThat(pages.all { File(java.net.URI(it.imagePath)).isFile }).isTrue()
        assertThat(processor.enqueueCalls.single().documentId).isEqualTo(outcome.documentId)
        assertThat(first.exists() || second.exists()).isFalse()
    }

    @Test
    fun `a name with a path or a missing file stores nothing`() = runTest {
        inboxFile("page-1.jpg")
        assertThat(importer().import(listOf("page-1.jpg", "../escape.jpg"))).isInstanceOf(DebugImporter.Outcome.Rejected::class.java)
        assertThat(importer().import(listOf("page-1.jpg", "missing.jpg"))).isInstanceOf(DebugImporter.Outcome.Rejected::class.java)
        assertThat(importer().import(emptyList())).isInstanceOf(DebugImporter.Outcome.Rejected::class.java)
        assertThat(repo.getDocuments().first()).isEmpty()
        assertThat(processor.enqueueCalls).isEmpty()
        assertThat(File(dir, "inbox/page-1.jpg").exists()).isTrue()
    }
}
