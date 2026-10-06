package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.list.PartyNames
import com.postsaimanager.core.domain.document.people.ConcernedPeopleProfile
import com.postsaimanager.core.domain.document.people.ModelConcernedPeople
import com.postsaimanager.core.domain.form.AiEnginePromptFraming
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.Relationship
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Who is this letter for or about?" on the phone: records, for each benchmark letter of `concerned-people-spec.json`, what the
 * list question ("Is this letter for or about any one of the following people? P1: ... P2: ...", answered as ids or NONE) says in the
 * listed order and in the reversed order, and, from the same session, the per-member log-odds of "Is this letter for or about <member>?"
 * as the comparison. Nothing is decided here; the record holds the answers, the scores, the expected people (and the tolerated ones)
 * and the milliseconds, so the position bias (does it always name the first member? the distractor?) can be read offline.
 *
 * NOT run by the agent that wrote it: it needs the phone and a model.
 *
 * Staging (under /data/local/tmp/z10, like [ZoneBenchmarkTest]): `text.gguf` (or `model`), `bench/<key>.json` (the OCR fixtures)
 * and `concerned-spec.json` (a copy of core/domain/src/test/resources/benchmark/concerned-people-spec.json).
 * Arguments: `keys`, `model`, `dir`, `resume` (default true). Output: `<external files>/z10/rec/<key>.concerned.json`.
 */
@RunWith(AndroidJUnit4::class)
class ConcernedPeopleBenchmarkTest {

    private val dir get() = File(args.getString("dir") ?: "/data/local/tmp/z10")
    private val tag = "z10"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    /** The letter as the app stores it: every block of every page, in reading order, one per line. */
    private fun letterText(file: File): String {
        val pages = JSONObject(file.readText()).getJSONArray("pages")
        val sorted = (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.getInt("pageNumber") }
        return sorted.joinToString("\n") { page ->
            val blocks = page.getJSONArray("blocks")
            (0 until blocks.length()).joinToString("\n") { blocks.getJSONObject(it).getString("text") }
        }
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun relationship(name: String?): Relationship? = name?.let { runCatching { Relationship.valueOf(it) }.getOrNull() }

    @Test
    fun run() = runBlocking {
        val model = File(dir, args.getString("model") ?: "text.gguf")
        val bench = File(dir, "bench")
        val specFile = File(dir, "concerned-spec.json")
        assumeTrue("nothing staged in ${dir.path}", model.exists() && bench.isDirectory && specFile.exists())
        val resume = args.getString("resume") != "false"
        val only = args.getString("keys")?.split(",")
        val outDir = File(context.getExternalFilesDir(null), "z10/rec").apply { mkdirs() }

        val letters = JSONObject(specFile.readText()).getJSONArray("letters").objects()
            .filter { (only == null || it.getString("key") in only) && File(bench, it.getString("key") + ".json").exists() }
            .filterNot { resume && File(outDir, it.getString("key") + ".concerned.json").exists() }
        if (letters.isEmpty()) return@runBlocking

        val engine = LocalAiEngine(Dispatchers.IO)
        val loaded = engine.load(model.absolutePath, InferenceConfig(contextTokens = 6144, threads = InferenceConfig.defaultThreadCount()))
        check(loaded is PamResult.Success) { "load failed: $loaded" }
        val people = ModelConcernedPeople(engine, AiEnginePromptFraming(engine), ConcernedPeopleProfile())

        for (letter in letters) {
            val key = letter.getString("key")
            val text = letterText(File(bench, "$key.json"))
            val tokens = PartyNames.tokenSet(text)
            val profiles = letter.getJSONArray("profiles").objects()
            val members = profiles.map {
                SubjectCandidate(
                    profileId = it.getString("id"),
                    name = it.getString("name"),
                    relationship = relationship(it.optString("relationship").takeIf { r -> r.isNotEmpty() }),
                    isSelf = it.optBoolean("self", false),
                )
            }
            // What the app would ask: only the people whose name token is in the letter. The distractor is recorded as not asked.
            val asked = members.filter { PartyNames.mentions(tokens, it.name) }
            val t0 = System.nanoTime()
            val result = people.compare(text, asked)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val rec = JSONObject().put("key", key).put("asked", JSONArray(asked.map { it.profileId }))
                .put("notAsked", JSONArray(members.filter { it !in asked }.map { it.profileId }))
                .put("expected", letter.getJSONArray("expected")).put("tolerated", letter.optJSONArray("tolerated") ?: JSONArray())
                .put("ms", ms)
            when (result) {
                is PamResult.Success -> rec
                    .put("forward", JSONArray(result.data.forward.toList()))
                    .put("reversed", JSONArray(result.data.reversed.toList()))
                    .put("scores", JSONObject().also { o -> asked.forEachIndexed { i, m -> o.put(m.profileId, result.data.scores[i]) } })
                is PamResult.Error -> rec.put("error", result.error.userMessage)
            }
            File(outDir, "$key.concerned.json").writeText(rec.toString(2))
            Log.i(tag, "CONCERNED $key asked=${asked.size} ms=$ms ${if (result is PamResult.Success) "forward=${result.data.forward} reversed=${result.data.reversed}" else "error"}")
        }
        engine.unload()
    }
}
