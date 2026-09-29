package com.postsaimanager.core.ai.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.extraction.v2.DocType
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationParser
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRelation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Roles
import com.postsaimanager.core.domain.extraction.v2.SelectionPrompt
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 1, workstream H: the three ways of reading a letter with the real model, on the phone.
 *
 *  - `t`  text only, the current design (`ExtractionV2Pipeline` + `ModelDocumentInterpreter`).
 *  - `ti` the same call 1 with page 1 as an image added to the prompt.
 *  - `i`  image only, one call per page with a free-value schema, merged offline. Values are
 *    unverifiable (no candidate ids), so they are only scored against the manifest.
 *
 * Nothing is scored here. Each run records the raw model answers plus timings as
 * `<key>.<variant>.json`; the JVM benchmark (`VisionVariantsTest`) replays them through the
 * verifier and scores them with the shared metrics. Call 2 (free text) is skipped: it does not
 * feed any scored metric and would only add wall time.
 *
 * Staging (all under /data/local/tmp/h7, deleted afterwards):
 * ```
 * text.gguf  mmproj-f16.gguf
 * bench/<key>.json                   real phone OCR (core:domain test resources, benchmark/fixtures)
 * bench/img/<key>/page-N.jpg         the invented letter pages
 * ```
 * Arguments: `keys` (comma list, default all found), `variants` (default `t,ti,i`), `side`
 * (long side in px of the image given to the model, default 768), `mmproj` (default f16).
 */
@RunWith(AndroidJUnit4::class)
class VisionBenchmarkTest {

    private val dir = File("/data/local/tmp/h7")
    private val tag = "h7"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private val schema = ExtractionSchema.DEFAULT

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

    private fun scaled(source: File, side: Int, name: String): File {
        val bmp = BitmapFactory.decodeFile(source.absolutePath)
        val k = side.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        val f = File(context.cacheDir, "$name-$side.png")
        FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** Records call 1 of the inner interpreter (raw answer and wall time); skips call 2. */
    private class Recording(private val inner: DocumentInterpreter) : DocumentInterpreter by inner {
        var raw: String? = null
        var ms: Long = 0
        var prompt: String? = null

        override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
            val t0 = System.nanoTime()
            val out = inner.interpret(request)
            ms = (System.nanoTime() - t0) / 1_000_000
            raw = when (out) {
                is InterpretationOutcome.Answered -> out.rawText
                is InterpretationOutcome.Failed -> out.rawText
            }
            prompt = out.prompt
            return out
        }

        override suspend fun writeText(request: TextRequest): TextOutcome = TextOutcome.Failed("skipped in the benchmark")
    }

    /** Call 1 of the current design with page 1 added as an image. */
    private class WithImage(
        private val engine: AiEngine,
        private val imagePath: String,
        private val contextTokens: Int,
    ) : DocumentInterpreter {
        private val withExample = contextTokens >= SelectionPrompt.EXAMPLE_MIN_CONTEXT_TOKENS
        override val maxAnswerTokens: Int = ModelDocumentInterpreter.MAX_ANSWER_TOKENS
        override val maxTextTokens: Int = ModelDocumentInterpreter.MAX_TEXT_TOKENS
        var raw: String? = null
        var ms: Long = 0
        var stats: String = ""

        override fun promptOverheadChars(offered: OfferedCandidates) =
            SelectionPrompt.overheadChars(ExtractionSchema.DEFAULT, offered, withExample) + IMAGE_NOTE.length

        override fun textOverheadChars() = SelectionPrompt.textOverheadChars()

        override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
            val grammar = StructuredGrammar.build(request.offered, ExtractionSchema.DEFAULT)
            val prompt = engine.formatPrompt(
                listOf(
                    AiChatMessage(AiChatRole.SYSTEM, SelectionPrompt.system(ExtractionSchema.DEFAULT, withExample)),
                    AiChatMessage(
                        AiChatRole.USER,
                        AiRequest.IMAGE_MARKER + "\n" + IMAGE_NOTE + "\n\n" + SelectionPrompt.user(request.layoutText, request.offered),
                    ),
                ),
            )
            val t0 = System.nanoTime()
            val text = engine.generate(
                AiRequest(
                    prompt = prompt,
                    maxTokens = MAX_ANSWER_TOKENS,
                    temperature = 0f,
                    grammar = grammar,
                    thinkingEnabled = false,
                    imagePaths = listOf(imagePath),
                ),
            ).toList().joinToString("")
            ms = (System.nanoTime() - t0) / 1_000_000
            stats = engine.lastVisionStats()
            raw = text
            return when (val parsed = InterpretationParser.parse(text)) {
                is InterpretationParser.Parsed.Ok -> InterpretationOutcome.Answered(parsed.value, text, prompt, grammar)
                is InterpretationParser.Parsed.Bad -> InterpretationOutcome.Failed(parsed.reason, text, prompt, grammar)
            }
        }

