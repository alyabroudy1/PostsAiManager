package com.postsaimanager.core.data.repository

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.RecognisedFact
import com.postsaimanager.core.model.TimelineCodes
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.testing.FakeTimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The background reprocess path of [DocumentProcessingPipeline.processDocument] (`reprocess = true`):
 * it keeps what a person wrote, stamps the new version, stays invisible, and a failure leaves the
 * finished document exactly as it was.
 */
class DocumentReprocessPipelineTest {

    private val documentDao = mockk<DocumentDao>(relaxed = true)
    private val timeline = FakeTimelineRepository()
    private val dispatcher = StandardTestDispatcher()
    private val ocrService = mockk<OcrService>()
    private val aiExtraction = mockk<AiExtractionUseCase>()
    private val entityProfileLinker = mockk<EntityProfileLinker>(relaxed = true)
    private val indexDocument = mockk<IndexDocumentUseCase>()
    private val mapper = DocumentMapper()

    private val pipeline = DocumentProcessingPipeline(
        ocrService = ocrService,
        indexDocument = indexDocument,
        mergeExtraction = MergeExtractionUseCase(),
        aiExtraction = aiExtraction,
        entityProfileLinker = entityProfileLinker,
        fieldRevisionDao = mockk<FieldRevisionDao>(relaxed = true),
        documentMapper = mapper,
        documentDao = documentDao,
        timelineRepository = timeline,
        appContext = mockk<Context>(relaxed = true),
        ioDispatcher = dispatcher,
    )

    private val extractedDoc = DocumentEntity(
        id = "doc-1",
        title = "Letter",
        status = DocumentStatus.EXTRACTED.name,
        documentType = null,
        language = null,
        sourceType = "CAMERA",
        thumbnailPath = null,
        pageCount = 1,
        createdAt = 0L,
        modifiedAt = 0L,
        extractorVersion = "entity-extractor-1",
    )

    private val userField = ExtractedData(
        id = "f-user",
        documentId = "doc-1",
        fieldName = "caseWorker",
        fieldValue = "Herr Schmidt",
        fieldType = ExtractedFieldType.TEXT,
        confidence = 1f,
        source = ValueSource.USER,
        isConfirmed = true,
    )

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        coEvery { documentDao.getById("doc-1") } returns extractedDoc
        coEvery { documentDao.getPages("doc-1") } returns listOf(
            DocumentPageEntity(
                id = "p1", documentId = "doc-1", pageNumber = 1, imagePath = "/p1.jpg",
                processedPath = null, ocrText = null, ocrConfidence = null, width = 10, height = 10,
            ),
        )
        coEvery { documentDao.getExtractedData("doc-1") } returns listOf(mapper.extractedDataToEntity(userField))
        coEvery { ocrService.recognizeText(any()) } returns PamResult.Success(
            OcrResult(fullText = "Rechnung 64,98 EUR", confidence = 0.9f, blocks = emptyList(), detectedLanguage = "de"),
        )
        coEvery { indexDocument(any<String>(), any<List<IndexDocumentUseCase.PageText>>()) } returns
            PamResult.Success(IndexDocumentUseCase.Result("doc-1", chunkCount = 1, embedded = false))
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun understanding(modelUsed: Boolean = true) = DocumentUnderstanding(
        facts = listOf(RecognisedFact("Total", "64,98 EUR", FactKind.AMOUNT, 0.9f)),
        modelUsed = modelUsed,
    )

    @Test
    @DisplayName("keeps user rows, stamps the current version, and stays invisible")
    fun reprocessKeepsUserRowsAndUpdatesVersion() = runTest(dispatcher) {
        coEvery { aiExtraction(any(), any(), any(), any(), any()) } returns PamResult.Success(understanding())

        val result = pipeline.processDocument("doc-1", reprocess = true)

        assertThat(result).isInstanceOf(PamResult.Success::class.java)

        // The user's row is written back exactly as it was, never replaced by a machine value.
        val persisted = slot<List<ExtractedDataEntity>>()
        coVerify { documentDao.insertExtractedData(capture(persisted)) }
        val kept = persisted.captured.single { it.id == "f-user" }
        assertThat(kept.fieldValue).isEqualTo("Herr Schmidt")
        assertThat(kept.source).isEqualTo(ValueSource.USER.name)
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-user") }

        // The version that produced the new machine values is recorded on the document.
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.extractorVersion).isEqualTo(ExtractorVersion.CURRENT)

        // The document never leaves EXTRACTED: no PROCESSING, no re-write of the status.
        coVerify(exactly = 0) { documentDao.updateStatus(any(), any(), any()) }
        // No progress for the UI to show, and no profile proposals to ask the user about.
        assertThat(pipeline.processingState.first()).isEqualTo(ProcessingState.Idle)
        coVerify(exactly = 0) { entityProfileLinker.process(any(), any()) }

