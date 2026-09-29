package com.postsaimanager.core.ai.embed

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.VectorMath
import com.postsaimanager.core.domain.usecase.TextChunker
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
 * What similarity scores this model actually produces on this corpus.
 *
 * `RetrieveChunksUseCase.MIN_SEMANTIC_SIMILARITY` is a threshold on a number nobody had
 * measured — it was carried over from the sentence-pair figures, where a related pair
 * scores ~0.42. A question matched against a *paragraph* is a different distribution: mean
 * pooling over several hundred tokens dilutes the vector, so the same relationship scores
 * lower. A threshold set from the wrong distribution silently discards every semantic hit,
 * which is exactly what it did.
 *
 * This test asserts the **ordering** that retrieval depends on and logs the absolute values
 * so the threshold can be set from evidence.
 */
@RunWith(AndroidJUnit4::class)
class SimilarityCalibrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val files = EmbeddingModelFiles(context)
    private val stagedModel = File("/data/local/tmp/embed-model.onnx")
    private val stagedVocab = File("/data/local/tmp/embed-vocab.txt")
    private val tag = "pam_spike"

    private val letter = """
        Jobcenter Berlin Mitte
        Aktenzeichen BG 1234/5678

        Sehr geehrte Frau Mustermann,

        Ihr Widerspruch vom 12.01.2026 gegen unseren Bescheid vom 03.12.2025 wurde
        geprüft. Nach eingehender Prüfung der von Ihnen vorgetragenen Gründe und der
        beigefügten Nachweise können wir Ihrem Anliegen leider nicht entsprechen. Der
        Widerspruch wird daher als unbegründet zurückgewiesen. Die Entscheidung stützt
        sich auf die uns vorliegenden Einkommensnachweise des Vorjahres.

        Gegen diesen Widerspruchsbescheid können Sie innerhalb eines Monats nach
        Bekanntgabe Klage beim Sozialgericht Berlin erheben. Die Klage ist schriftlich
        oder zur Niederschrift des Urkundsbeamten der Geschäftsstelle zu erheben. Eine
        Rechtsberatung durch unsere Mitarbeiter ist aus rechtlichen Gründen nicht
        zulässig.

        Bitte reichen Sie die noch fehlenden Unterlagen bis zum 31.01.2026 bei uns ein.
        Andernfalls müssen wir die laufenden Leistungen vorläufig einstellen, bis die
        Angaben vollständig vorliegen. Maßgeblich ist der Eingang bei der zuständigen
        Stelle, nicht das Datum des Poststempels.

        Ihre monatliche Regelleistung beträgt ab dem 01.02.2026 voraussichtlich 563,00
        Euro. Die Anpassung erfolgt automatisch, ein gesonderter Antrag ist hierfür
        nicht erforderlich. Über Änderungen Ihrer Einkommensverhältnisse sind wir
        unverzüglich zu unterrichten.

        Für Rückfragen steht Ihnen Herr Schmidt unter der Rufnummer 030 12345678 zur
        Verfügung. Unsere Sprechzeiten sind montags bis donnerstags von 8 bis 16 Uhr
        sowie freitags von 8 bis 12 Uhr.

        Mit freundlichen Grüßen
    """.trimIndent()

    /** Question, and a string that identifies the paragraph that ought to answer it. */
    private val questions = listOf(
        "Bis wann muss ich die fehlenden Papiere abgeben?" to "31.01.2026",
        "Wie viel Geld bekomme ich im Monat?" to "563,00",
        "Kann ich gegen die Entscheidung vor Gericht gehen?" to "Sozialgericht",
        "Wann kann ich dort anrufen?" to "Sprechzeiten",
        "Wurde mein Einspruch akzeptiert?" to "zurückgewiesen",
    )

    @Before
    fun installModel() {
        assumeTrue("No model staged", stagedModel.exists() && stagedVocab.exists())
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
    fun theRightParagraphOutscoresTheOthers() = runBlocking {
        val embedder = LazyEmbeddingService(files, OnnxEmbeddingService(Dispatchers.IO))
        val chunks = TextChunker.chunk(letter)
        val chunkVectors = (embedder.embedAll(chunks.map { it.text }) as PamResult.Success).data

        Log.i(tag, "calib chunks=${chunks.size}")

        var correct = 0
        for ((question, expectedMarker) in questions) {
            val queryVector = (embedder.embed(question) as PamResult.Success).data

            val scored = chunks.indices
                .map { i -> i to VectorMath.cosineSimilarity(queryVector, chunkVectors[i]) }
                .sortedByDescending { it.second }

            val bestIndex = scored.first().first
            val hit = chunks[bestIndex].text.contains(expectedMarker)
            if (hit) correct++

            Log.i(
                tag,
                "calib q=\"$question\" best=${"%.3f".format(scored.first().second)} " +
                    "second=${"%.3f".format(scored[1].second)} " +
                    "worst=${"%.3f".format(scored.last().second)} correct=$hit",
            )
        }

        Log.i(tag, "calib summary correct=$correct/${questions.size}")

        // Ranking is what retrieval needs. The absolute values only decide the threshold,
        // and are logged above rather than asserted, since they shift with the model.
        assertTrue(
            "the model ranked the right paragraph first for only $correct of " +
                "${questions.size} questions",
            correct >= 4,
        )
    }
}
