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
import com.postsaimanager.core.data.worker.DocumentEnrichmentWorker
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsRequest
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialReading
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialRequest
import com.postsaimanager.core.domain.extraction.gemma.PaidState
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.domain.reading.AnnounceUnderstoodLetterUseCase
import com.postsaimanager.core.domain.timeline.RecordDocumentEventsUseCase
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EventReading
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.FieldProvenance
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.RecognisedFact
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.testing.FakeTimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * "Gemma reads the letter" in the document pipeline: with the trial off (or unable to read) the reading is the one the pipeline always had,
 * and with it on the pipeline stores what the trial returned, the actions included, without asking the old reading at all.
 */
class DocumentProcessingPipelineGemmaTrialTest {

    private val documentDao = mockk<DocumentDao>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private val aiExtraction = mockk<AiExtractionUseCase>()
    private val ocrService = mockk<OcrService>()
    private val mapper = DocumentMapper()
    private val workManager = mockk<WorkManager>(relaxed = true)

    private val block = OcrBlock("Rechnung 64,98 EUR", TextBounds(0.1f, 0.1f, 0.9f, 0.2f), 0.9f)
    private val page = DocumentPageEntity(
        id = "p1", documentId = "doc-1", pageNumber = 1, imagePath = "/p1.jpg", processedPath = null, ocrText = null,
        ocrConfidence = null, ocrBlocks = null, width = 100, height = 200,
    )

    private val old = DocumentUnderstanding(
        documentType = "official_letter", modelUsed = true,
        facts = listOf(RecognisedFact("Amount", "1,00 EUR", FactKind.AMOUNT, 0.9f, FieldProvenance(slotKey = "total"))),
    )

    private val gemma = DocumentUnderstanding(
        language = "de", documentType = "invoice_bill", modelUsed = true, title = "Rechnung", titleCode = "composed",
        titleArgs = listOf("invoice_bill", "Stadtwerke", ""),
        summary = "Eine Rechnung.", summarySource = SummarySource.MODEL,
        facts = listOf(RecognisedFact("Amount", "64,98 EUR", FactKind.AMOUNT, 0.9f, FieldProvenance(slotKey = "total"))),
        actionItems = listOf(ActionItem("pay", mapOf("amount" to "total"))),
    )

    private val linker = mockk<EntityProfileLinker>(relaxed = true)
    private val recordEvents = mockk<RecordDocumentEventsUseCase>(relaxed = true)
    private val recordEventsLazy = mockk<dagger.Lazy<RecordDocumentEventsUseCase>>().also { every { it.get() } returns recordEvents }
    private val announce = mockk<AnnounceUnderstoodLetterUseCase>(relaxed = true)
    private val announceLazy = mockk<dagger.Lazy<AnnounceUnderstoodLetterUseCase>>().also { every { it.get() } returns announce }

