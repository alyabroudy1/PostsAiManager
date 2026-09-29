package com.postsaimanager.core.data.repository

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.TimelineEventType
import com.postsaimanager.core.testing.FakeTimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins F1: a document with no pages must not be left stuck at `PROCESSING` forever.
 *
 * `processDocument` used to return `PamError.OcrFailed` for this case without ever touching
 * the document's status — the row stayed `PROCESSING`, nothing logged why, and startup
 * recovery ([com.postsaimanager.core.data.worker.DocumentProcessingRecovery]) re-ran it on
 * every launch. This exercises the real `processDocument` (not an extracted seam — the fix is
 * that every non-success exit routes through the same `failDocument` helper) with every
 * dependency past `documentDao.getPages` mocked out, since the no-pages path returns before
 * any of them are touched.
 */
class DocumentProcessingPipelineTest {

    private val documentDao = mockk<DocumentDao>(relaxed = true)
    private val timelineRepository = FakeTimelineRepository()
    private val dispatcher = StandardTestDispatcher()

    private val pipeline = DocumentProcessingPipeline(
        ocrService = mockk(relaxed = true),
        entityExtractor = EntityExtractor(),
        indexDocument = mockk<IndexDocumentUseCase>(relaxed = true),
        mergeExtraction = MergeExtractionUseCase(),
        aiExtraction = mockk<AiExtractionUseCase>(relaxed = true),
        entityProfileLinker = mockk(relaxed = true),
        fieldRevisionDao = mockk<FieldRevisionDao>(relaxed = true),
        documentMapper = DocumentMapper(),
        documentDao = documentDao,
        timelineRepository = timelineRepository,
        appContext = mockk<Context>(relaxed = true),
        ioDispatcher = dispatcher,
    )

    // `failDocument` logs via `android.util.Log.w`, which the plain `android.jar` stub used
    // for JVM unit tests throws on rather than no-ops — same reason DocumentProcessingWorker's
    // own tests, if it had any at this layer, would need it too.
    @BeforeEach
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @AfterEach
    fun unmockLog() {
        unmockkStatic(Log::class)
    }

    @Test
    @DisplayName("a document with no pages ends FAILED, with the reason logged and recorded")
    fun noPagesFailsDocument() = runTest(dispatcher) {
        // Explicit, not left to the relaxed mock's default: a relaxed `DocumentEntity?`
        // return value is a *non-null* zero-valued instance, which the trashed-document
        // guard would (wrongly, for this test) read as trashed — see
        // `trashedDocumentStopsBeforeAnyWrite` below for the case that guard exists for.
        coEvery { documentDao.getById("doc-1") } returns null
        coEvery { documentDao.getPages("doc-1") } returns emptyList()

        val result = pipeline.processDocument("doc-1")

        assertThat(result).isInstanceOf(PamResult.Error::class.java)

        // Persisted, not just the in-memory flow — the next launch (and startup recovery)
        // must read this document as failed rather than re-enqueueing it forever.
        coVerify { documentDao.updateStatus("doc-1", DocumentStatus.FAILED.name, any()) }

        val state = pipeline.processingState.first()
        assertThat(state).isInstanceOf(ProcessingState.Failed::class.java)

        val event = timelineRepository.recorded.single()
        assertThat(event.documentId).isEqualTo("doc-1")
        assertThat(event.eventType).isEqualTo(TimelineEventType.PROCESSING_FAILED)
        // The machine-readable reason the detail screen's FAILED banner branches on, to offer
        // Delete instead of a pointless Try again.
        assertThat(event.data).isEqualTo("no_pages")
    }

    @Test
    @DisplayName("a trashed document is not processed — no status write, no pages read")
    fun trashedDocumentStopsBeforeAnyWrite() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns DocumentEntity(
            id = "doc-1",
            title = "doc-1",
            status = DocumentStatus.QUEUED.name,
            documentType = null,
            language = null,
            sourceType = "CAMERA",
            thumbnailPath = null,
            pageCount = 1,
            createdAt = 0L,
            modifiedAt = 0L,
            deletedAt = 1_000L,
        )

        val result = pipeline.processDocument("doc-1")

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { documentDao.updateStatus("doc-1", DocumentStatus.PROCESSING.name, any()) }
        coVerify(exactly = 0) { documentDao.getPages("doc-1") }
    }
}
