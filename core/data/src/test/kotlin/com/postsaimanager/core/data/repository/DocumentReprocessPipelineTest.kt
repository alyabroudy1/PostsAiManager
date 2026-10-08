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
import com.postsaimanager.core.data.mapper.JsonColumns
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.data.worker.ReaderRetry
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentType
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.FieldProvenance
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.TitleSource
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
        concernedPeopleDecision = mockk(relaxed = true),
        recordEvents = mockk(relaxed = true),
        syncEventLinks = mockk(relaxed = true),
        announceUnderstood = mockk(relaxed = true),
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
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(understanding())

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
        // No progress for the UI to show. The steps after a reading run as for any reading (the linker raises no question to ask the user).
        assertThat(pipeline.processingState.first()).isEqualTo(ProcessingState.Idle)
        coVerify(exactly = 1) { entityProfileLinker.process("doc-1", any()) }

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
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(understanding())

        pipeline.processDocument("doc-1", reprocess = true)

        coVerify(exactly = 0) { ocrService.recognizeText(any()) }
        coVerify(exactly = 0) { documentDao.insertPages(any()) }
        // The stored blocks, page by page, are what the model is offered.
        coVerify { aiExtraction(listOf(storedBlock, storedBlock), any(), listOf(1, 1), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("a reprocess reads the images again when any page has no stored blocks")
    fun reprocessReadsAgainWhenOnePageLacksBlocks() = runTest(dispatcher) {
        coEvery { documentDao.getPages("doc-1") } returns listOf(storedPage(1), storedPage(2, withBlocks = false))
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(understanding())

        pipeline.processDocument("doc-1", reprocess = true)

        coVerify(exactly = 2) { ocrService.recognizeText(any()) }
    }

    @Test
    @DisplayName("a first scan always reads the images, stored blocks or not")
    fun scanAlwaysReadsImages() = runTest(dispatcher) {
        coEvery { documentDao.getPages("doc-1") } returns listOf(storedPage(1))
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(understanding())

        pipeline.processDocument("doc-1", reprocess = false)

        coVerify(exactly = 1) { ocrService.recognizeText(any()) }
    }

    private suspend fun titleAfterRun(doc: DocumentEntity, reprocess: Boolean): DocumentEntity {
        coEvery { documentDao.getById("doc-1") } returns doc
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns
            PamResult.Success(understanding().copy(title = "Nordlicht Mahnung", titleCode = TitleComposer.CODE, titleArgs = listOf("invoice_bill", "Nordlicht", "Mahnung")))
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
    @DisplayName("the composed title replaces the default title, on a scan and on a reprocess alike")
    fun defaultTitleIsReplaced() = runTest(dispatcher) {
        val after = titleAfterRun(
            extractedDoc.copy(extractionType = "bill", titleCode = "scanned_pages", titleArgs = "[\"2\"]"),
            reprocess = true,
        )
        // The plain text stays as the fallback; the code and its args are what the screen renders the title from.
        assertThat(after.title).isEqualTo("Nordlicht Mahnung")
        assertThat(after.titleCode).isEqualTo(TitleComposer.CODE)
        assertThat(after.titleArgs).isEqualTo(JsonColumns.encodeStrings(listOf("invoice_bill", "Nordlicht", "Mahnung")))
        assertThat(after.titleSource).isEqualTo(TitleSource.COMPOSED.name)
    }

    @Test
    @DisplayName("a composed title is composed again by a reprocess, a legacy title in real words is not")
    fun composedTitleIsRecomposedRealWordsAreNot() = runTest(dispatcher) {
        val again = titleAfterRun(
            extractedDoc.copy(extractionType = "invoice_bill", title = "Alt", titleCode = TitleComposer.CODE, titleArgs = JsonColumns.encodeStrings(listOf("invoice_bill", "Alt", ""))),
            reprocess = true,
        )
        assertThat(again.title).isEqualTo("Nordlicht Mahnung")
        io.mockk.clearMocks(documentDao, answers = false)
        val legacy = titleAfterRun(extractedDoc.copy(extractionType = "bill", title = "Mahnung Nordlicht", titleCode = null), reprocess = true)
        assertThat(legacy.title).isEqualTo("Mahnung Nordlicht")
        assertThat(legacy.titleCode).isNull()
    }

    // ── a document read by the previous extractor: what a person decided survives the new version ──

    private fun stored(
        id: String, name: String, value: String, slot: String, machine: String = value, state: ReviewState = ReviewState.UNREVIEWED,
        source: ValueSource = ValueSource.MACHINE, type: ExtractedFieldType = ExtractedFieldType.TEXT,
    ) = ExtractedData(
        id = id, documentId = "doc-1", fieldName = name, fieldValue = value, fieldType = type, confidence = 0.8f,
        source = source, machineValue = machine, slotKey = slot, reviewState = state,
        isConfirmed = state == ReviewState.CONFIRMED || state == ReviewState.EDITED, deletedByUser = state == ReviewState.IGNORED,
    )

    /** What the new reading says: a different total, a different street, the IBAN again, a composed title and a new family. */
    private fun newReading() = DocumentUnderstanding(
        documentType = "invoice_bill", topics = listOf("tax"), layoutTemplate = "din5008_b",
        title = "Neuer Titel", titleCode = TitleComposer.CODE, titleArgs = listOf("invoice_bill", "Neu", ""),
        summary = "Neue Zusammenfassung.", summarySource = SummarySource.MODEL,
        facts = listOf(
            RecognisedFact("Total", "99,00 EUR", FactKind.AMOUNT, 0.9f, FieldProvenance(slotKey = "total")),
            RecognisedFact("addressee.street", "Musterweg 3", FactKind.OTHER, 0.9f, FieldProvenance(slotKey = "addressee.street")),
            RecognisedFact("IBAN", "DE02120300000000202051", FactKind.IBAN, 0.9f, FieldProvenance(slotKey = "iban")),
        ),
    )

    private val v14Doc get() = extractedDoc.copy(
        extractorVersion = "extraction-v2-1", extractionType = "bill", title = "Meine Rechnung", titleCode = null, isUserTitle = true,
        titleSource = TitleSource.USER.name, summary = "Meine Notiz", summarySource = SummarySource.USER.name,
    )

    @Test
    @DisplayName("a v14-era document keeps its confirmed field, edited address part, ignored field and user title when reprocessed")
    fun v14DocumentKeepsWhatAPersonDecided() = runTest(dispatcher) {
        val confirmed = stored("f-total", "Amount", "64,98 EUR", "total", state = ReviewState.CONFIRMED)
        val edited = stored("f-street", "addressee.street", "Neue Str. 5", "addressee.street", machine = "Alte Str. 1", state = ReviewState.EDITED, source = ValueSource.USER)
        val ignored = stored("f-iban", "IBAN", "DE89370400440532013000", "iban", state = ReviewState.IGNORED, type = ExtractedFieldType.IBAN)
        coEvery { documentDao.getById("doc-1") } returns v14Doc
        coEvery { documentDao.getExtractedData("doc-1") } returns listOf(confirmed, edited, ignored).map(mapper::extractedDataToEntity)
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(newReading())

        val result = pipeline.processDocument("doc-1", reprocess = true)

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        val rows = slot<List<ExtractedDataEntity>>()
        coVerify { documentDao.insertExtractedData(capture(rows)) }
        val byId = rows.captured.associateBy { it.id }
        // The confirmed value stands; the different reading is only flagged.
        assertThat(byId.getValue("f-total").fieldValue).isEqualTo("64,98 EUR")
        assertThat(byId.getValue("f-total").reviewState).isEqualTo(ReviewState.CONFIRMED.name)
        assertThat(byId.getValue("f-total").hasUnreviewedMachineChange).isTrue()
        // The edited address part stands, the person's.
        assertThat(byId.getValue("f-street").fieldValue).isEqualTo("Neue Str. 5")
        assertThat(byId.getValue("f-street").source).isEqualTo(ValueSource.USER.name)
        assertThat(byId.getValue("f-street").reviewState).isEqualTo(ReviewState.EDITED.name)
        // The ignored field is a tombstone: kept, not deleted and not brought back.
        assertThat(byId.getValue("f-iban").reviewState).isEqualTo(ReviewState.IGNORED.name)
        assertThat(byId.getValue("f-iban").fieldValue).isEqualTo("DE89370400440532013000")
        assertThat(byId.getValue("f-iban").deletedByUser).isTrue()
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-total") }
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-street") }
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-iban") }
        // The user's title and summary are untouched; the family the model chose is replaced by the new reading's.
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.title).isEqualTo("Meine Rechnung")
        assertThat(updated.captured.isUserTitle).isTrue()
        assertThat(updated.captured.titleCode).isNull()
        assertThat(updated.captured.titleSource).isEqualTo(TitleSource.USER.name)
        assertThat(updated.captured.summary).isEqualTo("Meine Notiz")
        assertThat(updated.captured.summarySource).isEqualTo(SummarySource.USER.name)
        assertThat(updated.captured.extractionType).isEqualTo("invoice_bill")
        assertThat(updated.captured.topics).isEqualTo(JsonColumns.encodeStrings(listOf("tax")))
        assertThat(updated.captured.layoutTemplate).isEqualTo("din5008_b")
        assertThat(updated.captured.extractorVersion).isEqualTo(ExtractorVersion.CURRENT)
    }

    @Test
    @DisplayName("a document whose family a person chose keeps its family and topics, and is read as that family")
    fun userFamilyIsKeptAndRead() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns v14Doc.copy(
            extractionType = "official_letter", familySource = FamilySource.USER.name, topics = JsonColumns.encodeStrings(listOf("health")),
            isUserTitle = false, titleCode = "scanned_pages", summary = null, summarySource = null,
        )
        val forced = slot<String>()
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), capture(forced)) } returns
            PamResult.Success(newReading().copy(documentType = "official_letter"))

        pipeline.processDocument("doc-1", reprocess = true)

        // The reading is told the person's family, so it asks that family's questions.
        assertThat(forced.captured).isEqualTo("official_letter")
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.extractionType).isEqualTo("official_letter")
        assertThat(updated.captured.familySource).isEqualTo(FamilySource.USER.name)
        assertThat(updated.captured.topics).isEqualTo(JsonColumns.encodeStrings(listOf("health")))
    }

    @Test
    @DisplayName("Read again as a family reads the letter as it and stores it as the person's choice")
    fun readAgainAsAFamily() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns v14Doc.copy(extractionType = "invoice_bill", isUserTitle = false, titleCode = "scanned_pages")
        val forced = slot<String>()
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), capture(forced)) } returns
            PamResult.Success(newReading().copy(documentType = "receipt", topics = listOf("shopping")))

        pipeline.processDocument("doc-1", reprocess = false, forcedFamily = "receipt")

        assertThat(forced.captured).isEqualTo("receipt")
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.extractionType).isEqualTo("receipt")
        assertThat(updated.captured.familySource).isEqualTo(FamilySource.USER.name)
        assertThat(updated.captured.topics).isEqualTo(JsonColumns.encodeStrings(listOf("shopping")))
    }

    @Test
    @DisplayName("a family the schema does not know is not forced")
    fun unknownForcedFamilyIsIgnored() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns v14Doc
        val seen = mutableListOf<String?>()
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), captureNullable(seen)) } returns PamResult.Success(newReading())

        pipeline.processDocument("doc-1", reprocess = true, forcedFamily = "spaceship")

        assertThat(seen).containsExactly(null)
    }

    @Test
    @DisplayName("a summary a person wrote stays, and one the model wrote is replaced, when the reading is not staged")
    fun summaryFollowsItsSource() = runTest(dispatcher) {
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(newReading())
        coEvery { documentDao.getById("doc-1") } returns v14Doc
        pipeline.processDocument("doc-1", reprocess = true)
        val kept = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(kept)) }
        assertThat(kept.captured.summary).isEqualTo("Meine Notiz")

        coEvery { documentDao.getById("doc-1") } returns v14Doc.copy(summary = "Alt", summarySource = SummarySource.MODEL.name)
        pipeline.processDocument("doc-1", reprocess = true)
        val written = mutableListOf<DocumentEntity>()
        coVerify(atLeast = 2) { documentDao.update(capture(written)) }
        assertThat(written.last().summary).isEqualTo("Neue Zusammenfassung.")
        assertThat(written.last().summarySource).isEqualTo(SummarySource.MODEL.name)
    }

    @Test
    @DisplayName("a family the model chose is replaced by the new reading's, with its topics and layout template")
    fun modelFamilyIsReplaced() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns v14Doc.copy(extractionType = "official_letter", topics = JsonColumns.encodeStrings(listOf("government")))
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(newReading())
        pipeline.processDocument("doc-1", reprocess = true)
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.extractionType).isEqualTo("invoice_bill")
        assertThat(updated.captured.familySource).isEqualTo(FamilySource.MODEL.name)
        assertThat(updated.captured.topics).isEqualTo(JsonColumns.encodeStrings(listOf("tax")))
        assertThat(updated.captured.documentType).isEqualTo(DocumentType.INVOICE.name)
    }

    @Test
    @DisplayName("a migrated health letter keeps its sensitive family and topic when the model reads another family")
    fun sensitivityIsStickyOnReprocess() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns v14Doc.copy(extractionType = "medical", topics = JsonColumns.encodeStrings(listOf("health")))
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(newReading())
        pipeline.processDocument("doc-1", reprocess = true)
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.extractionType).isEqualTo("medical")
        assertThat(JsonColumns.decodeStrings(updated.captured.topics)).contains("health")
    }

    @Test
    @DisplayName("the pipeline raises no proposal: organisations are linked, nobody is asked")
    fun noProposalsAreRaised() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns v14Doc.copy(extractionType = null, isUserTitle = false, titleCode = "scanned_pages")
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(newReading())
        pipeline.processDocument("doc-1", reprocess = false)
        coVerify { entityProfileLinker.process("doc-1", any()) }
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
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("model crashed")

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
    @DisplayName("a user-added field named like a first-stage slot survives the first stage, and the second stage is marked owed")
    fun firstStageKeepsAUserFieldNamedLikeASlot() = runTest(dispatcher) {
        val workManager = mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.mockkObject(androidx.work.WorkManager.Companion)
        every { androidx.work.WorkManager.getInstance(any<Context>()) } returns workManager
        try {
            // A person's extra (second-stage kind: "x:" slot key) whose name is the one the first stage's amount slot writes.
            val mine = ExtractedData(
                id = "f-mine", documentId = "doc-1", fieldName = "Amount", fieldValue = "99,00 EUR", fieldType = ExtractedFieldType.TEXT,
                confidence = 1f, slotKey = "x:amount", source = ValueSource.USER, isConfirmed = true,
            )
            coEvery { documentDao.getExtractedData("doc-1") } returns listOf(mapper.extractedDataToEntity(mine))
            coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns
                PamResult.Success(understanding().copy(enrichment = com.postsaimanager.core.model.EnrichmentTicket(typeId = "invoice_bill")))

            pipeline.processDocument("doc-1", reprocess = true)

            val persisted = slot<List<ExtractedDataEntity>>()
            coVerify { documentDao.insertExtractedData(capture(persisted)) }
            val amounts = persisted.captured.filter { it.fieldName == "Amount" }
            assertThat(amounts.map { it.id }).containsExactly("f-mine")
            assertThat(amounts.single().fieldValue).isEqualTo("99,00 EUR")
            coVerify(exactly = 0) { documentDao.deleteExtractedField("f-mine") }

            val updated = slot<DocumentEntity>()
            coVerify { documentDao.update(capture(updated)) }
            assertThat(updated.captured.enrichmentPending).isTrue()
        } finally {
            io.mockk.unmockkObject(androidx.work.WorkManager.Companion)
        }
    }

    @Test
    @DisplayName("a re-read whose reader model was unavailable changes nothing, records no failure and asks to be run again")
    fun unavailableReaderOnReprocessAsksAgain() = runTest(dispatcher) {
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns
            PamResult.Success(understanding(modelUsed = false).copy(readerUnavailable = true))

        val result = pipeline.processDocument("doc-1", reprocess = true)

        val error = (result as PamResult.Error).error
        assertThat(error).isInstanceOf(PamError.ModelNotLoaded::class.java)
        assertThat((error as PamError.ModelNotLoaded).modelName).isEqualTo(READER_MODEL_NAME)
        coVerify(exactly = 0) { documentDao.update(any()) }
        coVerify(exactly = 0) { documentDao.insertExtractedData(any()) }
        assertThat(timeline.recorded).isEmpty()
    }

    @Test
    @DisplayName("a first reading whose reader model was lost goes back to the queue, not to a degraded completion")
    fun unavailableReaderOnFirstReadingIsQueuedAgain() = runTest(dispatcher) {
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns
            PamResult.Success(understanding(modelUsed = false).copy(readerUnavailable = true))

        val result = pipeline.processDocument("doc-1")

        val error = (result as PamResult.Error).error
        assertThat(error).isInstanceOf(PamError.ModelNotLoaded::class.java)
        assertThat(ReaderRetry.shouldRetry(error, runAttemptCount = 0)).isTrue()
        // Back in the queue (not FAILED, not EXTRACTED), nothing stored from the degraded reading.
        coVerify { documentDao.updateStatus("doc-1", DocumentStatus.QUEUED.name, any()) }
        coVerify(exactly = 0) { documentDao.updateStatus("doc-1", DocumentStatus.FAILED.name, any()) }
        coVerify(exactly = 0) { documentDao.insertExtractedData(any()) }
        assertThat(timeline.recorded.map { it.code }).doesNotContain(TimelineCodes.PROCESSING_FAILED)
    }

    @Test
    @DisplayName("a run in which the model did not read the letter changes nothing")
    fun noModelChangesNothing() = runTest(dispatcher) {
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(understanding(modelUsed = false))

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
