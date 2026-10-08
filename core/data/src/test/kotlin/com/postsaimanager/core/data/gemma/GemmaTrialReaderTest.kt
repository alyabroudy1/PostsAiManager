package com.postsaimanager.core.data.gemma

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.gemma.GemmaReadingOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaReadingUseCase
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextRequest
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextWriter
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsRequest
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialRequest
import com.postsaimanager.core.domain.extraction.gemma.PaidState
import com.postsaimanager.core.domain.extraction.text.SummaryFacts
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.testing.FakeChatImageStore
import com.postsaimanager.core.testing.FakeGemmaReaderTrial
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The Gemma reader's seam in the pipeline: Gemma by default, or null (the old reading runs) when the old reader is chosen or Gemma cannot read. */
class GemmaTrialReaderTest {

    private val trial = FakeGemmaReaderTrial()
    private val reading = mockk<GemmaReadingUseCase>()
    private val images = FakeChatImageStore()
    private val texts = mockk<GemmaTextWriter>()
    private val reader = GemmaTrialReader(trial, reading, images, texts)
    private val understanding = DocumentUnderstanding(documentType = "invoice_bill", modelUsed = true)
    private val blocks = listOf(OcrBlock("Rechnung", TextBounds(0.1f, 0.1f, 0.5f, 0.2f), 0.9f))

    private fun request(reprocess: Boolean = false, pictures: List<String> = listOf("/p1.jpg", "/p2.jpg", "/p3.jpg")) = GemmaTrialRequest(
        documentId = "doc-1", pages = pictures.map { blocks }, pageImagePaths = pictures, pageAspect = 0.7f, forcedFamily = null, reprocess = reprocess,
    )

    @BeforeEach
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @AfterEach
    fun unmockLog() {
        unmockkStatic(Log::class)
    }

    @Test
    @DisplayName("Gemma is the default reader: nothing is switched on, and a new document is read by Gemma")
    fun `gemma is the default`() = runBlocking {
        coEvery { reading(any(), any(), any(), any()) } returns GemmaReadingOutcome.Read(understanding, "")

        assertThat(reader.read(request())).isSameInstanceAs(understanding)
    }

    @Test
    @DisplayName("with the old reader chosen nothing happens: no reading, no picture touched, the old reading runs")
    fun `old reader chosen is untouched`() = runBlocking {
        trial.setEnabled(false)

        val result = reader.read(request())

        assertThat(result).isNull()
        coVerify(exactly = 0) { reading(any(), any(), any(), any()) }
        assertThat(images.stored).isEmpty()
    }

    @Test
    @DisplayName("a quiet background re-read is Gemma's too (it waits behind the user's own work in the pipeline's lock)")
    fun `reprocess reads with gemma`() = runBlocking {
        coEvery { reading(any(), any(), any(), any()) } returns GemmaReadingOutcome.Read(understanding, "")

        val result = reader.read(request(reprocess = true))

        assertThat(result).isSameInstanceAs(understanding)
    }

    @Test
    @DisplayName("a quiet background re-read falls back to the old reading when Gemma is not installed")
    fun `reprocess falls back when gemma is missing`() = runBlocking {
        coEvery { reading(any(), any(), any(), any()) } returns GemmaReadingOutcome.Unavailable("no chat model is installed")

        assertThat(reader.read(request(reprocess = true))).isNull()
    }

    @Test
    @DisplayName("with Gemma chosen the first two pages go to the reader as pictures and the understanding comes back; the pictures are removed")
    fun `on reads`() = runBlocking {
        trial.setEnabled(true)
        coEvery { reading(any(), any(), any(), any()) } returns GemmaReadingOutcome.Read(understanding, "category=invoice_bill")

        val result = reader.read(request())

        assertThat(result).isSameInstanceAs(understanding)
        coVerify {
            reading(
                match { it.size == 3 }, match { it.size == 2 && it.all { p -> p.startsWith("/chat-attachments/gemma-reading-doc-1/") } }, 0.7f, null,
            )
        }
        assertThat(images.stored).isEmpty()
    }

    @Test
    @DisplayName("a one-off request reads with the old reader chosen, and is spent")
    fun `one off request`() = runBlocking {
        trial.setEnabled(false)
        trial.requestOnce("doc-1")
        coEvery { reading(any(), any(), any(), any()) } returns GemmaReadingOutcome.Read(understanding, "")

        assertThat(reader.read(request())).isNotNull()
        assertThat(reader.read(request())).isNull()
    }

    private val textRequest = GemmaTextRequest("Rechnung 64,98 EUR", SummaryFacts("invoice_bill"), emptyList(), "de", PaidState.TO_PAY)
    private val written = GemmaTextOutcome.Written(SummaryResult("Eine Rechnung.", SummarySource.MODEL, null, emptyList()), emptyList(), 5L, emptyList())

    @Test
    @DisplayName("the text step writes for a reading Gemma's own ticket owns, whatever the switch says")
    fun `texts for a ticket`() = runBlocking {
        trial.setEnabled(false)
        coEvery { texts.write(any()) } returns written

        val outcome = reader.writeTexts(GemmaTextsRequest("doc-1", textRequest, oneGo = true))

        assertThat((outcome as GemmaTextsOutcome.Done).outcome).isSameInstanceAs(written)
    }

    @Test
    @DisplayName("without a ticket the text step writes only while Gemma is the chosen reader; with the old one chosen the usual second stage runs")
    fun `texts without a ticket`() = runBlocking {
        coEvery { texts.write(any()) } returns written

        assertThat(reader.writeTexts(GemmaTextsRequest("doc-1", textRequest, oneGo = false))).isInstanceOf(GemmaTextsOutcome.Done::class.java)

        trial.setEnabled(false)
        assertThat(reader.writeTexts(GemmaTextsRequest("doc-1", textRequest, oneGo = false))).isSameInstanceAs(GemmaTextsOutcome.NotGemma)
        coVerify(exactly = 1) { texts.write(any()) }
    }

    @Test
    @DisplayName("a text step that could not run is reported as unavailable, not as written")
    fun `texts unavailable`() = runBlocking {
        coEvery { texts.write(any()) } returns GemmaTextOutcome.Unavailable("no answer")

        val outcome = reader.writeTexts(GemmaTextsRequest("doc-1", textRequest, oneGo = true)) as GemmaTextsOutcome.Done

        assertThat(outcome.outcome).isInstanceOf(GemmaTextOutcome.Unavailable::class.java)
    }

    @Test
    @DisplayName("when Gemma cannot read (not installed, busy, failed, too slow, unusable JSON) the answer is null, so the old reading runs")
    fun `unavailable falls back`() = runBlocking {
        for (reason in listOf("no chat model is installed", "no answer (the model is busy, the run failed or it took longer than 120 s)", "the answer is not a JSON object")) {
            coEvery { reading(any(), any(), any(), any()) } returns GemmaReadingOutcome.Unavailable(reason)

            assertThat(reader.read(request())).isNull()
            assertThat(images.stored).isEmpty()
        }
    }
}
