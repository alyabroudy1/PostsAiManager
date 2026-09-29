package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.QuestionnaireInterpreter
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Workstream B, on the phone: the two ways of reading a letter with the real model, through the real
 * `ExtractionV2Pipeline`.
 *
 *  - `single` the current design: one big grammar-constrained call for everything, then a second for the free text
 *    ([ModelDocumentInterpreter]).
 *  - `questionnaire` the letter read once, then many tiny focused questions ([QuestionnaireInterpreter], through
 *    the engine's [com.postsaimanager.core.domain.ai.PromptSession]).
 *
 * Nothing is scored here. Each run records the raw model answers, the offered candidate table (so a replay can
 * remap ids by kind, value and page) and the timings as `<key>.<interpreter>.json`; the JVM benchmark
 * (`InterpreterMetrics`) replays them through the verifier and scores them with the shared metrics.
 * Text only: OCR stays the reading step.
 *
 * Staging (all under /data/local/tmp/b8, deleted afterwards):
 * ```
 * text.gguf                          the model (Qwen3.5-0.8B Q4_K_M)
 * bench/<key>.json                   real phone OCR (core:domain test resources, benchmark/fixtures)
 * ```
 * Arguments: `keys` (comma list, default all found), `interpreters` (default `single,questionnaire`), `model`
 * (default text.gguf), `suffix` (appended to the interpreter in the file name, for another model).
 */
@RunWith(AndroidJUnit4::class)
class QuestionnaireBenchmarkTest {

    private val dir = File("/data/local/tmp/b8")
    private val tag = "b8"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun parseFixture(file: File): List<List<OcrBlock>> {
        val pages = JSONObject(file.readText()).getJSONArray("pages")
        val sorted = (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.getInt("pageNumber") }
        return sorted.map { p ->
            val blocks = p.getJSONArray("blocks")
            (0 until blocks.length()).map { i ->
                val b = blocks.getJSONObject(i)
                val r = b.getJSONObject("bounds")
                OcrBlock(
                    text = b.getString("text"),
                    bounds = TextBounds(
                        r.getDouble("left").toFloat(), r.getDouble("top").toFloat(),
                        r.getDouble("right").toFloat(), r.getDouble("bottom").toFloat(),
                    ),
                    confidence = b.optDouble("confidence", 1.0).toFloat(),
                    language = b.optString("language").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
        }
    }

    /** Wraps an interpreter and keeps what the recording needs: the candidate table, the raw answers, the times. */
    private class Capture(private val inner: DocumentInterpreter) : DocumentInterpreter by inner {
        var candidates: JSONArray = JSONArray()
        var call1: String? = null
        var call2: String? = null
        var call1Ms = 0L
        var call2Ms = 0L
        var failure: String? = null
        var layoutTextChars = 0

        override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
            layoutTextChars = request.layoutText.length
            candidates = JSONArray().also { arr ->
                request.offered.rows.forEach { row ->
                    arr.put(
                        JSONObject().put("id", row.candidate.id).put("kind", row.candidate.kind.name)
                            .put("raw", row.candidate.raw).put("normalized", row.candidate.normalized)
                            .put("page", row.pages.firstOrNull() ?: row.candidate.page),
                    )
                }
            }
            val t0 = System.nanoTime()
            val out = inner.interpret(request)
            call1Ms = (System.nanoTime() - t0) / 1_000_000
            when (out) {
                is InterpretationOutcome.Answered -> call1 = out.rawText
                is InterpretationOutcome.Failed -> {
                    call1 = out.rawText
                    failure = out.reason
                }
            }
            return out
        }

        override suspend fun writeText(request: TextRequest): TextOutcome {
            val t0 = System.nanoTime()
            val out = inner.writeText(request)
            call2Ms = (System.nanoTime() - t0) / 1_000_000
            if (out is TextOutcome.Written) call2 = out.rawText
            return out
        }
    }

    @Test
    fun run() = runBlocking {
        val model = File(dir, args.getString("model") ?: "text.gguf")
        val suffix = args.getString("suffix") ?: ""
        val bench = File(dir, "bench")
        assumeTrue("nothing staged in ${bench.path}", model.exists() && bench.isDirectory)
        val interpreters = (args.getString("interpreters") ?: "single,questionnaire").split(",")
        val keys = args.getString("keys")?.split(",")
            ?: bench.list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
        // As the earlier text-only runs: the model is loaded with more room than the letter is budgeted against.
        val budgetTokens = 4096

        val engine = LocalAiEngine(Dispatchers.IO)
        val loaded = engine.load(
            model.absolutePath,
            InferenceConfig(contextTokens = 6144, threads = InferenceConfig.defaultThreadCount()),
        )
        check(loaded is PamResult.Success) { "load failed: $loaded" }
        val outDir = File(context.getExternalFilesDir(null), "b8/rec").apply { mkdirs() }
        val pipeline = ExtractionV2Pipeline()

        for (key in keys) {
            val fixture = File(bench, "$key.json")
            if (!fixture.exists()) continue
            val pages = parseFixture(fixture)
            for (name in interpreters) {
                val rec = JSONObject().put("key", key).put("interpreter", name).put("pages", pages.size)
                var questionnaire: QuestionnaireInterpreter? = null
                val capture = when (name) {
                    "single" -> Capture(ModelDocumentInterpreter(engine, contextTokens = budgetTokens))
                    // "questionnaire2": each question also restates the candidates it chooses from.
                    "questionnaire", "questionnaire2" -> Capture(
                        QuestionnaireInterpreter(
                            engine, engine, contextTokens = budgetTokens, measureTokens = true,
                            restateOptions = name == "questionnaire2",
                        ).also { questionnaire = it },
                    )
                    else -> error("unknown interpreter $name")
                }
                val t0 = System.nanoTime()
                try {
                    pipeline.run(pages, capture, budgetTokens)
                } catch (e: Exception) {
                    Log.e(tag, "$key/$name failed", e)
                    rec.put("error", e.toString())
                }
                val wallMs = (System.nanoTime() - t0) / 1_000_000
                rec.put("contextTokens", budgetTokens).put("candidates", capture.candidates)
                    .put("call1", capture.call1 ?: "").put("call1Ms", capture.call1Ms).put("call2Ms", capture.call2Ms)
                    .put("layoutTextChars", capture.layoutTextChars)
                capture.call2?.let { rec.put("call2", it) }
                capture.failure?.let { rec.put("failure", it) }
                questionnaire?.let { q ->
                    rec.put("prefixTokens", q.prefixTokens).put("prefixMs", q.prefixMs)
                    rec.put(
                        "asks",
                        JSONArray().also { arr ->
                            q.transcript.forEach { a ->
                                arr.put(
                                    JSONObject().put("name", a.name).put("question", a.question).put("answer", a.answer ?: JSONObject.NULL)
                                        .put("ms", a.ms).put("questionTokens", a.questionTokens).put("answerTokens", a.answerTokens),
                                )
                            }
                        },
                    )
                }
                rec.put("wallMs", wallMs).put("model", model.name)
                File(outDir, "$key.$name$suffix.json").writeText(rec.toString(1))
                Log.i(tag, "REC $key $name$suffix wallMs=$wallMs call1Ms=${capture.call1Ms} call2Ms=${capture.call2Ms}")
            }
        }
        engine.unload()
    }
}