        // One timeline event, as a code and its two versions rather than English text.
        val event = timeline.recorded.single()
        assertThat(event.code).isEqualTo(TimelineCodes.REPROCESSED)
        assertThat(event.args).containsExactly("entity-extractor-1", ExtractorVersion.CURRENT).inOrder()
    }

    private val storedBlock = OcrBlock("Rechnung 64,98 EUR", TextBounds(0.1f, 0.1f, 0.9f, 0.2f), 0.9f)

    private fun storedPage(number: Int, withBlocks: Boolean = true) = DocumentPageEntity(
        id = "p$number", documentId = "doc-1", pageNumber = number, imagePath = "/p$number.jpg",
        processedPath = null, ocrText = "Rechnung 64,98 EUR", ocrConfidence = 0.8f,
        ocrBlocks = if (withBlocks) {
            Json.encodeToString(ListSerializer(OcrBlock.serializer()), listOf(storedBlock))
        } else {
            null
        },
        width = 10, height = 10,
    )

    @Test
    @DisplayName("a reprocess reuses the stored OCR when every page has it, and does not read an image")
    fun reprocessReusesStoredOcr() = runTest(dispatcher) {
        coEvery { documentDao.getPages("doc-1") } returns listOf(storedPage(1), storedPage(2))
        coEvery { aiExtraction(any(), any(), any(), any(), any()) } returns PamResult.Success(understanding())

        pipeline.processDocument("doc-1", reprocess = true)

        coVerify(exactly = 0) { ocrService.recognizeText(any()) }
        coVerify(exactly = 0) { documentDao.insertPages(any()) }
        // The stored blocks, page by page, are what the model is offered.
        coVerify { aiExtraction(listOf(storedBlock, storedBlock), any(), listOf(1, 1), any(), any()) }
    }

    @Test
    @DisplayName("a reprocess reads the images again when any page has no stored blocks")
    fun reprocessReadsAgainWhenOnePageLacksBlocks() = runTest(dispatcher) {
        coEvery { documentDao.getPages("doc-1") } returns listOf(storedPage(1), storedPage(2, withBlocks = false))
        coEvery { aiExtraction(any(), any(), any(), any(), any()) } returns PamResult.Success(understanding())

        pipeline.processDocument("doc-1", reprocess = true)

        coVerify(exactly = 2) { ocrService.recognizeText(any()) }
    }

    @Test
    @DisplayName("a first scan always reads the images, stored blocks or not")
    fun scanAlwaysReadsImages() = runTest(dispatcher) {
        coEvery { documentDao.getPages("doc-1") } returns listOf(storedPage(1))
        coEvery { aiExtraction(any(), any(), any(), any(), any()) } returns PamResult.Success(understanding())

        pipeline.processDocument("doc-1", reprocess = false)

        coVerify(exactly = 1) { ocrService.recognizeText(any()) }
    }

    private suspend fun titleAfterRun(doc: DocumentEntity, reprocess: Boolean): DocumentEntity {
        coEvery { documentDao.getById("doc-1") } returns doc
        coEvery { aiExtraction(any(), any(), any(), any(), any()) } returns
            PamResult.Success(understanding().copy(title = "Nordlicht Mahnung"))
        pipeline.processDocument("doc-1", reprocess = reprocess)
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        return updated.captured
    }

    @Test
    @DisplayName("a reprocess does not rename a document that already has a model title")
    fun reprocessKeepsTheStoredTitle() = runTest(dispatcher) {
        val after = titleAfterRun(extractedDoc.copy(extractionType = "bill"), reprocess = true)
        assertThat(after.title).isEqualTo("Letter")
    }

    @Test
    @DisplayName("the model's title replaces the default title, on a scan and on a reprocess alike")
    fun defaultTitleIsReplaced() = runTest(dispatcher) {
        val after = titleAfterRun(
            extractedDoc.copy(extractionType = "bill", titleCode = "scanned_pages", titleArgs = "[\"2\"]"),
            reprocess = true,
        )
        assertThat(after.title).isEqualTo("Nordlicht Mahnung")
        assertThat(after.titleCode).isNull()
    }

    @Test
    @DisplayName("a first model reading does not rename a document whose title is already real words")
    fun firstReadingKeepsARealTitle() = runTest(dispatcher) {
        val after = titleAfterRun(extractedDoc.copy(extractionType = null), reprocess = false)
        assertThat(after.title).isEqualTo("Letter")
    }

    @Test
    @DisplayName("a title a person set is never replaced")
    fun userTitleIsNeverReplaced() = runTest(dispatcher) {
        val after = titleAfterRun(
            extractedDoc.copy(isUserTitle = true, titleCode = "scanned_pages", extractionType = null),
            reprocess = false,
        )
        assertThat(after.title).isEqualTo("Letter")
    }

    @Test
    @DisplayName("a failure keeps the EXTRACTED status and the old data, and is recorded for one retry")
    fun failureKeepsStatusAndData() = runTest(dispatcher) {
        coEvery { aiExtraction(any(), any(), any(), any(), any()) } throws IllegalStateException("model crashed")

        val result = pipeline.processDocument("doc-1", reprocess = true)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { documentDao.updateStatus(any(), any(), any()) }
        coVerify(exactly = 0) { documentDao.update(any()) }
        coVerify(exactly = 0) { documentDao.insertExtractedData(any()) }
        assertThat(pipeline.processingState.first()).isEqualTo(ProcessingState.Idle)

        val event = timeline.recorded.single()
        assertThat(event.code).isEqualTo(TimelineCodes.REPROCESS_FAILED)
        assertThat(event.args).containsExactly("error")
    }

    @Test
    @DisplayName("a run in which the model did not read the letter changes nothing")
    fun noModelChangesNothing() = runTest(dispatcher) {
        coEvery { aiExtraction(any(), any(), any(), any(), any()) } returns PamResult.Success(understanding(modelUsed = false))

        val result = pipeline.processDocument("doc-1", reprocess = true)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { documentDao.update(any()) }
        coVerify(exactly = 0) { documentDao.insertExtractedData(any()) }
        assertThat(timeline.recorded.single().args).containsExactly("no_model")
    }

    @Test
    @DisplayName("a document without pages keeps its status on a reprocess")
    fun noPagesKeepsStatus() = runTest(dispatcher) {
        coEvery { documentDao.getPages("doc-1") } returns emptyList()

        val result = pipeline.processDocument("doc-1", reprocess = true)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { documentDao.updateStatus(any(), any(), any()) }
        assertThat(timeline.recorded.single().code).isEqualTo(TimelineCodes.REPROCESS_FAILED)
    }
}