    private fun pipeline(trial: GemmaTrialReading = GemmaTrialReading.NONE) = DocumentProcessingPipeline(
        ocrService = ocrService,
        indexDocument = mockk<IndexDocumentUseCase>().also {
            coEvery { it(any<String>(), any<List<IndexDocumentUseCase.PageText>>()) } returns
                PamResult.Success(IndexDocumentUseCase.Result("doc", chunkCount = 1, embedded = false))
        },
        mergeExtraction = MergeExtractionUseCase(),
        aiExtraction = aiExtraction,
        entityProfileLinker = linker,
        concernedPeopleDecision = mockk(relaxed = true),
        recordEvents = recordEventsLazy,
        syncEventLinks = mockk(relaxed = true),
        announceUnderstood = announceLazy,
        fieldRevisionDao = mockk<FieldRevisionDao>(relaxed = true),
        documentMapper = mapper,
        documentDao = documentDao,
        timelineRepository = FakeTimelineRepository(),
        appContext = mockk<Context>(relaxed = true),
        ioDispatcher = dispatcher,
        gemmaTrial = trial,
    )

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
        coEvery { documentDao.getById("doc-1") } returns DocumentEntity(
            id = "doc-1", title = "Scan", status = DocumentStatus.QUEUED.name, documentType = null, language = null, sourceType = "CAMERA",
            thumbnailPath = null, pageCount = 1, createdAt = 0L, modifiedAt = 0L, titleCode = "scan_default",
        )
        coEvery { documentDao.getPages("doc-1") } returns listOf(page)
        coEvery { documentDao.getExtractedData("doc-1") } returns emptyList()
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(old)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
        unmockkStatic(Log::class)
    }

    private fun storedValues(): List<String> {
        val captured = slot<List<ExtractedDataEntity>>()
        coVerify { documentDao.insertExtractedData(capture(captured)) }
        return captured.captured.map { it.fieldValue }
    }

    @Test
    @DisplayName("with the trial off (the default) the old reading runs and its result is what is stored")
    fun `off is the old path`() = runTest(dispatcher) {
        pipeline().processDocument("doc-1")

        coVerify(exactly = 1) { aiExtraction(any(), any(), any(), any(), any(), any(), any()) }
        assertThat(storedValues()).containsExactly("1,00 EUR")
    }

    @Test
    @DisplayName("a trial that cannot read (null) leaves the document to the old reading: never left unread")
    fun `unavailable falls back to the old path`() = runTest(dispatcher) {
        val trial = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding? = null
        }

        pipeline(trial).processDocument("doc-1")

        coVerify(exactly = 1) { aiExtraction(any(), any(), any(), any(), any(), any(), any()) }
        assertThat(storedValues()).containsExactly("1,00 EUR")
    }

    @Test
    @DisplayName("with the trial's reading the old reading is not asked; the fields, the summary and the actions are stored")
    fun `the trial's reading is stored`() = runTest(dispatcher) {
        val seen = slot<GemmaTrialRequest>()
        val trial = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding {
                seen.captured = request
                return gemma
            }
        }

        pipeline(trial).processDocument("doc-1")

        coVerify(exactly = 0) { aiExtraction(any(), any(), any(), any(), any(), any(), any()) }
        assertThat(storedValues()).containsExactly("64,98 EUR")
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        val document = mapper.toDomain(updated.captured)
        assertThat(document.extractionType).isEqualTo("invoice_bill")
        assertThat(document.summary).isEqualTo("Eine Rechnung.")
        assertThat(document.actionItems).containsExactly(ActionItem("pay", mapOf("amount" to "total")))
        // The reading is complete in one go: no second stage is owed or queued.
        assertThat(document.enrichmentPending).isFalse()
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(DocumentEnrichmentWorker.workName("doc-1"), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
        // What the trial was handed: the pages' blocks and pictures, and that this is no background re-read.
        assertThat(seen.captured.documentId).isEqualTo("doc-1")
        assertThat(seen.captured.pages).containsExactly(listOf(block))
        assertThat(seen.captured.pageImagePaths).containsExactly("/p1.jpg")
        assertThat(seen.captured.reprocess).isFalse()
        assertThat(seen.captured.pageAspect).isEqualTo(0.5f)
    }

    @Test
    @DisplayName("a Gemma reading writes the document's timeline event from the kind it decided, and announces the letter")
    fun `a gemma reading writes the timeline event`() = runTest(dispatcher) {
        val trial = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding = gemma.copy(event = EventReading("payment_demand"))
        }

        pipeline(trial).processDocument("doc-1")

        coVerify(exactly = 1) { recordEvents("doc-1", EventReading("payment_demand"), any()) }
        coVerify(exactly = 1) { announce("doc-1") }
    }

    @Test
    @DisplayName("a Gemma reading is followed by the profile linking (contact, suggestions), the people check and the event, on a scan and on a quiet re-read")
    fun `the steps after a reading run for every gemma reading`() = runTest(dispatcher) {
        val trial = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding = gemma.copy(event = EventReading("application_filed"))
        }

        pipeline(trial).processDocument("doc-1")
        pipeline(trial).processDocument("doc-1", reprocess = true)

        coVerify(exactly = 2) { linker.process("doc-1", any()) }
        coVerify(exactly = 2) { recordEvents("doc-1", EventReading("application_filed"), any()) }
        verify(exactly = 2) {
            workManager.enqueueUniqueWork(DocumentEnrichmentWorker.peopleWorkName("doc-1"), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    @DisplayName("a reading no model made (only found values) runs no after-reading step")
    fun `found values alone run no step`() = runTest(dispatcher) {
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(old.copy(modelUsed = false))

        pipeline().processDocument("doc-1")

        coVerify(exactly = 0) { linker.process(any(), any()) }
    }

    @Test
    @DisplayName("a reading that names no event kind writes no event")
    fun `no event kind writes nothing`() = runTest(dispatcher) {
        val trial = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding = gemma
        }

        pipeline(trial).processDocument("doc-1")

        coVerify(exactly = 0) { recordEvents(any(), any(), any()) }
    }

    private val oneGoReading get() = gemma.copy(summary = "", summarySource = null, enrichment = EnrichmentTicket(oneGo = true, paid = "already_paid"))

    @Test
    @DisplayName("a one-go Gemma reading is complete when stored (actions, event, announcement) and owes only its summary and key facts, which are queued")
    fun `a one-go reading owes only the texts`() = runTest(dispatcher) {
        val trial = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding = oneGoReading.copy(event = EventReading("payment_demand"))
        }
        val p = pipeline(trial)

        p.processDocument("doc-1")

        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        val document = mapper.toDomain(updated.captured)
        assertThat(document.actionItems).containsExactly(ActionItem("pay", mapOf("amount" to "total")))
        assertThat(document.enrichmentPending).isTrue()
        coVerify(exactly = 1) { recordEvents("doc-1", EventReading("payment_demand"), any()) }
        coVerify(exactly = 1) { announce("doc-1") }
        coVerify { documentDao.updateReadingStage("doc-1", "UNDERSTOOD") }
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(DocumentEnrichmentWorker.workName("doc-1"), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    private fun extractedDocument() = DocumentEntity(
        id = "doc-1", title = "Rechnung", status = DocumentStatus.EXTRACTED.name, documentType = null, language = "de", sourceType = "CAMERA",
        thumbnailPath = null, pageCount = 1, createdAt = 0L, modifiedAt = 0L, extractionType = "receipt", enrichmentPending = true,
    )

    @Test
    @DisplayName("the second stage of a one-go reading is the text step: the summary is stored, the old second stage is never asked")
    fun `enrichment is the text step`() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns extractedDocument()
        coEvery { documentDao.getPages("doc-1") } returns listOf(page.copy(ocrText = "Rechnung 64,98 EUR"))
        val trial = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding? = null
            override suspend fun writeTexts(request: GemmaTextsRequest): GemmaTextsOutcome {
                assertThat(request.oneGo).isTrue()
                assertThat(request.text.paid).isEqualTo(PaidState.ALREADY_PAID)
                return GemmaTextsOutcome.Done(
                    GemmaTextOutcome.Written(SummaryResult("Beleg über 64,98 EUR.", SummarySource.MODEL, null, emptyList()), emptyList(), 3L, emptyList()),
                )
            }
        }

        val result = pipeline(trial).enrichDocument("doc-1", EnrichmentTicket(oneGo = true, paid = "already_paid"))

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.summary).isEqualTo("Beleg über 64,98 EUR.")
        assertThat(updated.captured.enrichmentPending).isFalse()
        coVerify(exactly = 0) { aiExtraction(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("a text step that could not run is an error with the attempt counted, and the old second stage is not run in its place")
    fun `the text step failing counts an attempt`() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns extractedDocument()
        coEvery { documentDao.getPages("doc-1") } returns listOf(page.copy(ocrText = "Rechnung 64,98 EUR"))
        val trial = object : GemmaTrialReading {
            override suspend fun read(request: GemmaTrialRequest): DocumentUnderstanding? = null
            override suspend fun writeTexts(request: GemmaTextsRequest): GemmaTextsOutcome =
                GemmaTextsOutcome.Done(GemmaTextOutcome.Unavailable("no answer"))
        }

        val result = pipeline(trial).enrichDocument("doc-1", EnrichmentTicket(oneGo = true))

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { aiExtraction(any(), any(), any(), any(), any(), any(), any()) }
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.enrichmentAttempts).isEqualTo(1)
    }

    @Test
    @DisplayName("the old reading's first stage writes no event (its second stage does)")
    fun `the old path writes the event in its second stage`() = runTest(dispatcher) {
        pipeline().processDocument("doc-1")

        coVerify(exactly = 0) { recordEvents(any(), any(), any()) }
    }
}
