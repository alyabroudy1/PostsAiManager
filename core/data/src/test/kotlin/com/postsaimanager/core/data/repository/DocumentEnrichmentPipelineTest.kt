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
import com.postsaimanager.core.domain.document.EnrichmentRetryPolicy
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.FieldProvenance
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.RecognisedFact
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.TitleSource
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.testing.FakeTimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.postsaimanager.core.data.worker.DocumentEnrichmentWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The second stage of a reading ([DocumentProcessingPipeline.enrichDocument]): it reads the stored text again, asks only for the second
 * stage, and merges what it wrote into the rows it owns (the extras and the subject line), never over what a person wrote or confirmed,
 * never touching the first stage's parties and slots, and taking the title only where the title policy allows.
 */
class DocumentEnrichmentPipelineTest {

    private val documentDao = mockk<DocumentDao>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private val aiExtraction = mockk<AiExtractionUseCase>()
    private val mapper = DocumentMapper()

    private val pipeline = DocumentProcessingPipeline(
        ocrService = mockk<OcrService>(),
        indexDocument = mockk<IndexDocumentUseCase>(),
        mergeExtraction = MergeExtractionUseCase(),
        aiExtraction = aiExtraction,
        entityProfileLinker = mockk<EntityProfileLinker>(relaxed = true),
        concernedPeopleDecision = mockk(relaxed = true),
        fieldRevisionDao = mockk<FieldRevisionDao>(relaxed = true),
        documentMapper = mapper,
        documentDao = documentDao,
        timelineRepository = FakeTimelineRepository(),
        appContext = mockk<Context>(relaxed = true),
        ioDispatcher = dispatcher,
    )

    private fun doc(title: String = "Scan 30 Sep", titleCode: String? = "scan_default", isUserTitle: Boolean = false, summary: String? = null) = DocumentEntity(
        id = "doc-1", title = title, status = DocumentStatus.EXTRACTED.name, documentType = null, language = null, sourceType = "CAMERA",
        thumbnailPath = null, pageCount = 1, createdAt = 0L, modifiedAt = 0L, extractorVersion = "x",
        titleCode = titleCode, isUserTitle = isUserTitle, summary = summary,
    )

    private val block = OcrBlock("Rechnung 64,98 EUR", TextBounds(0.1f, 0.1f, 0.9f, 0.2f), 0.9f)
    private val page = DocumentPageEntity(
        id = "p1", documentId = "doc-1", pageNumber = 1, imagePath = "/p1.jpg", processedPath = null, ocrText = "Rechnung 64,98 EUR",
        ocrConfidence = 0.8f, ocrBlocks = Json.encodeToString(ListSerializer(OcrBlock.serializer()), listOf(block)), width = 10, height = 10,
    )

    private fun field(id: String, name: String, value: String, slot: String?, source: ValueSource = ValueSource.MACHINE, confirmed: Boolean = false) = ExtractedData(
        id = id, documentId = "doc-1", fieldName = name, fieldValue = value, fieldType = ExtractedFieldType.TEXT, confidence = 0.8f,
        slotKey = slot, source = source, isConfirmed = confirmed,
    )

    /** What the first stage stored: the sender, the total, a machine extra, and an extra a person confirmed. */
    private val sender = field("f-sender", "Sender Organization", "Stadtwerke", UnderstandingToFields.SLOT_SENDER)
    private val total = field("f-total", "Amount", "64,98 EUR", "total")
    private val staleExtra = field("f-stale", "Tarif", "Basis", "x:tarif")
    private val confirmedExtra = field("f-mine", "Zählernummer", "4711", "x:zaehlernummer", source = ValueSource.USER, confirmed = true)

    private val ticket = EnrichmentTicket(typeId = "bill", takenIds = listOf("A1", "M1"))

