package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How a given model actually reads a real German letter.
 *
 * Driving the app UI could not answer either question: the process aborts natively, the app
 * degrades to pattern matching, and by the time anyone looks the logcat ring has rolled
 * over. Here the suspects are separated — the model, the grammar, and the context size —
 * and each is exercised on its own.
 *
 * Requires the model in place:
 * ```
 * adb shell "run-as com.postsaimanager.debug cat files/models/qwen3.5-2b-q4_k_m.gguf \
 *   > /data/local/tmp/qwen35-2b.gguf"
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ExtractionModelTest {

    /**
     * Whichever model is staged for comparison.
     *
     * A fixed path rather than a per-model constant, so swapping models is a file copy
     * instead of a code change — the point of these runs is to compare models on identical
     * input, and an edit between runs is one more thing that could differ.
     *
     * ```
     * adb push gemma4-e2b.gguf /data/local/tmp/extract-model.gguf
     * ```
     */
    private val modelFile = File("/data/local/tmp/extract-model.gguf")
    private val tag = "pam_spike"

    private suspend fun loaded(): LocalAiEngine {
        val engine = LocalAiEngine(Dispatchers.IO)
        // The window the device provider now caps to, so this reproduces what the app does.
        val result = engine.load(modelFile.absolutePath, contextTokens = 4096)
        check(result is PamResult.Success) { "load failed: $result" }
        Log.i(tag, "model loaded: ${result.data}")
        return engine
    }

    @Test
    fun generatesWithoutAGrammar() = runBlocking {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())
        val engine = loaded()
        try {
            // Through the chat template, not a raw string. Gemma emits end-of-turn
            // immediately when handed a bare prompt, generating nothing at all — which
            // looks like a broken model and is really a missing template.
            val prompt = engine.formatPrompt(
                listOf(
                    AiChatMessage(
                        AiChatRole.USER,
                        "Antworte auf Deutsch mit einem Satz: Was ist ein Widerspruch?",
                    ),
                ),
            )
            val out = engine.generate(
                AiRequest(
                    prompt = prompt,
                    maxTokens = 48,
                    temperature = 0.1f,
                    grammar = null,
                ),
            ).toList().joinToString("")

            Log.i(tag, "model nogrammar len=${out.length} text=${out.take(160)}")
            // Survives here means the model and runtime are fine and the grammar is the
            // suspect; aborts here means the grammar is exonerated.
            assert(out.isNotEmpty()) { "empty generation" }
        } finally {
            engine.unload()
        }
    }

    @Test
    fun generatesWithATrivialGrammar() = runBlocking {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())
        val engine = loaded()
        try {
            // Narrows "grammars are broken for this model" against "this grammar is wrong".
            val out = engine.generate(
                AiRequest(
                    prompt = "Say yes or no.",
                    maxTokens = 8,
                    temperature = 0.1f,
                    grammar = "root ::= \"yes\" | \"no\"",
                ),
            ).toList().joinToString("")

            Log.i(tag, "model trivialgrammar out=${out.take(60)}")
            assert(out.isNotEmpty()) { "empty generation" }
        } finally {
            engine.unload()
        }
    }

    /**
     * The real thing: the whole extraction path against a real letter.
     *
     * An earlier version passed the page text straight to `generate()` with the grammar and
     * got fluent nonsense — the model had been given a shape to fill and no task. Going
     * through the use case is what supplies the system instructions and the model's own
     * chat template.
     */
    @Test
    fun readsARealLetter() = runBlocking {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())
        val engine = loaded()
        try {
            val extract = AiExtractionUseCase(engine, StubActiveModel(modelFile.absolutePath))

            val start = System.currentTimeMillis()
            val result = extract(jobcenterLetter, contextTokens = 4096)
            val elapsed = System.currentTimeMillis() - start

            when (result) {
                is PamResult.Success -> {
                    val understanding = result.data
                    Log.i(
                        tag,
                        "model read ms=$elapsed lang=${understanding.language} " +
                            "subject=${understanding.subject}",
                    )
                    understanding.entities.forEach {
                        Log.i(
                            tag,
                            "model entity ${it.role} ${it.kind} name='${it.name}' " +
                                "rel='${it.relation}' conf=${it.confidence}",
                        )
                    }
                    understanding.facts.forEach {
                        Log.i(
                            tag,
                            "model fact ${it.kind} '${it.label}'='${it.value}' conf=${it.confidence}",
                        )
                    }
                }
                is PamResult.Error -> Log.w(tag, "model read failed: ${result.error.userMessage}")
            }

            assert(result is PamResult.Success) { "extraction failed: $result" }
        } finally {
            engine.unload()
        }
    }

    /** The scanned Jobcenter letter, as positioned blocks. */
    private val jobcenterLetter: List<OcrBlock> = listOf(
        block(
            """
            Jobcenter Berlin Mitte
            Müllerstraße 16, 13353 Berlin
            """.trimIndent(),
            0.08f, 0.03f, 0.45f, 0.09f,
        ),
        block(
            """
            Frau
            Aylin Mustermann
            Seestraße 42
            13347 Berlin
            """.trimIndent(),
            0.08f, 0.18f, 0.40f, 0.30f,
        ),
        block(
            """
            Aktenzeichen: BG 1234/5678
            Ihr Zeichen: WS-2026-0142
            Datum: 15.01.2026
            """.trimIndent(),
            0.58f, 0.18f, 0.92f, 0.27f,
        ),
        block("Widerspruchsbescheid", 0.08f, 0.36f, 0.45f, 0.40f),
        block(
            """
            Sehr geehrte Frau Mustermann,
            Ihr Widerspruch vom 12.01.2026 gegen unseren Bescheid vom 03.12.2025 wurde
            geprüft. Der Widerspruch wird als unbegründet zurückgewiesen.
            """.trimIndent(),
            0.08f, 0.44f, 0.92f, 0.56f,
        ),
        block(
            """
            Bitte reichen Sie die noch fehlenden Unterlagen bis zum 31.01.2026 bei uns ein.
            Andernfalls müssen wir die laufenden Leistungen vorläufig einstellen.
            """.trimIndent(),
            0.08f, 0.58f, 0.92f, 0.66f,
        ),
        block(
            "Ihre monatliche Regelleistung beträgt ab dem 01.02.2026 voraussichtlich " +
                "563,00 Euro.",
            0.08f, 0.68f, 0.92f, 0.74f,
        ),
        block(
            "Für Rückfragen steht Ihnen Herr Schmidt unter 030 12345678 zur Verfügung.",
            0.08f, 0.76f, 0.92f, 0.82f,
        ),
        block(
            """
            Mit freundlichen Grüßen
            i. A. Schmidt
            """.trimIndent(),
            0.08f, 0.90f, 0.40f, 0.96f,
        ),
    )

    private fun block(text: String, l: Float, t: Float, r: Float, b: Float) = OcrBlock(
        text = text,
        bounds = TextBounds(l, t, r, b),
        confidence = 0.9f,
    )
}

/** Points the extraction use case at the model this test loaded. */
private class StubActiveModel(private val path: String) : ActiveModelProvider {
    override suspend fun activeModelPath(): String = path
    override suspend fun activeModelContextTokens(): Int = 4096
    override suspend fun extractionModelPath(): String = path
    override suspend fun extractionModelContextTokens(): Int = 4096
}
