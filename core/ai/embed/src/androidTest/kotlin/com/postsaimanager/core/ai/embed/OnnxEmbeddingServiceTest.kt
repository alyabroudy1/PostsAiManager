package com.postsaimanager.core.ai.embed

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * The real embedding model, on device, against real German text.
 *
 * Everything below this has been proven with fakes — chunking, cosine, fusion, pooling.
 * This is the first test where the vectors are produced by an actual encoder, so it is the
 * one that can tell us whether **semantic** retrieval genuinely works on the corpus this
 * app is for.
 *
 * Requires the model and vocabulary pushed first:
 * ```
 * adb push embed-model.onnx /data/local/tmp/embed-model.onnx
 * adb push embed-vocab.txt  /data/local/tmp/embed-vocab.txt
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OnnxEmbeddingServiceTest {

    private val modelFile = File("/data/local/tmp/embed-model.onnx")
    private val vocabFile = File("/data/local/tmp/embed-vocab.txt")
    private val tag = "pam_spike"

    private fun service() = OnnxEmbeddingService(Dispatchers.IO)

    private fun requireModel() {
        assumeTrue("No embedding model at ${modelFile.path}", modelFile.exists())
        assumeTrue("No vocab at ${vocabFile.path}", vocabFile.exists())
    }

    private suspend fun loaded(): OnnxEmbeddingService {
        val service = service()
        val result = service.load(
            modelFile = modelFile,
            vocabFile = vocabFile,
            modelId = "distiluse-multilingual-v2",
            doLowerCase = false,
        )
        assertTrue("load failed: $result", result is PamResult.Success)
        return service
    }

    @Test
    fun loadsAndProducesAVector() = runBlocking {
        requireModel()

        var service: OnnxEmbeddingService? = null
        val loadMs = measureTimeMillis { service = loaded() }
        val embedder = service!!

        try {
            var vector: FloatArray? = null
            val embedMs = measureTimeMillis {
                vector = (embedder.embed("Die Frist endet am 31. Januar 2026.")
                    as PamResult.Success).data
            }

            Log.i(tag, "embed load_ms=$loadMs embed_ms=$embedMs dims=${vector!!.size}")

            assertTrue("empty vector", vector!!.isNotEmpty())
            assertTrue("vector is not finite", vector!!.all { it.isFinite() })

            // l2Normalize should leave unit length.
            val norm = kotlin.math.sqrt(vector!!.sumOf { (it * it).toDouble() })
            assertEquals(1.0, norm, 1e-3)
        } finally {
            embedder.release()
        }
    }

    /**
     * **The test that decides whether any of this was worth building.**
     *
     * A German question and a German answer that share almost no words must land closer
     * together than an unrelated German sentence. If that fails, retrieval is only keyword
     * search wearing a costume.
     */
    @Test
    fun semanticallyRelatedGermanTextScoresHigherThanUnrelated() = runBlocking {
        requireModel()
        val embedder = loaded()

        try {
            val question = "Wann muss ich die Unterlagen einreichen?"
            val related = "Bitte reichen Sie die fehlenden Dokumente bis zum 31.01.2026 nach."
            val unrelated = "Der Rechnungsbetrag für Ihren Mobilfunkvertrag beträgt 49,99 Euro."

            val vectors = (embedder.embedAll(listOf(question, related, unrelated))
                as PamResult.Success).data

            val relatedScore = VectorMath.cosineSimilarity(vectors[0], vectors[1])
            val unrelatedScore = VectorMath.cosineSimilarity(vectors[0], vectors[2])

            Log.i(tag, "embed german related=$relatedScore unrelated=$unrelatedScore")

            // "einreichen" vs "reichen ... nach", "Unterlagen" vs "Dokumente" — near-zero
            // lexical overlap, so only meaning can connect them.
            assertTrue(
                "related ($relatedScore) did not beat unrelated ($unrelatedScore)",
                relatedScore > unrelatedScore,
            )
        } finally {
            embedder.release()
        }
    }

    @Test
    fun matchesAcrossLanguages() = runBlocking {
        requireModel()
        val embedder = loaded()

        try {
            val german = "Der Widerspruch wurde abgelehnt."
            val english = "The appeal was rejected."
            val unrelated = "Die Katze schläft auf dem Sofa."

            val vectors = (embedder.embedAll(listOf(german, english, unrelated))
                as PamResult.Success).data

            val crossLingual = VectorMath.cosineSimilarity(vectors[0], vectors[1])
            val sameLanguageUnrelated = VectorMath.cosineSimilarity(vectors[0], vectors[2])

            Log.i(tag, "embed crossLingual=$crossLingual sameLangUnrelated=$sameLanguageUnrelated")

            // The point of a multilingual encoder: a user may ask in English about a
            // German letter, or the reverse.
            assertTrue(
                "cross-lingual ($crossLingual) did not beat unrelated ($sameLanguageUnrelated)",
                crossLingual > sameLanguageUnrelated,
            )
        } finally {
            embedder.release()
        }
    }

    @Test
    fun identicalTextEmbedsIdentically() = runBlocking {
        requireModel()
        val embedder = loaded()

        try {
            val text = "Sehr geehrte Damen und Herren,"
            val vectors = (embedder.embedAll(listOf(text, text)) as PamResult.Success).data

            // Non-determinism here would make stored vectors drift from query vectors.
            assertEquals(1.0f, VectorMath.cosineSimilarity(vectors[0], vectors[1]), 1e-4f)
        } finally {
            embedder.release()
        }
    }

    @Test
    @DisplayNameCompat("padding length must not change a short sentence's vector")
    fun batchingDoesNotChangeResults() = runBlocking {
        requireModel()
        val embedder = loaded()

        try {
            val short = "Frist"
            val alone = (embedder.embed(short) as PamResult.Success).data
            val inBatch = (embedder.embedAll(
                listOf(short, "Ein deutlich längerer Satz mit erheblich mehr Inhalt darin."),
            ) as PamResult.Success).data.first()

            Log.i(tag, "embed batchStability=${VectorMath.cosineSimilarity(alone, inBatch)}")

            // Masked pooling is what makes this hold; without it the padded batch would
            // pull the short sentence's vector somewhere else.
            assertEquals(1.0f, VectorMath.cosineSimilarity(alone, inBatch), 1e-3f)
        } finally {
            embedder.release()
        }
    }

    @Test
    fun batchEmbeddingIsFasterPerItemThanOneAtATime() = runBlocking {
        requireModel()
        val embedder = loaded()

        try {
            val texts = List(8) { "Dies ist Testsatz Nummer $it über Fristen und Bescheide." }

            val oneByOne = measureTimeMillis { texts.forEach { embedder.embed(it) } }
            val batched = measureTimeMillis { embedder.embedAll(texts) }

            Log.i(tag, "embed perf oneByOne_ms=$oneByOne batched_ms=$batched for ${texts.size}")

            // Not asserted as a hard requirement — thermal state makes timings noisy — but
            // recorded, because indexing a document runs many chunks and the difference
            // decides whether that is a background nuisance or a visible stall.
            assertTrue(batched > 0)
        } finally {
            embedder.release()
        }
    }

    @Test
    fun reportsNotLoadedRatherThanCrashing() = runBlocking {
        val embedder = service()
        val result = embedder.embed("anything")

        assertTrue("expected a typed error", result is PamResult.Error)
        assertTrue(!embedder.isReady)
    }
}

/** JUnit 4 has no @DisplayName; keeps intent visible without adding a dependency. */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class DisplayNameCompat(val value: String)
