package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
import com.postsaimanager.core.model.InferenceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Records what the model scores for the action kinds, once per letter, for the JVM replay to fit the thresholds on
 * (`ActionKindReplayTest`, recordings in `core/domain/src/test/resources/benchmark/actions/<key>.json`).
 *
 * Each letter goes through the real two-stage reading with the shipped scoring profile (topics in the second stage, as the app reads),
 * with the recording variant of the action profile: every stored date and amount is scored under every action kind, so the binding
 * thresholds can be fitted offline too. What is written is the input the action scorer was given (the stored slot values and whether a
 * sender was stored) and every score batch of the action questions, as the interpreter recorded them: the questions and the raw log-odds.
 * Nothing is decided here.
 *
 * Staging (all under /data/local/tmp/z10, the same as `ZoneBenchmarkTest`; delete afterwards): `text.gguf`, `bench/<key>.json`.
 * Arguments: `keys` (default: every staged letter), `dir`, `model` (default text.gguf), `resume` (default true: a letter that has its file is skipped).
 * Output: the app's external files directory, `z10/actions/<key>.json`.
 */
@RunWith(AndroidJUnit4::class)
class ActionKindBenchmarkTest {

    private val args get() = InstrumentationRegistry.getArguments()
    private val dir get() = File(args.getString("dir") ?: "/data/local/tmp/z10")
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "z10"

    @Test
    fun record() = runBlocking {
        val model = File(dir, args.getString("model") ?: "text.gguf")
        val bench = File(dir, "bench")
        assumeTrue("nothing staged in ${bench.path}", model.exists() && bench.isDirectory)
        val resume = args.getString("resume") != "false"
        val keys = args.getString("keys")?.split(",")
            ?: bench.list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
        val outDir = File(context.getExternalFilesDir(null), "z10/actions").apply { mkdirs() }
        val todo = keys.filter { File(bench, "$it.json").exists() && !(resume && File(outDir, "$it.json").exists()) }
        if (todo.isEmpty()) return@runBlocking

        val engine = LocalAiEngine(Dispatchers.IO)
        val loaded = engine.load(model.absolutePath, InferenceConfig(contextTokens = 6144, threads = InferenceConfig.defaultThreadCount()))
        check(loaded is PamResult.Success) { "load failed: $loaded" }
        val shipped = ModelProfiles.QWEN35_08B
        val budgetTokens = 4096
        for (key in todo) {
            val (pages, aspect) = BenchmarkFixtureFiles.parse(File(bench, "$key.json"))
            fun interpreter() = ZoneScoringInterpreter(
                engine, engine, contextTokens = budgetTokens, profile = ModelProfiles.recordingProfile(shipped.scoring),
                topicsInFirstStage = shipped.topicsInFirstStage,
            )
            // The two stages as the app runs them: the first reading leaves a ticket, the second is a later call on a new interpreter.
            val second = interpreter()
            val rec = JSONObject().put("key", key)
            try {
                val one = ExtractionV2Pipeline().run(pages, interpreter(), budgetTokens, aspect, stages = ExtractionV2Pipeline.Stages.FIRST)
                val ticket = one.enrichment
                val out = ExtractionV2Pipeline().run(pages, second, budgetTokens, aspect, stages = ExtractionV2Pipeline.Stages.SECOND, ticket = ticket)
                rec.put("family", one.documentType?.id ?: JSONObject.NULL)
                    .put("senderStored", !ticket?.facts?.get("sender").isNullOrBlank())
                    .put(
                        "slots",
                        JSONArray().also { arr ->
                            ticket?.slots.orEmpty().forEach { s -> arr.put(JSONObject().put("key", s.key).put("label", s.label).put("value", s.value)) }
                        },
                    )
                    .put("stageActions", out.actions?.let { a -> JSONArray(a.map { it.kind }) } ?: JSONObject.NULL)
            } catch (e: Exception) {
                Log.e(tag, "$key failed", e)
                rec.put("error", e.toString())
            }
            val asks = JSONArray()
            second.transcript.filter { it.name.startsWith("score:action") }.forEach { a ->
                asks.put(JSONObject().put("name", a.name).put("question", a.question).put("answer", a.answer ?: JSONObject.NULL).put("ms", a.ms))
            }
            rec.put("asks", asks)
            File(outDir, "$key.json").writeText(rec.toString(1))
            Log.i(tag, "ACTIONREC $key batches=${asks.length()} error=${rec.optString("error")}")
        }
        engine.unload()
    }
}
