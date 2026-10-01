package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.extraction.zones.ZoneInterpreter
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
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
 * Experiment Z on the phone: the letter read zone by zone, through the real `ExtractionV2Pipeline`.
 *
 *  - `zones`            layout template, then each question on its own zone with only that zone's candidates
 *  - `zonesctx`         the same with a labelled, context-only glimpse of the zone above and below
 *  - `zonesscoring`     layout template, then a yes/no log-odds score per candidate (no option labels)
 *  - `zonesscoringctx`  the same with the glimpse
 *
 * Nothing is scored here. Each letter records its raw answers or raw scores, the offered candidates and the timings as
 * `<key>.<interpreter><suffix>.json`; the JVM benchmark replays them through the real interpreters and the verifier.
 * A scoring run is recorded with the shipped profile minus its abstain thresholds (its default of -12 abstains from nothing that
 * matters): every candidate is scored and nothing is decided on the device, so the thresholds (family, topics, the address labels,
 * the extras...) can be tuned offline on the recorded scores, and the decoder is the shipped one, so the questions that depend on the
 * decisions (the summary's facts) are asked again as recorded. A recording holds both stages (`score:family`, the scores, `score:addr`,
 * the second stage's asks and `text:summary`) and a `trace` of the stage timings; `mode=stages` times the two stages as the app runs them.
 *
 * Resumable per letter and interpreter: a recording that exists is skipped (`resume` argument, default true), so a run
 * that lost the USB connection is started again with the same arguments and continues.
 *
 * Staging (all under /data/local/tmp/z10, deleted afterwards): `text.gguf` (or `model`), `bench/<key>.json`.
 * Arguments: `keys`, `interpreters` (default `zones,zonesscoring`), `model` (default text.gguf), `suffix`, `resume`, `dir` (default /data/local/tmp/z10),
 * `mode=stages` (time the two stages), `topics1=false` (keep the topic scores out of the first stage), `tree=true` (the prefix tree).
 */
@RunWith(AndroidJUnit4::class)
class ZoneBenchmarkTest {

    private val dir get() = File(args.getString("dir") ?: "/data/local/tmp/z10")
    private val tag = "z10"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun parseFixture(file: File): Pair<List<List<OcrBlock>>, Float?> {
        val pages = JSONObject(file.readText()).getJSONArray("pages")
        val sorted = (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.getInt("pageNumber") }
        val blocks = sorted.map { p ->
            val bs = p.getJSONArray("blocks")
            (0 until bs.length()).map { i ->
                val b = bs.getJSONObject(i)
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
        val first = sorted.firstOrNull()
        val aspect = first?.let { p -> p.optDouble("width", 0.0) / p.optDouble("height", 1.0) }?.takeIf { it > 0.0 }?.toFloat()
        return blocks to aspect
    }

    /** Keeps what the recording needs: the candidate table and the times of both calls. */
    private class Capture(private val inner: DocumentInterpreter) : DocumentInterpreter by inner {
        var candidates: JSONArray = JSONArray()
        var call1: String? = null
        var call1Ms = 0L
        var call2Ms = 0L
        var failure: String? = null

        override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
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
            return out
        }
    }

    @Test
    fun run() = runBlocking {
        val model = File(dir, args.getString("model") ?: "text.gguf")
        val suffix = args.getString("suffix") ?: ""
        val bench = File(dir, "bench")
        assumeTrue("nothing staged in ${bench.path}", model.exists() && bench.isDirectory)
        val interpreters = (args.getString("interpreters") ?: "zones,zonesscoring").split(",")
        val resume = args.getString("resume") != "false"
        val keys = args.getString("keys")?.split(",")
            ?: bench.list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
        val budgetTokens = 4096

        val outDir = File(context.getExternalFilesDir(null), "z10/rec").apply { mkdirs() }
        val todo = keys.flatMap { k -> interpreters.map { k to it } }.filter { (k, n) ->
            File(bench, "$k.json").exists() && !(resume && File(outDir, "$k.$n$suffix.json").exists())
        }
        if (todo.isEmpty() && args.getString("mode") != "stages") return@runBlocking

        val engine = LocalAiEngine(Dispatchers.IO)
        val loaded = engine.load(
            model.absolutePath,
            InferenceConfig(contextTokens = 6144, threads = InferenceConfig.defaultThreadCount()),
        )
        check(loaded is PamResult.Success) { "load failed: $loaded" }
        val pipeline = ExtractionV2Pipeline()

        // `mode=stages`: no recording, only the two stages timed the way the app runs them (the first, then the second as a later
        // call on a new interpreter that finds the letter's prefix still open), each letter with the shipped profile.
        if (args.getString("mode") == "stages") {
            for (key in keys.filter { File(bench, "$it.json").exists() }) {
                val (pages, aspect) = parseFixture(File(bench, "$key.json"))
                // `tree=true` times the prefix tree (faster, scores not bit-identical to the recorded ones); default: the shipped profile as is.
                val shipped = com.postsaimanager.core.domain.extraction.zones.ModelProfiles.QWEN35_08B.scoring
                    .let { if (args.getString("tree") == "true") it.copy(prefixTree = true) else it }
                // `topics1=false` keeps the topic scores out of the first stage (ModelProfile.topicsInFirstStage) to time the other setting.
                val topicsFirst = args.getString("topics1") != "false"
                val first = ZoneScoringInterpreter(engine, engine, contextTokens = budgetTokens, profile = shipped, topicsInFirstStage = topicsFirst)
                val t0 = System.nanoTime()
                val one = pipeline.run(pages, first, budgetTokens, aspect, stages = ExtractionV2Pipeline.Stages.FIRST)
                val ms1 = (System.nanoTime() - t0) / 1_000_000
                val second = ZoneScoringInterpreter(engine, engine, contextTokens = budgetTokens, profile = shipped, topicsInFirstStage = topicsFirst)
                val t1 = System.nanoTime()
                val two = pipeline.run(pages, second, budgetTokens, aspect, stages = ExtractionV2Pipeline.Stages.SECOND, ticket = one.enrichment)
                val ms2 = (System.nanoTime() - t1) / 1_000_000
                Log.i(
                    tag,
                    "STAGES tree=${shipped.prefixTree} topics1=$topicsFirst $key stage1Ms=$ms1 stage2Ms=$ms2 family=${one.documentType?.id} topics=${one.topics} " +
                        "slots=${one.slots.size} parties=${one.parties.all.size} addresses=${one.addresses.keys} extras=${two.extras.size} language=${two.language} " +
                        "title=${two.composedTitle?.code} summary=${two.summary?.origin}",
                )
                // The stage's own breakdown (`t ...` lines: counts and milliseconds, never a word of the letter).
                one.diagnostics.trace.filter { it.startsWith("t ") }.forEach { Log.i(tag, "STAGE1T $key $it") }
                two.diagnostics.trace.filter { it.startsWith("t ") }.forEach { Log.i(tag, "STAGE2T $key $it") }
            }
            engine.unload()
            return@runBlocking
        }

        for ((key, name) in todo) {
            val (pages, aspect) = parseFixture(File(bench, "$key.json"))
            val rec = JSONObject().put("key", key).put("interpreter", name).put("pages", pages.size)
            val ctx = name.contains("ctx")
            var transcript: () -> List<com.postsaimanager.core.domain.extraction.v2.AskRecord> = { emptyList() }
            var prefix: () -> Pair<Int, Long> = { 0 to 0L }
            var template: () -> Pair<String, Float> = { "" to 0f }
            val capture = when {
                name.startsWith("zonesscoring") -> {
                    // The shipped profile without its abstain thresholds (its default of -12 abstains from nothing that matters), so every
                    // candidate is scored and nothing is decided on the device, but with the shipped decoder: the decisions the summary's facts
                    // rest on are the ones a replay under the shipped profile makes again, so its question is found in the recording.
                    val i = ZoneScoringInterpreter(
                        engine, engine, contextTokens = budgetTokens, neighbourContext = ctx,
                        profile = ModelProfiles.QWEN35_08B.scoring.copy(thresholds = emptyMap()),
                        topicsInFirstStage = args.getString("topics1") != "false",
                    )
                    transcript = { i.transcript }
                    prefix = { i.prefixTokens to i.prefixMs }
                    template = { i.templateId to i.templateScore }
                    Capture(i)
                }
                name.startsWith("zones") -> {
                    val i = ZoneInterpreter(engine, engine, contextTokens = budgetTokens, measureTokens = true, neighbourContext = ctx)
                    transcript = { i.transcript }
                    prefix = { i.prefixTokens to i.prefixMs }
                    template = { i.templateId to i.templateScore }
                    Capture(i)
                }
                else -> error("unknown interpreter $name")
            }
            val t0 = System.nanoTime()
            try {
                val out = pipeline.run(pages, capture, budgetTokens, aspect)
                // Where the time went, as the interpreter and the pipeline measured it (`t ...` lines: counts and milliseconds only).
                rec.put("trace", JSONArray(out.diagnostics.trace.filter { it.startsWith("t ") }))
            } catch (e: Exception) {
                Log.e(tag, "$key/$name failed", e)
                rec.put("error", e.toString())
            }
            val wallMs = (System.nanoTime() - t0) / 1_000_000
            rec.put("contextTokens", budgetTokens).put("candidates", capture.candidates)
                .put("call1", capture.call1 ?: "").put("call1Ms", capture.call1Ms).put("call2Ms", capture.call2Ms)
            capture.failure?.let { rec.put("failure", it) }
            val (prefixTokens, prefixMs) = prefix()
            val (templateId, templateScore) = template()
            rec.put("prefixTokens", prefixTokens).put("prefixMs", prefixMs)
                .put("template", templateId).put("templateScore", templateScore.toDouble())
            rec.put(
                "asks",
                JSONArray().also { arr ->
                    transcript().forEach { a ->
                        arr.put(
                            JSONObject().put("name", a.name).put("question", a.question).put("answer", a.answer ?: JSONObject.NULL)
                                .put("ms", a.ms).put("questionTokens", a.questionTokens).put("answerTokens", a.answerTokens),
                        )
                    }
                },
            )
            rec.put("wallMs", wallMs).put("model", model.name)
            File(outDir, "$key.$name$suffix.json").writeText(rec.toString(1))
            Log.i(tag, "REC $key $name$suffix wallMs=$wallMs template=$templateId call1Ms=${capture.call1Ms} call2Ms=${capture.call2Ms}")
        }
        engine.unload()
    }
}
