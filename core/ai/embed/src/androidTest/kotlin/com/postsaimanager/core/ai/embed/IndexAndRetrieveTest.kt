package com.postsaimanager.core.ai.embed

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.RetrieveChunksUseCase
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole retrieval path on real vectors: chunk a German letter, embed it, store it, then
 * ask a question about it.
 *
 * Every layer below has been proven with fakes, where "related" and "unrelated" were decided
 * by the test. This is the one that can fail for the reason that actually matters — that the
 * model does not find the passage a person was asking about.
 *
 * Requires the model pushed first:
 * ```
 * adb push embed-model.onnx /data/local/tmp/embed-model.onnx
 * adb push embed-vocab.txt  /data/local/tmp/embed-vocab.txt
 * ```
 */
@RunWith(AndroidJUnit4::class)
class IndexAndRetrieveTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val files = EmbeddingModelFiles(context)
    private val stagedModel = File("/data/local/tmp/embed-model.onnx")
    private val stagedVocab = File("/data/local/tmp/embed-vocab.txt")
    private val tag = "pam_spike"

    /**
     * A letter of the kind this app exists for: the answer is present but phrased nothing
     * like the question anyone would ask about it.
     */
    private val letter = """
        Jobcenter Berlin Mitte
        Aktenzeichen BG 1234/5678

        Sehr geehrte Frau Mustermann,

        Ihr Widerspruch vom 12.01.2026 gegen unseren Bescheid wurde geprüft. Nach
        eingehender Prüfung der Sachlage können wir Ihrem Anliegen leider nicht
        entsprechen. Der Widerspruch wird daher zurückgewiesen.

        Bitte reichen Sie die noch fehlenden Unterlagen bis zum 31.01.2026 bei uns
        ein. Andernfalls müssen wir die Leistungen vorläufig einstellen.

        Für Rückfragen steht Ihnen Herr Schmidt unter der Rufnummer 030 12345678
        zur Verfügung.

        Mit freundlichen Grüßen
    """.trimIndent()

    @Before
    fun installModel() {
        assumeTrue("No model staged at ${stagedModel.path}", stagedModel.exists())
        assumeTrue("No vocab staged at ${stagedVocab.path}", stagedVocab.exists())

        // Into the app's own storage, exactly where the downloader will put it — so this
        // exercises the real path resolution rather than a test-only location.
        files.directory.mkdirs()
        if (!files.modelFile.exists() || files.modelFile.length() != stagedModel.length()) {
            stagedModel.copyTo(files.modelFile, overwrite = true)
        }
        if (!files.vocabFile.exists() || files.vocabFile.length() != stagedVocab.length()) {
            stagedVocab.copyTo(files.vocabFile, overwrite = true)
        }
    }

    @After
    fun removeModel() {
        files.directory.deleteRecursively()
    }

    private fun lazyService() = LazyEmbeddingService(files, OnnxEmbeddingService(Dispatchers.IO))

    @Test
    fun answersAQuestionThatSharesNoWordsWithTheAnswer() = runBlocking {
        val repository = FakeDocumentChunkRepository()
        val embedder = lazyService()
        val index = IndexDocumentUseCase(repository, embedder)
        val retrieve = RetrieveChunksUseCase(repository, embedder)

        val indexed = index("doc-1", letter)
        assertTrue("indexing failed: $indexed", indexed is PamResult.Success)
        val summary = (indexed as PamResult.Success).data
        assertTrue("stored without vectors", summary.embedded)

        Log.i(tag, "index chunks=${summary.chunkCount} embedded=${summary.embedded}")

        // "Frist" and "abgeben" appear nowhere in the letter, which says "reichen Sie ...
        // bis zum ... ein". Keyword search cannot answer this; only meaning can.
        val result = retrieve("Bis wann muss ich die Papiere abgeben?")

        assertTrue("semantic search did not run", result.semanticSearchUsed)
        assertTrue("nothing retrieved", result.chunks.isNotEmpty())

        val best = result.chunks.first()
        Log.i(
            tag,
            "retrieve top score=${best.score} semantic=${best.matchedSemantically} " +
                "text=${best.chunk.text.take(80).replace('\n', ' ')}",
        )

        assertTrue(
            "the top passage does not contain the deadline: ${best.chunk.text}",
            best.chunk.text.contains("31.01.2026"),
        )
    }

    @Test
    fun findsAReferenceNumberThatEmbeddingsWouldBlurTogether() = runBlocking {
        val repository = FakeDocumentChunkRepository()
        val embedder = lazyService()
        IndexDocumentUseCase(repository, embedder)("doc-1", letter)

        // The other half of hybrid retrieval. Reference numbers sit almost on top of each
        // other in vector space, so this is the keyword side earning its place.
        val result = RetrieveChunksUseCase(repository, embedder)("BG 1234/5678")

        assertTrue("nothing retrieved", result.chunks.isNotEmpty())
        assertTrue(
            "top passage lacks the reference: ${result.chunks.first().chunk.text}",
            result.chunks.first().chunk.text.contains("BG 1234/5678"),
        )
    }

    @Test
    fun loadsOnFirstUseRatherThanOnConstruction() = runBlocking {
        val delegate = OnnxEmbeddingService(Dispatchers.IO)
        val service = LazyEmbeddingService(files, delegate)

        // Installed, so retrieval will attempt it — but nothing is in memory yet.
        assertTrue("should report ready with the model installed", service.isReady)
        assertFalse("model loaded before it was needed", delegate.isReady)

        // The id has to be right *before* the first load: retrieval compares it against
        // every stored vector's model id, and "none" would discard the entire index.
        assertEquals(files.modelId, service.modelId)

        val embedded = service.embed("Der Widerspruch wurde zurückgewiesen.")

        assertTrue("embed failed: $embedded", embedded is PamResult.Success)
        assertTrue("model still not loaded after use", delegate.isReady)
        assertEquals(files.modelId, service.modelId)
    }

    @Test
    fun withoutAnInstalledModelItDegradesInsteadOfFailing() = runBlocking {
        files.directory.deleteRecursively()

        val embedder = lazyService()
        assertFalse("reported ready with no model on disk", embedder.isReady)

        val repository = FakeDocumentChunkRepository()
        val indexed = IndexDocumentUseCase(repository, embedder)("doc-1", letter)

        // The document is still indexed, just without vectors — a missing optional model
        // must not cost the user their scan.
        val summary = (indexed as PamResult.Success).data
        assertFalse(summary.embedded)
        assertTrue(summary.chunkCount > 0)

        // And it is still findable by keyword, which is the whole point of storing the
        // text when the model is absent.
        val result = RetrieveChunksUseCase(repository, embedder)("Widerspruch")
        assertFalse(result.semanticSearchUsed)
        assertTrue("keyword search found nothing", result.chunks.isNotEmpty())
    }
}