    private fun understanding(title: String = "Rechnung Stadtwerke", summary: String = "Eine Rechnung über 64,98 EUR.") = DocumentUnderstanding(
        language = "de", documentType = "bill", title = title, summary = summary, suggestedQuestions = listOf("Bis wann muss ich zahlen?"),
        // What the second stage composes and writes: a coded title and the writer's summary (the model's sentences).
        titleCode = TitleComposer.CODE, titleArgs = listOf("invoice_bill", "Stadtwerke", "Rechnung Juli"),
        summarySource = SummarySource.MODEL,
        facts = listOf(
            RecognisedFact("Zählernummer", "4711-X", FactKind.OTHER, 0.7f, FieldProvenance(slotKey = "x:zaehlernummer")),
            RecognisedFact("Vertrag", "V-99", FactKind.OTHER, 0.7f, FieldProvenance(slotKey = "x:vertrag")),
            RecognisedFact("Subject", "Rechnung Juli", FactKind.SUBJECT, 0.9f, FieldProvenance(slotKey = "subject")),
        ),
    )

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        coEvery { documentDao.getById("doc-1") } returns doc()
        coEvery { documentDao.getPages("doc-1") } returns listOf(page)
        coEvery { documentDao.getExtractedData("doc-1") } returns listOf(sender, total, staleExtra, confirmedExtra).map(mapper::extractedDataToEntity)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun answer(u: DocumentUnderstanding) {
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } returns PamResult.Success(u)
    }

    @Test
    fun `it asks the second stage only, from the stored text and the first stage's ticket`() = runTest(dispatcher) {
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)
        coVerify {
            // The ticket also carries the slot values the first stage stored (here the amount), which the stage scores for key information.
            aiExtraction(
                listOf(block), any(), listOf(1), any(), any(), ExtractionV2Pipeline.Stages.SECOND,
                ticket.copy(slots = listOf(com.postsaimanager.core.model.TicketSlot("total", "Amount", "64,98 EUR"))),
            )
        }
    }

    @Test
    fun `the first stage's rows are never touched and a person's extra stands`() = runTest(dispatcher) {
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)

        val persisted = slot<List<ExtractedDataEntity>>()
        coVerify { documentDao.insertExtractedData(capture(persisted)) }
        val ids = persisted.captured.map { it.id }
        // Not offered to the merge, so neither rewritten nor deleted.
        assertThat(ids).doesNotContain("f-sender")
        assertThat(ids).doesNotContain("f-total")
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-sender") }
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-total") }
        // The value a person confirmed stays theirs; the new reading only flags the difference.
        val mine = persisted.captured.single { it.id == "f-mine" }
        assertThat(mine.fieldValue).isEqualTo("4711")
        assertThat(mine.source).isEqualTo(ValueSource.USER.name)
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-mine") }
        // A machine extra the model no longer writes goes; a new one is stored.
        coVerify { documentDao.deleteExtractedField("f-stale") }
        assertThat(persisted.captured.map { it.fieldName }).contains("Vertrag")
        assertThat(persisted.captured.map { it.fieldName }).contains("Subject")
    }

    @Test
    fun `the language and summary land on the document and the composed title replaces a default title only`() = runTest(dispatcher) {
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.language).isEqualTo("de")
        assertThat(updated.captured.summary).isEqualTo("Eine Rechnung über 64,98 EUR.")
        assertThat(updated.captured.summarySource).isEqualTo(SummarySource.MODEL.name)
        assertThat(updated.captured.summaryCode).isNull()
        // The title is the composed one: its code and args, and the plain text as the fallback.
        assertThat(updated.captured.title).isEqualTo("Rechnung Stadtwerke")
        assertThat(updated.captured.titleCode).isEqualTo(TitleComposer.CODE)
        assertThat(updated.captured.titleArgs).isEqualTo(JsonColumns.encodeStrings(listOf("invoice_bill", "Stadtwerke", "Rechnung Juli")))
        assertThat(updated.captured.titleSource).isEqualTo(TitleSource.COMPOSED.name)
        // The status is not touched: the document stays as shown.
        coVerify(exactly = 0) { documentDao.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `a title a person set is never replaced by the second stage`() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns doc(title = "Meine Stromrechnung", titleCode = null, isUserTitle = true)
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.title).isEqualTo("Meine Stromrechnung")
        assertThat(updated.captured.titleCode).isNull()
        assertThat(updated.captured.titleSource).isEqualTo(TitleSource.USER.name)
        assertThat(updated.captured.summary).isNotNull()
    }

    @Test
    fun `a title in real words from an older reading is never touched by the second stage`() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns doc(title = "Stromrechnung Stadtwerke", titleCode = null, isUserTitle = false)
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.title).isEqualTo("Stromrechnung Stadtwerke")
        assertThat(updated.captured.titleCode).isNull()
    }

    @Test
    fun `a summary a person wrote stays, and a template summary is stored as its code and arguments`() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns doc(summary = "Meine Notiz").copy(summarySource = SummarySource.USER.name)
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)
        val kept = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(kept)) }
        assertThat(kept.captured.summary).isEqualTo("Meine Notiz")
        assertThat(kept.captured.summarySource).isEqualTo(SummarySource.USER.name)

        coEvery { documentDao.getById("doc-1") } returns doc()
        val args = listOf("invoice_bill", "Stadtwerke", "", "64,98 EUR", "", "")
        answer(understanding(summary = "").copy(summarySource = SummarySource.TEMPLATE, summaryCode = "template", summaryArgs = args))
        pipeline.enrichDocument("doc-1", ticket)
        val written = mutableListOf<DocumentEntity>()
        coVerify(atLeast = 2) { documentDao.update(capture(written)) }
        val last = written.last()
        assertThat(last.summary).isNull()
        assertThat(last.summarySource).isEqualTo(SummarySource.TEMPLATE.name)
        assertThat(last.summaryCode).isEqualTo("template")
        assertThat(last.summaryArgs).isEqualTo(JsonColumns.encodeStrings(args))
    }

    @Test
    fun `a second stage that wrote no summary leaves the summary pending and counts the attempt`() = runTest(dispatcher) {
        answer(understanding(summary = "").copy(summarySource = null))
        pipeline.enrichDocument("doc-1", ticket)
        val updates = mutableListOf<DocumentEntity>()
        coVerify { documentDao.update(capture(updates)) }
        assertThat(updates.last().summarySource).isNull()
        assertThat(updates.last().enrichmentAttempts).isEqualTo(1)
    }

    /** The document as the dao holds it: every update replaces it, so a run sees what the run before stored. */
    private fun statefulDocument(start: DocumentEntity): () -> DocumentEntity {
        var stored = start
        coEvery { documentDao.getById("doc-1") } answers { stored }
        coEvery { documentDao.update(any()) } answers { stored = firstArg() }
        return { stored }
    }

    @Test
    fun `a second stage that keeps failing settles on the template summary after its last attempt, never retried again`() = runTest(dispatcher) {
        val stored = statefulDocument(doc(titleCode = null).copy(extractionType = "invoice_bill"))
        answer(understanding().copy(modelUsed = false))

        for (attempt in 1 until EnrichmentRetryPolicy.MAX_ATTEMPTS) {
            assertThat(pipeline.enrichDocument("doc-1", null)).isInstanceOf(PamResult.Error::class.java)
            assertThat(stored().enrichmentAttempts).isEqualTo(attempt)
            assertThat(stored().summarySource).isNull()
        }
        assertThat(pipeline.enrichDocument("doc-1", null)).isInstanceOf(PamResult.Error::class.java)

        // Settled: the template summary, rendered from the verified fields, and nothing awaits a summary any more.
        assertThat(stored().enrichmentAttempts).isEqualTo(EnrichmentRetryPolicy.MAX_ATTEMPTS)
        assertThat(stored().summarySource).isEqualTo(SummarySource.TEMPLATE.name)
        assertThat(stored().summaryCode).isEqualTo(SummaryWriter.TEMPLATE_CODE)
        assertThat(JsonColumns.decodeStrings(stored().summaryArgs)).containsExactly("invoice_bill", "Stadtwerke", "", "64,98 EUR", "", "").inOrder()
        assertThat(stored().summary).isNull()
        assertThat(pipeline.enrichingDocuments.first()).isEmpty()
    }

    @Test
    fun `an engine error counts as an attempt too, and a summary a person wrote is never replaced by the template`() = runTest(dispatcher) {
        val stored = statefulDocument(doc(titleCode = null).copy(extractionType = "invoice_bill", summary = "Mine", summarySource = SummarySource.USER.name))
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("engine died")

        repeat(EnrichmentRetryPolicy.MAX_ATTEMPTS) { pipeline.enrichDocument("doc-1", null) }

        assertThat(stored().enrichmentAttempts).isEqualTo(EnrichmentRetryPolicy.MAX_ATTEMPTS)
        assertThat(stored().summarySource).isEqualTo(SummarySource.USER.name)
        assertThat(stored().summary).isEqualTo("Mine")
    }

    @Test
    fun `topics scored in the second stage are stored unless a person chose the family`() = runTest(dispatcher) {
        answer(understanding().copy(topics = listOf("tax", "government")))
        pipeline.enrichDocument("doc-1", ticket)
        val stored = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(stored)) }
        assertThat(stored.captured.topics).isEqualTo(JsonColumns.encodeStrings(listOf("tax", "government")))

        coEvery { documentDao.getById("doc-1") } returns doc().copy(familySource = FamilySource.USER.name, topics = JsonColumns.encodeStrings(listOf("health")))
        pipeline.enrichDocument("doc-1", ticket)
        val kept = mutableListOf<DocumentEntity>()
        coVerify(atLeast = 2) { documentDao.update(capture(kept)) }
        assertThat(kept.last().topics).isEqualTo(JsonColumns.encodeStrings(listOf("health")))
    }

    // ── a ticket lost on process death is rebuilt from the stored document ──

    @Test
    fun `a lost ticket is rebuilt from the stored family, topics and first stage fields, so the second stage still runs`() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns doc().copy(extractionType = "invoice_bill", topics = JsonColumns.encodeStrings(listOf("insurance")))
        val asked = slot<EnrichmentTicket>()
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), capture(asked), any()) } returns PamResult.Success(understanding())

        val result = pipeline.enrichDocument("doc-1", null)

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(asked.captured.typeId).isEqualTo("invoice_bill")
        assertThat(asked.captured.topics).containsExactly("insurance")
        // The facts the summary rests on are the stored first stage values; the candidate ids are not stored, so the values stand for them.
        assertThat(asked.captured.facts).containsEntry("sender", "Stadtwerke")
        assertThat(asked.captured.facts).containsEntry("amount", "64,98 EUR")
        assertThat(asked.captured.takenValues).containsAtLeast("Stadtwerke", "64,98 EUR")
        // What the second stage owns (an extra, the subject) is not "taken".
        assertThat(asked.captured.takenValues).doesNotContain("Basis")
        // And the result is written like any second stage's.
        coVerify { documentDao.update(any()) }
    }

    @Test
    fun `a value a person ignored is no fact of a rebuilt ticket`() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns doc().copy(extractionType = "invoice_bill")
        val ignoredTotal = total.copy(reviewState = ReviewState.IGNORED, deletedByUser = true)
        coEvery { documentDao.getExtractedData("doc-1") } returns listOf(sender, ignoredTotal).map(mapper::extractedDataToEntity)
        val asked = slot<EnrichmentTicket>()
        coEvery { aiExtraction(any(), any(), any(), any(), any(), any(), capture(asked), any()) } returns PamResult.Success(understanding())
        pipeline.enrichDocument("doc-1", null)
        assertThat(asked.captured.facts).doesNotContainKey("amount")
        assertThat(asked.captured.takenValues).doesNotContain("64,98 EUR")
    }

    @Test
    fun `a confirmed Subject row an older reading stored is paired with the new subject line, not replaced by it`() = runTest(dispatcher) {
        // Stored before slot keys: no slot key, so only its name says it is the subject line.
        val legacySubject = field("f-legacy-subject", "Subject", "Mein Betreff", slot = null, source = ValueSource.USER, confirmed = true)
        coEvery { documentDao.getExtractedData("doc-1") } returns listOf(sender, legacySubject).map(mapper::extractedDataToEntity)
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)

        val persisted = slot<List<ExtractedDataEntity>>()
        coVerify { documentDao.insertExtractedData(capture(persisted)) }
        val subjects = persisted.captured.filter { it.fieldName == "Subject" }
        // One row carries the name, and it is the person's: a second "Subject" row would make the insert delete theirs.
        assertThat(subjects.map { it.id }).containsExactly("f-legacy-subject")
        assertThat(subjects.single().fieldValue).isEqualTo("Mein Betreff")
        assertThat(subjects.single().source).isEqualTo(ValueSource.USER.name)
        coVerify(exactly = 0) { documentDao.deleteExtractedField("f-legacy-subject") }
    }

    @Test
    fun `the second stage is owed until the retries run out`() = runTest(dispatcher) {
        val stored = statefulDocument(doc(titleCode = null).copy(extractionType = "invoice_bill", enrichmentPending = true))
        answer(understanding().copy(modelUsed = false))
        for (attempt in 1 until EnrichmentRetryPolicy.MAX_ATTEMPTS) {
            pipeline.enrichDocument("doc-1", null)
            assertThat(stored().enrichmentPending).isTrue()
        }
        pipeline.enrichDocument("doc-1", null)
        assertThat(stored().enrichmentPending).isFalse()
    }

    @Test
    fun `a second stage that wrote its summary clears the pending flag, even over an earlier summary`() = runTest(dispatcher) {
        val stored = statefulDocument(
            doc().copy(extractionType = "invoice_bill", enrichmentPending = true, summary = "Alt", summarySource = SummarySource.MODEL.name),
        )
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)
        assertThat(stored().enrichmentPending).isFalse()
        assertThat(stored().summary).isEqualTo("Eine Rechnung über 64,98 EUR.")
    }

    @Test
    fun `a document no model has read has nothing to enrich, ticket or not`()= runTest(dispatcher) {
        // extractionType is null: the first stage never ran, so there is no reading to complete.
        val result = pipeline.enrichDocument("doc-1", null)
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { aiExtraction(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a second stage queued without a ticket is kept if one is queued, and the screens wait for it`() = runTest(dispatcher) {
        val workManager = mockk<WorkManager>(relaxed = true)
        io.mockk.mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        try {
            pipeline.enqueueEnrichment("doc-1")
            verify { workManager.enqueueUniqueWork(DocumentEnrichmentWorker.workName("doc-1"), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
            assertThat(pipeline.enrichingDocuments.first()).containsExactly("doc-1")
            // Once it ran (or failed), nothing is coming any more.
            coEvery { documentDao.getById("doc-1") } returns doc().copy(extractionType = "invoice_bill")
            answer(understanding())
            pipeline.enrichDocument("doc-1", null)
            assertThat(pipeline.enrichingDocuments.first()).isEmpty()
        } finally {
            io.mockk.unmockkObject(WorkManager.Companion)
        }
    }

    @Test
    fun `a second stage the model did not write changes only the attempt count and the screens stop waiting`() = runTest(dispatcher) {
        answer(understanding().copy(modelUsed = false))
        val result = pipeline.enrichDocument("doc-1", ticket)
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { documentDao.insertExtractedData(any()) }
        val updated = slot<DocumentEntity>()
        coVerify(exactly = 1) { documentDao.update(capture(updated)) }
        assertThat(updated.captured.enrichmentAttempts).isEqualTo(1)
        assertThat(updated.captured.title).isEqualTo("Scan 30 Sep")
        assertThat(updated.captured.summarySource).isNull()
        assertThat(pipeline.enrichingDocuments.first()).isEmpty()
    }

    @Test
    fun `a document trashed while it waited is left alone`() = runTest(dispatcher) {
        coEvery { documentDao.getById("doc-1") } returns doc().copy(deletedAt = 5L)
        val result = pipeline.enrichDocument("doc-1", ticket)
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { aiExtraction(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { documentDao.update(any()) }
    }

    // ── the order of the stages, and a scan going first ──

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val ocrService = mockk<OcrService>()
    private val scanPipeline = DocumentProcessingPipeline(
        ocrService = ocrService,
        indexDocument = mockk<IndexDocumentUseCase>().also {
            coEvery { it(any<String>(), any<List<IndexDocumentUseCase.PageText>>()) } returns
                PamResult.Success(IndexDocumentUseCase.Result("doc", chunkCount = 1, embedded = false))
        },
        mergeExtraction = MergeExtractionUseCase(),
        aiExtraction = aiExtraction,
        entityProfileLinker = mockk<EntityProfileLinker>(relaxed = true),
        concernedPeopleDecision = mockk(relaxed = true),
        fieldRevisionDao = mockk<FieldRevisionDao>(relaxed = true),
        documentMapper = mapper,
        documentDao = documentDao,
        timelineRepository = FakeTimelineRepository(),
        appContext = mockk<Context>(relaxed = true),
        ioDispatcher = dispatcher,
    )

    private fun firstStage() = DocumentUnderstanding(
        documentType = "bill", modelUsed = true,
        facts = listOf(RecognisedFact("Total", "64,98 EUR", FactKind.AMOUNT, 0.9f, FieldProvenance(slotKey = "total"))),
        enrichment = ticket,
    )

    private fun scanOf(id: String) {
        coEvery { documentDao.getById(id) } returns doc().copy(id = id, status = DocumentStatus.QUEUED.name)
        coEvery { documentDao.getPages(id) } returns listOf(page.copy(id = "p-$id", documentId = id, ocrText = null, ocrBlocks = null))
        coEvery { documentDao.getExtractedData(id) } returns emptyList()
    }

    @Test
    fun `the first stage is stored and shown before the second is queued, and a scan pushes it aside and brings it back`() = runTest(dispatcher) {
        io.mockk.mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        try {
            coEvery { ocrService.recognizeText(any()) } returns PamResult.Success(
                OcrResult(fullText = "Rechnung 64,98 EUR", confidence = 0.9f, blocks = listOf(block), detectedLanguage = "de"),
            )
            answer(firstStage())
            scanOf("doc-1")
            scanOf("doc-2")

            scanPipeline.processDocument("doc-1")

            // Stored as EXTRACTED first, only then is the second stage queued (replacing one queued before for the same document).
            io.mockk.coVerifyOrder {
                documentDao.updateStatus("doc-1", DocumentStatus.EXTRACTED.name, any())
                workManager.enqueueUniqueWork(DocumentEnrichmentWorker.workName("doc-1"), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
            }
            // The first stage was asked for as the first stage, and what it stored has no summary yet.
            coVerify { aiExtraction(any(), any(), any(), any(), any(), ExtractionV2Pipeline.Stages.FIRST, any()) }
            assertThat(scanPipeline.enrichingDocuments.first()).containsExactly("doc-1")

            // A new scan never waits for a summary: every second stage is cancelled, and the screens still know it is coming.
            scanPipeline.enqueue("doc-2")
            verify { workManager.cancelAllWorkByTag(DocumentEnrichmentWorker.TAG) }
            assertThat(scanPipeline.enrichingDocuments.first()).containsExactly("doc-1")

            // Once that scan's first stage is stored, the second stage that was pushed aside is queued again (kept if still queued).
            scanPipeline.processDocument("doc-2")
            verify { workManager.enqueueUniqueWork(DocumentEnrichmentWorker.workName("doc-1"), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        } finally {
            io.mockk.unmockkObject(WorkManager.Companion)
        }
    }

    @Test
    fun `a reading that is complete queues no second stage`() = runTest(dispatcher) {
        io.mockk.mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        try {
            coEvery { ocrService.recognizeText(any()) } returns PamResult.Success(
                OcrResult(fullText = "Rechnung", confidence = 0.9f, blocks = listOf(block), detectedLanguage = "de"),
            )
            answer(firstStage().copy(enrichment = null))
            scanOf("doc-1")
            scanPipeline.processDocument("doc-1")
            // No second stage; the one work queued is the quiet "who is this letter for or about?" check.
            verify(exactly = 0) { workManager.enqueueUniqueWork(DocumentEnrichmentWorker.workName("doc-1"), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
            verify(exactly = 1) { workManager.enqueueUniqueWork(DocumentEnrichmentWorker.peopleWorkName("doc-1"), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
            assertThat(scanPipeline.enrichingDocuments.first()).isEmpty()
        } finally {
            io.mockk.unmockkObject(WorkManager.Companion)
        }
    }

    @Test
    fun `a document whose text was not stored cannot be read a second time`() = runTest(dispatcher) {
        coEvery { documentDao.getPages("doc-1") } returns listOf(page.copy(ocrBlocks = null))
        val result = pipeline.enrichDocument("doc-1", ticket)
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { aiExtraction(any(), any(), any(), any(), any(), any(), any()) }
    }
}
