package com.postsaimanager.core.data.worker

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.testing.FakeDocumentProcessor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins H4: a document left `QUEUED` — not just `PROCESSING` — because its work was lost (the
 * app killed in the window between [com.postsaimanager.core.domain.document.DocumentProcessor
 * .enqueue] setting the status and `WorkManager` actually scheduling the request, or the
 * `:inference` process becoming unreachable) must resume on its own, exactly like a
 * `PROCESSING` document.
 */
class DocumentProcessingRecoveryTest {

    private val documentDao = mockk<DocumentDao>()
    private val documentProcessor = FakeDocumentProcessor()
    private val dispatcher = StandardTestDispatcher()

    private val recovery = DocumentProcessingRecovery(documentDao, documentProcessor, dispatcher)

    private fun doc(id: String, status: DocumentStatus) = DocumentEntity(
        id = id,
        title = "doc-$id",
        status = status.name,
        documentType = null,
        language = null,
        sourceType = "CAMERA",
        thumbnailPath = null,
        pageCount = 1,
        createdAt = 0L,
        modifiedAt = 0L,
    )

    @Test
    @DisplayName("re-enqueues documents stuck at PROCESSING")
    fun resumesProcessing() = runTest(dispatcher) {
        coEvery { documentDao.getByStatus(DocumentStatus.PROCESSING.name) } returns
            listOf(doc("p1", DocumentStatus.PROCESSING))
        coEvery { documentDao.getByStatus(DocumentStatus.QUEUED.name) } returns emptyList()

        recovery.resumeInterrupted()

        assertThat(documentProcessor.enqueueCalls.map { it.documentId }).containsExactly("p1")
    }

    @Test
    @DisplayName("re-enqueues documents stuck at QUEUED whose work was lost")
    fun resumesQueued() = runTest(dispatcher) {
        coEvery { documentDao.getByStatus(DocumentStatus.PROCESSING.name) } returns emptyList()
        coEvery { documentDao.getByStatus(DocumentStatus.QUEUED.name) } returns
            listOf(doc("q1", DocumentStatus.QUEUED))

        recovery.resumeInterrupted()

        assertThat(documentProcessor.enqueueCalls.map { it.documentId }).containsExactly("q1")
    }

    @Test
    @DisplayName("re-enqueues both PROCESSING and QUEUED documents in one pass")
    fun resumesBoth() = runTest(dispatcher) {
        coEvery { documentDao.getByStatus(DocumentStatus.PROCESSING.name) } returns
            listOf(doc("p1", DocumentStatus.PROCESSING))
        coEvery { documentDao.getByStatus(DocumentStatus.QUEUED.name) } returns
            listOf(doc("q1", DocumentStatus.QUEUED))

        recovery.resumeInterrupted()

        assertThat(documentProcessor.enqueueCalls.map { it.documentId })
            .containsExactly("p1", "q1")
    }

    @Test
    @DisplayName("does nothing when nothing is stuck")
    fun noOpWhenNothingStuck() = runTest(dispatcher) {
        coEvery { documentDao.getByStatus(any()) } returns emptyList()

        recovery.resumeInterrupted()

        assertThat(documentProcessor.enqueueCalls).isEmpty()
    }
}
