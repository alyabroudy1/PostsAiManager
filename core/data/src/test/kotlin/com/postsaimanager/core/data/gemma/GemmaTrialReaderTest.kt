package com.postsaimanager.core.data.gemma

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.gemma.GemmaReadingOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaReadingUseCase
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialRequest
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

/** The trial's seam in the pipeline: off means untouched, on means Gemma's reading or, when Gemma cannot read, null (the old reading runs). */
class GemmaTrialReaderTest {

    private val trial = FakeGemmaReaderTrial()
    private val reading = mockk<GemmaReadingUseCase>()
    private val images = FakeChatImageStore()
    private val reader = GemmaTrialReader(trial, reading, images)
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
    @DisplayName("with the trial off nothing happens: no reading, no picture touched, the old reading runs")
    fun `off is untouched`() = runBlocking {
        val result = reader.read(request())

        assertThat(result).isNull()
        coVerify(exactly = 0) { reading(any(), any(), any(), any()) }
        assertThat(images.stored).isEmpty()
    }

    @Test
    @DisplayName("a quiet background re-read is never the trial's, and it does not spend a request meant for a person's tap")
    fun `reprocess is not the trial's`() = runBlocking {
        trial.setEnabled(true)
        trial.requestOnce("doc-1")

        val result = reader.read(request(reprocess = true))

        assertThat(result).isNull()
        coVerify(exactly = 0) { reading(any(), any(), any(), any()) }
        assertThat(trial.isRequested("doc-1")).isTrue()
    }

    @Test
    @DisplayName("with the trial on the first two pages go to the reader as pictures and the understanding comes back; the pictures are removed")
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
    @DisplayName("a one-off request reads with the switch off, and is spent")
    fun `one off request`() = runBlocking {
        trial.requestOnce("doc-1")
        coEvery { reading(any(), any(), any(), any()) } returns GemmaReadingOutcome.Read(understanding, "")

        assertThat(reader.read(request())).isNotNull()
        assertThat(reader.read(request())).isNull()
    }

    @Test
    @DisplayName("when Gemma cannot read (not installed, busy, failed, too slow) the answer is null, so the usual reading runs")
    fun `unavailable falls back`() = runBlocking {
        trial.setEnabled(true)
        coEvery { reading(any(), any(), any(), any()) } returns GemmaReadingOutcome.Unavailable("no chat model is installed")

        val result = reader.read(request())

        assertThat(result).isNull()
        assertThat(images.stored).isEmpty()
    }
}
