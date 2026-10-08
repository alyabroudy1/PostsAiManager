package com.postsaimanager.core.data.repository

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.reading.AnnounceUnderstoodLetterUseCase
import com.postsaimanager.core.domain.document.EnrichmentRetryPolicy
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.FieldProvenance
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.ReadingStage
import com.postsaimanager.core.model.RecognisedFact
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.testing.FakeTimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The reading's stages as the pipeline stores them (TEXT_READY, FIELDS_READY, UNDERSTOOD): in order, each at its own boundary, the first
 * stage's fields stored before FIELDS_READY, a person's values never overwritten, and the "Letter understood" announcement made only
 * for a reading a person started, never for a quiet re-read.
 */
class ReadingStagePipelineTest {

    private val documentDao = mockk<DocumentDao>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private val aiExtraction = mockk<AiExtractionUseCase>()
    private val ocrService = mockk<OcrService>()
    private val mapper = DocumentMapper()
    private val announce = mockk<AnnounceUnderstoodLetterUseCase>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)

    private val pipeline = DocumentProcessingPipeline(
        ocrService = ocrService,
        indexDocument = mockk<IndexDocumentUseCase>().also {
            coEvery { it(any<String>(), any<List<IndexDocumentUseCase.PageText>>()) } returns
                PamResult.Success(IndexDocumentUseCase.Result("doc", chunkCount = 1, embedded = false))
        },
        mergeExtraction = MergeExtractionUseCase(),
        aiExtraction = aiExtraction,
        entityProfileLinker = mockk<EntityProfileLinker>(relaxed = true),
        concernedPeopleDecision = mockk(relaxed = true),
        recordEvents = mockk(relaxed = true),
        syncEventLinks = mockk(relaxed = true),
        announceUnderstood = dagger.Lazy { announce },
        fieldRevisionDao = mockk<FieldRevisionDao>(relaxed = true),
        documentMapper = mapper,
        documentDao = documentDao,
        timelineRepository = FakeTimelineRepository(),
        appContext = mockk<Context>(relaxed = true),
        ioDispatcher = dispatcher,
    )

    private val block = OcrBlock("Rechnung 64,98 EUR", TextBounds(0.1f, 0.1f, 0.9f, 0.2f), 0.9f)
    private val ticket = EnrichmentTicket(typeId = "bill", takenIds = listOf("A1"))

    private fun entity(status: DocumentStatus, stage: ReadingStage? = null, pending: Boolean = false) = DocumentEntity(
        id = "doc-1", title = "Scanned 1 page", status = status.name, documentType = null, language = null, sourceType = "CAMERA",
        thumbnailPath = null, pageCount = 1, createdAt = 0L, modifiedAt = 0L, extractorVersion = "x", titleCode = "scanned_pages",
        extractionType = "invoice_bill".takeIf { pending || stage != null }, enrichmentPending = pending, readingStage = stage?.name,
    )

    private fun page(withText: Boolean) = DocumentPageEntity(
        id = "p1", documentId = "doc-1", pageNumber = 1, imagePath = "/p1.jpg", processedPath = null,
        ocrText = "Rechnung 64,98 EUR".takeIf { withText }, ocrConfidence = 0.8f.takeIf { withText },
        ocrBlocks = Json.encodeToString(ListSerializer(OcrBlock.serializer()), listOf(block)).takeIf { withText }, width = 10, height = 10,
    )

    private fun total(value: String, source: ValueSource = ValueSource.MACHINE, confirmed: Boolean = false) = ExtractedData(
        id = "f-total", documentId = "doc-1", fieldName = "Amount", fieldValue = value, fieldType = ExtractedFieldType.TEXT, confidence = 0.8f,
        slotKey = "total", source = source, isConfirmed = confirmed,
    )

    private fun firstStage() = DocumentUnderstanding(
        documentType = "bill", modelUsed = true,
        facts = listOf(RecognisedFact("Total", "64,98 EUR", FactKind.AMOUNT, 0.9f, FieldProvenance(slotKey = "total"))),
        enrichment = ticket,
    )

    private fun secondStage(withSummary: Boolean = true) = DocumentUnderstanding(
        language = "de", documentType = "bill", title = "Rechnung Stadtwerke", summary = "Eine Rechnung.",
        titleCode = TitleComposer.CODE, titleArgs = listOf("invoice_bill", "Stadtwerke", "Rechnung"),
        summarySource = SummarySource.MODEL.takeIf { withSummary }, modelUsed = true,
    )

    /** The document as the dao holds it: updates and stage writes replace it, so each step sees what the step before stored. */
    private fun statefulDocument(start: DocumentEntity): () -> DocumentEntity {
        var stored = start
        coEvery { documentDao.getById("doc-1") } answers { stored }
        coEvery { documentDao.update(any()) } answers { stored = firstArg() }
        coEvery { documentDao.updateStatus("doc-1", any(), any()) } answers { stored = stored.copy(status = secondArg()) }
        coEvery { documentDao.updateReadingStage("doc-1", any()) } answers { stored = stored.copy(readingStage = secondArg()) }
        return { stored }
    }

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        coEvery { ocrService.recognizeText(any()) } returns PamResult.Success(
            OcrResult(fullText = "Rechnung 64,98 EUR", confidence = 0.9f, blocks = listOf(block), detectedLanguage = "de"),
        )
        coEvery { documentDao.getExtractedData("doc-1") } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
        unmockkStatic(Log::class)
    }

    private fun answer(u: DocumentUnderstanding) {
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(u)
    }

    @Test
    fun `a scan moves through the stages in order, each at its own boundary`() = runTest(dispatcher) {
        statefulDocument(entity(DocumentStatus.QUEUED))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = false))
        answer(firstStage())

        pipeline.processDocument("doc-1")

        coVerifyOrder {
            documentDao.updateStatus("doc-1", DocumentStatus.PROCESSING.name, any())
            documentDao.updateReadingStage("doc-1", null)
            documentDao.insertPages(any())
            documentDao.updateReadingStage("doc-1", ReadingStage.TEXT_READY.name)
            documentDao.insertExtractedData(any())
            documentDao.updateReadingStage("doc-1", ReadingStage.FIELDS_READY.name)
            documentDao.updateStatus("doc-1", DocumentStatus.EXTRACTED.name, any())
        }
        // UNDERSTOOD belongs to the second stage: nothing announced, nothing marked, until it ran.
        coVerify(exactly = 0) { documentDao.updateReadingStage("doc-1", ReadingStage.UNDERSTOOD.name) }
        coVerify(exactly = 0) { announce(any()) }
    }

    @Test
    fun `the first stage's fields are stored before FIELDS_READY, so they show at once`() = runTest(dispatcher) {
        val stored = statefulDocument(entity(DocumentStatus.QUEUED))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = false))
        var fieldsStoredAtBoundary = false
        coEvery { documentDao.updateReadingStage("doc-1", ReadingStage.FIELDS_READY.name) } answers {
            fieldsStoredAtBoundary = true
        }
        val persisted = slot<List<ExtractedDataEntity>>()
        coEvery { documentDao.insertExtractedData(capture(persisted)) } answers { assertThat(fieldsStoredAtBoundary).isFalse() }
        answer(firstStage())

        pipeline.processDocument("doc-1")

        assertThat(persisted.captured.map { it.fieldValue }).contains("64,98 EUR")
        assertThat(fieldsStoredAtBoundary).isTrue()
        assertThat(stored().status).isEqualTo(DocumentStatus.EXTRACTED.name)
    }

    @Test
    fun `a value a person confirmed is never overwritten by the first stage`() = runTest(dispatcher) {
        statefulDocument(entity(DocumentStatus.QUEUED))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = false))
        coEvery { documentDao.getExtractedData("doc-1") } returns
            listOf(total("100,00 EUR", ValueSource.USER, confirmed = true)).map(mapper::extractedDataToEntity)
        answer(firstStage())

        pipeline.processDocument("doc-1")

        val persisted = slot<List<ExtractedDataEntity>>()
        coVerify { documentDao.insertExtractedData(capture(persisted)) }
        val mine = persisted.captured.single { it.id == "f-total" }
        assertThat(mine.fieldValue).isEqualTo("100,00 EUR")
        assertThat(mine.source).isEqualTo(ValueSource.USER.name)
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-total") }
    }

    @Test
    fun `a value a person confirmed is never overwritten by the second stage either`() = runTest(dispatcher) {
        statefulDocument(entity(DocumentStatus.EXTRACTED, ReadingStage.FIELDS_READY, pending = true))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = true))
        val mineExtra = total("4711", ValueSource.USER, confirmed = true).copy(id = "f-mine", fieldName = "Zählernummer", slotKey = "x:zaehler")
        coEvery { documentDao.getExtractedData("doc-1") } returns listOf(mineExtra).map(mapper::extractedDataToEntity)
        answer(
            secondStage().copy(facts = listOf(RecognisedFact("Zählernummer", "9999", FactKind.OTHER, 0.7f, FieldProvenance(slotKey = "x:zaehler")))),
        )

        pipeline.enrichDocument("doc-1", ticket)

        val persisted = slot<List<ExtractedDataEntity>>()
        coVerify { documentDao.insertExtractedData(capture(persisted)) }
        assertThat(persisted.captured.single { it.id == "f-mine" }.fieldValue).isEqualTo("4711")
    }

    @Test
    fun `the second stage completes the reading and announces the letter once`() = runTest(dispatcher) {
        val stored = statefulDocument(entity(DocumentStatus.EXTRACTED, ReadingStage.FIELDS_READY, pending = true))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = true))
        answer(secondStage())

        pipeline.enrichDocument("doc-1", ticket)

        assertThat(stored().readingStage).isEqualTo(ReadingStage.UNDERSTOOD.name)
        assertThat(stored().enrichmentPending).isFalse()
        coVerify(exactly = 1) { announce("doc-1") }
    }

    @Test
    fun `a second stage that wrote no summary is not announced until its attempts run out`() = runTest(dispatcher) {
        val stored = statefulDocument(entity(DocumentStatus.EXTRACTED, ReadingStage.FIELDS_READY, pending = true))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = true))
        answer(secondStage().copy(modelUsed = false))

        for (attempt in 1 until EnrichmentRetryPolicy.MAX_ATTEMPTS) {
            pipeline.enrichDocument("doc-1", ticket)
            assertThat(stored().readingStage).isEqualTo(ReadingStage.FIELDS_READY.name)
        }
        coVerify(exactly = 0) { announce(any()) }

        pipeline.enrichDocument("doc-1", ticket)
        assertThat(stored().readingStage).isEqualTo(ReadingStage.UNDERSTOOD.name)
        coVerify(exactly = 1) { announce("doc-1") }
    }

    @Test
    fun `a quiet re-read never moves the stage and never announces`() = runTest(dispatcher) {
        // A finished letter (UNDERSTOOD) re-read after an extractor version bump: reprocess = true keeps its status and its stage.
        val stored = statefulDocument(entity(DocumentStatus.EXTRACTED, ReadingStage.UNDERSTOOD))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = true))
        answer(firstStage())

        pipeline.processDocument("doc-1", reprocess = true)
        assertThat(stored().readingStage).isEqualTo(ReadingStage.UNDERSTOOD.name)
        coVerify(exactly = 0) { documentDao.updateReadingStage(any(), any()) }

        // Its second stage runs too (the first stage owes one), and finds nothing to complete.
        answer(secondStage())
        pipeline.enrichDocument("doc-1", ticket)
        assertThat(stored().readingStage).isEqualTo(ReadingStage.UNDERSTOOD.name)
        coVerify(exactly = 0) { announce(any()) }
    }

    @Test
    fun `a letter read before stages existed is never announced when its second stage is recovered`() = runTest(dispatcher) {
        statefulDocument(entity(DocumentStatus.EXTRACTED, stage = null, pending = true))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = true))
        answer(secondStage())

        pipeline.enrichDocument("doc-1", null)

        coVerify(exactly = 0) { announce(any()) }
    }

    @Test
    fun `a reading no model understood is finished at its first stage and not announced`() = runTest(dispatcher) {
        val stored = statefulDocument(entity(DocumentStatus.QUEUED))
        coEvery { documentDao.getPages("doc-1") } returns listOf(page(withText = false))
        answer(firstStage().copy(modelUsed = false, enrichment = null))

        pipeline.processDocument("doc-1")

        assertThat(stored().readingStage).isEqualTo(ReadingStage.UNDERSTOOD.name)
        coVerify(exactly = 0) { announce(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }
}