        override suspend fun writeText(request: TextRequest): TextOutcome = TextOutcome.Failed("skipped in the benchmark")

        companion object {
            const val MAX_ANSWER_TOKENS = ModelDocumentInterpreter.MAX_ANSWER_TOKENS
            const val IMAGE_NOTE =
                "The image is page 1 of the letter. The text below is what OCR read from the letter; " +
                    "use the image to see the layout, who sits where, and what OCR may have got wrong."
        }
    }

    @Test
    fun run() = runBlocking {
        val text = File(dir, "text.gguf")
        val bench = File(dir, "bench")
        assumeTrue("nothing staged in ${bench.path}", text.exists() && bench.isDirectory)
        val mmproj = File(dir, "mmproj-${args.getString("mmproj") ?: "f16"}.gguf")
        val side = (args.getString("side") ?: "768").toInt()
        val variants = (args.getString("variants") ?: "t,ti,i").split(",")
        val keys = args.getString("keys")?.split(",")
            ?: bench.list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
        val ctx = 4096

        val engine = LocalAiEngine(Dispatchers.IO)
        val loaded = engine.load(
            text.absolutePath,
            InferenceConfig(contextTokens = 6144, threads = InferenceConfig.defaultThreadCount()),
        )
        check(loaded is PamResult.Success) { "load failed: $loaded" }
        if (variants.any { it != "t" }) {
            check(engine.loadVision(mmproj.absolutePath) is PamResult.Success) { "loadVision failed" }
        }
        val outDir = File(context.getExternalFilesDir(null), "h7/rec").apply { mkdirs() }
        val pipeline = ExtractionV2Pipeline()

        for (key in keys) {
            val fixture = File(bench, "$key.json")
            if (!fixture.exists()) continue
            val pages = parseFixture(fixture)
            val images = pages.indices.map { File(bench, "img/$key/page-${it + 1}.jpg") }
            for (variant in variants) {
                val rec = JSONObject().put("key", key).put("variant", variant).put("imageSide", side)
                    .put("mmproj", mmproj.name).put("pages", pages.size)
                val t0 = System.nanoTime()
                try {
                    when (variant) {
                        "t" -> {
                            val r = Recording(ModelDocumentInterpreter(engine, contextTokens = ctx))
                            pipeline.run(pages, r, ctx)
                            rec.put("raw", r.raw).put("call1Ms", r.ms)
                        }
                        "ti" -> {
                            val img = scaled(images[0], side, "ti")
                            val r = WithImage(engine, img.absolutePath, ctx)
                            pipeline.run(pages, Recording(r), ctx)
                            rec.put("raw", r.raw).put("call1Ms", r.ms).put("vision", r.stats)
                        }
                        "i" -> {
                            val arr = JSONArray()
                            var total = 0L
                            for ((i, img) in images.withIndex()) {
                                val one = scaled(img, side, "i")
                                val p = readPage(engine, one, i + 1, images.size)
                                total += p.ms
                                arr.put(JSONObject().put("page", i + 1).put("raw", p.raw).put("ms", p.ms).put("vision", p.stats))
                            }
                            rec.put("pageAnswers", arr).put("call1Ms", total)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "$key/$variant failed", e)
                    rec.put("error", e.toString())
                }
                rec.put("wallMs", (System.nanoTime() - t0) / 1_000_000)
                File(outDir, "$key.$variant.json").writeText(rec.toString(1))
                Log.i(tag, "REC $key $variant wallMs=${rec.getLong("wallMs")} call1Ms=${rec.optLong("call1Ms")}")
            }
        }
        engine.unload()
    }

    private class PageRead(val raw: String, val ms: Long, val stats: String)

    /** Variant `i`: one page image, free values, the call-1-shaped schema. */
    private suspend fun readPage(engine: AiEngine, image: File, page: Int, pages: Int): PageRead {
        val prompt = engine.formatPrompt(
            listOf(
                AiChatMessage(AiChatRole.SYSTEM, ImageOnly.system(schema, page, pages)),
                AiChatMessage(AiChatRole.USER, AiRequest.IMAGE_MARKER + "\nThis is page $page of $pages. Describe it as JSON."),
            ),
        )
        val t0 = System.nanoTime()
        val raw = engine.generate(
            AiRequest(
                prompt = prompt,
                maxTokens = ModelDocumentInterpreter.MAX_ANSWER_TOKENS,
                temperature = 0f,
                grammar = ImageOnly.grammar(schema),
                thinkingEnabled = false,
                imagePaths = listOf(image.absolutePath),
            ),
        ).toList().joinToString("")
        return PageRead(raw, (System.nanoTime() - t0) / 1_000_000, engine.lastVisionStats())
    }

    /**
     * Variant `i`'s prompt and grammar: the same JSON shape as call 1 (type, parties, slots, extras,
     * confidence words) but every value is text copied from the page ("v") instead of a candidate id.
     */
    object ImageOnly {

        /** Fewer than call 1's six: a page-by-page answer that runs into the token cap cannot be parsed. */
        const val MAX_EXTRAS = 2

        /** Likewise: the 0.8B model fills every role it may (six parties, mostly repeats) and hits the cap. */
        const val MAX_PARTIES = 4

        fun system(schema: ExtractionSchema, page: Int, pages: Int): String = """
            You read one page of a scanned letter, given as an image, and describe it as JSON. The letter can be in any language. This is page $page of $pages.

            OUTPUT. One JSON object with exactly the keys the grammar allows.
            - type: what kind of document this is (${schema.types.joinToString(", ") { it.id }}). tc: your confidence in it. On a later page, judge from what the page shows.
            - lang: the language code of the letter.
            - parties: everybody who plays a role on this page (at most $MAX_PARTIES). r is SENDER (who wrote and
              sent the letter), ADDRESSEE (who it is addressed to), CO_ADDRESSEE (someone addressed together with
              the addressee), ROUTING (a person named only as the contact or handler at an organisation that is
              the addressee), CARE_OF (a person or household whose address is only used as a mailbox),
              SUBJECT_PERSON (who the letter is about, for example a child when the parents are addressed).
              name is the name copied exactly as printed. k is PERSON, AUTHORITY, COMPANY or OTHER. rel is GUARDIAN_OF when
              the addressee acts for the subject person, HOUSEHOLD when a family or household is addressed, otherwise NONE.
              The sender and the addressee are never the same party.
            - s: the value fields. v is the value copied exactly as printed on this page, and r says what the value is;
              look for every field on the page (letter date near the top right, amounts near "Betrag", "Summe" or "total",
              deadlines near "bis" or "Frist", IBAN near "IBAN", numbers near "Nr." or "Nummer") and leave v empty ("") only when
              this page really has no such value. Never guess, compute or complete a value that is not printed.
              For a period given in words (for example "within one month") copy the words.
            - x: up to $MAX_EXTRAS other meaningful details that no field covers (a meter number, a tariff, a policy holder,
              a vehicle plate, a school class, a phone number to call ...). lb is the label as printed, k a short English
              snake_case key, v the text copied exactly as printed.
            - c is your confidence for that object: LOW, MEDIUM or HIGH.
        """.trimIndent()

        private fun lit(s: String) = "\"\\\"$s\\\"\""
        private fun key(s: String) = "\"\\\"$s\\\":\""
        private fun enumRule(v: List<String>) = v.joinToString(" | ") { lit(it) }
        private fun str(min: Int, max: Int) = "\"\\\"\" qchar{$min,$max} \"\\\"\""
        private fun obj(vararg f: Pair<String, String>) =
            f.joinToString(" \",\" ws ", prefix = "\"{\" ws ", postfix = " ws \"}\"") { (k, r) -> "${key(k)} ws $r" }

        private fun ruleOf(slot: SlotKey) = when (slot.kind) {
            SlotKind.AMOUNT -> "amt"
            SlotKind.DATE, SlotKind.DEADLINE -> "date"
            SlotKind.ACTION -> "action"
            else -> "plain"
        }

        fun grammar(schema: ExtractionSchema): String {
            val rules = LinkedHashMap<String, String>()
            rules["root"] = schema.types.joinToString(" | ") { "t-" + it.id.replace('_', '-') }
            for (type: DocType in schema.types) {
                val slots = type.slots.joinToString(" \",\" ws ") { "${key(it.json)} ws ${ruleOf(it)}" }
                rules["t-" + type.id.replace('_', '-')] = listOf(
                    "\"{\" ws ${key("type")} ws ${lit(type.id)}",
                    "${key("tc")} ws conf",
                    "${key("lang")} ws lang",
                    "${key("parties")} ws parties",
                    "${key("s")} ws \"{\" ws $slots ws \"}\"",
                    "${key("x")} ws xlist ws \"}\"",
                ).joinToString(" \",\" ws ")
            }
            rules["conf"] = enumRule(StructuredGrammar.CONFIDENCE_WORDS)
            rules["lang"] = "\"\\\"\" [a-z] [a-z] [a-z]? (\"-\" [A-Za-z0-9] [A-Za-z0-9]{1,7})? \"\\\"\""
            rules["parties"] = "\"[\" ws (party (ws \",\" ws party){0,${MAX_PARTIES - 1}})? ws \"]\""
            rules["party"] = obj(
                "r" to "prole", "name" to "vtext", "k" to "pkind", "rel" to "prel", "c" to "conf",
            )
            rules["prole"] = enumRule(PartyRole.entries.map { it.name })
            rules["pkind"] = enumRule(PartyKind.entries.map { it.name })
            rules["prel"] = enumRule(PartyRelation.entries.map { it.name })
            rules["vtext"] = str(1, 100)
            // Enums are named rules: inlined they would split the enclosing alternative at their `|`.
            rules["arole"] = enumRule(Roles.AMOUNT)
            rules["drole"] = enumRule(Roles.DATE)
            rules["actionid"] = enumRule(SlotKey.ACTIONS)
            // No "NONE" alternative: an empty v means "not on this page". With the bare NONE option the
            // 0.8B model answered NONE for every slot, even for values printed on the page.
            rules["sval"] = str(0, 60)
            rules["amt"] = obj("v" to "sval", "r" to "arole", "c" to "conf")
            rules["date"] = obj("v" to "sval", "r" to "drole", "c" to "conf")
            rules["plain"] = obj("v" to "sval", "c" to "conf")
            rules["action"] = "${lit("NONE")} | " + obj("id" to "actionid", "c" to "conf")
            rules["xlist"] = "\"[\" ws (extra (ws \",\" ws extra){0,${MAX_EXTRAS - 1}})? ws \"]\""
            rules["extra"] = obj("lb" to str(1, StructuredGrammar.MAX_EXTRA_LABEL_CHARS), "k" to "xkey", "v" to str(1, 100), "c" to "conf")
            rules["xkey"] = "\"\\\"\" [a-z] [a-z_]{1,29} \"\\\"\""
            rules["qchar"] = "[^\"\\\\\\n\\r]"
            rules["ws"] = "[ ]?"
            return rules.entries.joinToString("\n") { (n, b) -> "$n ::= $b" }
        }
    }
}
