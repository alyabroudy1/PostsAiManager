package com.postsaimanager.core.data.repository

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsRequest
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialReading
import com.postsaimanager.core.domain.extraction.gemma.PaidState
import com.postsaimanager.core.domain.extraction.text.KeyInfoVerifier
import com.postsaimanager.core.domain.extraction.text.SummaryFacts
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.SummarySource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The text step of a stored Gemma reading: the summary and the key facts, written afterwards, stored without touching the reading. */
class GemmaTextStageTest {

    private val documentDao = mockk<DocumentDao>(relaxed = true)
    private val revisions = mockk<FieldRevisionDao>(relaxed = true)
    private val gemma = mockk<GemmaTrialReading>()
    private val stage = GemmaTextStage(gemma, documentDao, revisions, DocumentMapper(), MergeExtractionUseCase())

    private fun doc(deletedAt: Long? = null, extractionType: String? = "receipt", summary: String? = null) = DocumentEntity(
        id = "doc-1", title = "Markt Beispiel", status = DocumentStatus.EXTRACTED.name, documentType = null, language = "de", sourceType = "CAMERA",
        thumbnailPath = null, pageCount = 1, createdAt = 0L, modifiedAt = 0L, extractorVersion = "x", extractionType = extractionType,
        deletedAt = deletedAt, summary = summary, enrichmentPending = true,
    )

    private val page = DocumentPageEntity(
        id = "p1", documentId = "doc-1", pageNumber = 1, imagePath = "/p1.jpg", processedPath = null,
        ocrText = "Markt Beispiel\nKassenbon 14,18 EUR\nVielen Dank fuer Ihren Einkauf\nBon-Nr 4711", ocrConfidence = 0.8f, ocrBlocks = null, width = 10, height = 10,
    )

    private val ticket = EnrichmentTicket(oneGo = true, paid = "already_paid")

    private fun written(summary: SummaryResult = SummaryResult("Kassenbon über 14,18 EUR.", SummarySource.MODEL, null, emptyList()), vararg facts: Pair<String, String>) =
        GemmaTextsOutcome.Done(GemmaTextOutcome.Written(summary, facts.map { KeyInfoVerifier.Kept(it.first, it.second) }, 12L, emptyList()))

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        coEvery { documentDao.getById("doc-1") } returns doc()
        coEvery { documentDao.getPages("doc-1") } returns listOf(page)
        coEvery { documentDao.getExtractedData("doc-1") } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    @DisplayName("the summary is stored on the document, the key facts as extras, and nothing is owed any more")
    fun `stored`() = runBlocking {
        coEvery { gemma.writeTexts(any()) } returns written(facts = arrayOf("Bon-Nr" to "4711"))
        val updated = slot<DocumentEntity>()
        val inserted = slot<List<ExtractedDataEntity>>()

        val result = stage.run("doc-1", ticket)

        assertThat(result).isSameInstanceAs(GemmaTextStageResult.Stored)
        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.summary).isEqualTo("Kassenbon über 14,18 EUR.")
        assertThat(updated.captured.summarySource).isEqualTo(SummarySource.MODEL.name)
        assertThat(updated.captured.enrichmentPending).isFalse()
        // The reading itself is untouched.
        assertThat(updated.captured.extractionType).isEqualTo("receipt")
        assertThat(updated.captured.title).isEqualTo("Markt Beispiel")
        coVerify { documentDao.insertExtractedData(capture(inserted)) }
        assertThat(inserted.captured.map { it.fieldName to it.fieldValue }).containsExactly("Bon-Nr" to "4711")
        assertThat(inserted.captured.single().slotKey).startsWith("x:")
    }

    @Test
    @DisplayName("the request carries what the reading decided: the letter's text, whether it was paid, the document's language")
    fun `request`() = runBlocking {
        coEvery { gemma.writeTexts(any()) } returns written()
        val request = slot<GemmaTextsRequest>()

        stage.run("doc-1", ticket)

        coVerify { gemma.writeTexts(capture(request)) }
        assertThat(request.captured.oneGo).isTrue()
        assertThat(request.captured.text.paid).isEqualTo(PaidState.ALREADY_PAID)
        assertThat(request.captured.text.languageCode).isEqualTo("de")
        assertThat(request.captured.text.ocrText).contains("Kassenbon 14,18 EUR")
    }

    @Test
    @DisplayName("a template summary (no sentence passed its check) settles the summary as well")
    fun `template`() = runBlocking {
        coEvery { gemma.writeTexts(any()) } returns written(SummaryWriter.templateOf(SummaryFacts("receipt")))
        val updated = slot<DocumentEntity>()

        assertThat(stage.run("doc-1", ticket)).isSameInstanceAs(GemmaTextStageResult.Stored)

        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.summarySource).isEqualTo(SummarySource.TEMPLATE.name)
        assertThat(updated.captured.summaryCode).isEqualTo(SummaryWriter.TEMPLATE_CODE)
        assertThat(updated.captured.enrichmentPending).isFalse()
    }

    @Test
    @DisplayName("a text step that could not run stores nothing and says why, so the attempt is counted and asked again")
    fun `failed`() = runBlocking {
        coEvery { gemma.writeTexts(any()) } returns GemmaTextsOutcome.Done(GemmaTextOutcome.Unavailable("no answer"))

        val result = stage.run("doc-1", ticket)

        assertThat((result as GemmaTextStageResult.Failed).reason).isEqualTo("no answer")
        coVerify(exactly = 0) { documentDao.update(any()) }
        coVerify(exactly = 0) { documentDao.insertExtractedData(any()) }
    }

    @Test
    @DisplayName("a ticket of the staged second stage is not Gemma's: the usual second stage runs")
    fun `staged ticket`() = runBlocking {
        val result = stage.run("doc-1", EnrichmentTicket(typeId = "bill"))

        assertThat(result).isSameInstanceAs(GemmaTextStageResult.NotGemma)
        coVerify(exactly = 0) { gemma.writeTexts(any()) }
    }

    @Test
    @DisplayName("with no ticket (lost) and the old reader chosen, the usual second stage runs")
    fun `no ticket and old reader`() = runBlocking {
        coEvery { gemma.writeTexts(any()) } returns GemmaTextsOutcome.NotGemma

        assertThat(stage.run("doc-1", null)).isSameInstanceAs(GemmaTextStageResult.NotGemma)
    }

    @Test
    @DisplayName("a document trashed meanwhile is gone: nothing is written")
    fun `gone`() = runBlocking {
        coEvery { documentDao.getById("doc-1") } returns doc(deletedAt = 5L)

        assertThat(stage.run("doc-1", ticket)).isSameInstanceAs(GemmaTextStageResult.Gone)
        coVerify(exactly = 0) { gemma.writeTexts(any()) }
    }

    @Test
    @DisplayName("a summary a person wrote is never replaced")
    fun `a person's summary stays`() = runBlocking {
        coEvery { documentDao.getById("doc-1") } returns doc(summary = "Mein Text").copy(summarySource = SummarySource.USER.name)
        coEvery { gemma.writeTexts(any()) } returns written()
        val updated = slot<DocumentEntity>()

        stage.run("doc-1", ticket)

        coVerify { documentDao.update(capture(updated)) }
        assertThat(updated.captured.summary).isEqualTo("Mein Text")
        assertThat(updated.captured.enrichmentPending).isFalse()
    }
}
