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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Retrieval over **OCR output from a real scan**, not hand-written prose.
 *
 * Every other test here feeds the encoder text that was typed. This one uses the exact
 * string ML Kit produced from a photographed German letter, umlaut errors and all —
 * "Müllerstralße" for "Müllerstraße", "gesondeter" for "gesonderter". That matters because
 * a WordPiece vocabulary has no entry for a misspelling: the damaged word falls apart into
 * subword pieces or becomes `[UNK]`, and the question is whether enough meaning survives
 * for the right passage to still win.
 *
 * The text is read from disk rather than embedded as a literal, so it stays byte-identical
 * to what the app stored instead of passing through a source file's escaping.
 *
 * ```
 * adb push ocr.txt /data/local/tmp/real-ocr.txt
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RealScannedLetterTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val files = EmbeddingModelFiles(context)
    private val stagedModel = File("/data/local/tmp/embed-model.onnx")
    private val stagedVocab = File("/data/local/tmp/embed-vocab.txt")
    private val ocrFile = File("/data/local/tmp/real-ocr.txt")
    private val tag = "pam_spike"

    @Before
    fun installModel() {
        assumeTrue("No model staged", stagedModel.exists() && stagedVocab.exists())
        assumeTrue("No OCR text at ${ocrFile.path}", ocrFile.exists())
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

    @Test
    fun answersQuestionsAboutAScannedLetter() = runBlocking {
        val ocrText = ocrFile.readText()
        val embedder = LazyEmbeddingService(files, OnnxEmbeddingService(Dispatchers.IO))
        val repository = FakeDocumentChunkRepository()

        val indexed = IndexDocumentUseCase(repository, embedder)("scan-1", ocrText)
        val summary = (indexed as PamResult.Success).data
        Log.i(tag, "real chars=${ocrText.length} chunks=${summary.chunkCount} " +
            "embedded=${summary.embedded}")

        assertTrue("no vectors were stored", summary.embedded)
        // Must split, or "the right chunk won" is vacuous.
        assertTrue("letter did not split", summary.chunkCount >= 2)

        val retrieve = RetrieveChunksUseCase(repository, embedder)

        // Each question is phrased the way someone would actually ask it, deliberately
        // avoiding the letter's own wording. "Frist" and "abgeben" do not occur in the
        // text; it says "reichen Sie ... bis zum ... ein".
        val questions = listOf(
            Triple("Bis wann muss ich die fehlenden Papiere abgeben?", "31.01.2026", "deadline"),
            Triple("Wie viel Geld bekomme ich jeden Monat?", "563,00", "amount"),
            Triple("Kann ich gegen die Entscheidung vor Gericht gehen?", "Sozialgericht", "appeal"),
        )

        // Which chunk holds which answer. Overlap means a marker can land in more than
        // one chunk, and a question whose answer sits in every chunk proves nothing about
        // ranking — the first version of this test "passed" 3/3 that way, with all three
        // questions returning the same chunk.
        val stored = repository.getForDocument("scan-1").sortedBy { it.ordinal }
        val ownership = questions.associate { (_, marker, label) ->
            label to stored.filter { marker in it.text }.map { it.ordinal }
        }
        ownership.forEach { (label, ordinals) ->
            Log.i(tag, "real marker=$label lives in chunks=$ordinals of ${stored.size}")
        }

        val discriminating = questions.filter { (_, _, label) ->
            ownership[label]?.size == 1
        }
        Log.i(
            tag,
            "real discriminating=${discriminating.map { it.third }} " +
                "ambiguous=${questions.filterNot { it in discriminating }.map { it.third }}",
        )

        var correct = 0
        for ((question, marker, label) in questions) {
            val result = retrieve(question)
            val best = result.chunks.firstOrNull()
            val hit = best?.chunk?.text?.contains(marker) == true
            if (hit) correct++

            Log.i(
                tag,
                "real q=$label hit=$hit ordinal=${best?.chunk?.ordinal} " +
                    "semantic=${best?.matchedSemantically} keyword=${best?.matchedByKeyword}",
            )
        }

        Log.i(tag, "real summary correct=$correct/${questions.size}")

        // Only the questions whose answer lives in exactly one chunk can say anything
        // about ranking. If none do, the document is too short to discriminate within —
        // which is a real limit worth failing on rather than papering over with a
        // trivially-satisfied assertion.
        assertTrue(
            "no question had a unique answer chunk, so this test cannot prove ranking; " +
                "chunk sizes and the document length make every answer ambiguous",
            discriminating.isNotEmpty(),
        )

        for ((question, marker, label) in discriminating) {
            val expected = ownership.getValue(label).single()
            val best = retrieve(question).chunks.firstOrNull()
            assertTrue(
                "for \"$label\" the answer is only in chunk $expected but retrieval " +
                    "returned chunk ${best?.chunk?.ordinal}",
                best?.chunk?.ordinal == expected,
            )
        }

        // Every question still has to land on a chunk containing its answer.
        assertTrue(
            "retrieval found the right passage for only $correct of ${questions.size} " +
                "questions about a real scanned letter",
            correct >= 2,
        )
    }
}
