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
import com.postsaimanager.core.model.FieldProvenance
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.RecognisedFact
import com.postsaimanager.core.model.TextBounds
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
            aiExtraction(listOf(block), any(), listOf(1), any(), any(), ExtractionV2Pipeline.Stages.SECOND, ticket)
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
    fun `the language and summary land on the document and the model's title replaces a default title only`() = runTest(dispatcher) {
        answer(understanding())
        pipeline.enrichDocument("doc-1", ticket)
        val updated = slot<DocumentEntity>()
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.language).isEqualTo("de")
        assertThat(updated.captured.summary).isEqualTo("Eine Rechnung über 64,98 EUR.")
        assertThat(updated.captured.title).isEqualTo("Rechnung Stadtwerke")
        assertThat(updated.captured.titleCode).isNull()
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
        assertThat(updated.captured.summary).isNotNull()
    }

    @Test
    fun `a second stage the model did not write changes nothing and the screens stop waiting`() = runTest(dispatcher) {
        answer(understanding().copy(modelUsed = false))
        val result = pipeline.enrichDocument("doc-1", ticket)
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { documentDao.insertExtractedData(any()) }
        coVerify(exactly = 0) { documentDao.update(any()) }
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

    @Test
    fun `a document whose text was not stored cannot be read a second time`() = runTest(dispatcher) {
        coEvery { documentDao.getPages("doc-1") } returns listOf(page.copy(ocrBlocks = null))
        val result = pipeline.enrichDocument("doc-1", ticket)
        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        coVerify(exactly = 0) { aiExtraction(any(), any(), any(), any(), any(), any(), any()) }
    }
}
