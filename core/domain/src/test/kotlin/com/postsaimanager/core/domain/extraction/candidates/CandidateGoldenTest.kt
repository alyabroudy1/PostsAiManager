package com.postsaimanager.core.domain.extraction.candidates

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.benchmark.BenchmarkFixtures
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.BlockZones
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

/**
 * Golden test for the whole candidate list: id, kind, raw, normalized, page, bbox, evidence, label
 * hint, subtype, validation and attributes of every candidate, for every benchmark fixture and for
 * the page inputs the extractor tests use. Recorded model answers reference candidate ids, so the
 * ids and their order must not change when the extractor is restructured.
 *
 * `golden-candidates.json` is the output; `golden-inputs.json` holds the recorded [page] inputs.
 * Regenerate the output on purpose with the environment variable `UPDATE_GOLDEN=1`.
 */
class CandidateGoldenTest {

    private class Case(val name: String, val run: () -> CandidateSet)

    @Test
    fun `candidate lists are identical to the golden file`() {
        val actual = render(cases())
        val target = File("src/test/resources/extraction/candidates/golden-candidates.json")
        if (System.getenv("UPDATE_GOLDEN") == "1") {
            target.parentFile.mkdirs()
            target.writeText(actual)
        }
        val expected = resource("golden-candidates.json")
        val a = actual.lines()
        val e = expected.lines()
        val at = a.indices.firstOrNull { a[it] != e.getOrNull(it) } ?: if (e.size > a.size) a.size else -1
        if (at >= 0) {
            throw AssertionError(
                "golden mismatch at line ${at + 1}\nexpected: ${e.getOrNull(at)}\nactual:   ${a.getOrNull(at)}",
            )
        }
        assertThat(actual).isEqualTo(expected)
    }

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        for ((manifest, fixture) in BenchmarkFixtures.load().docs) {
            out += Case("fixture:${manifest.key}") {
                val pageBlocks = fixture.pages.map { it.blocks }
                val zones = BlockZones.of(pageBlocks, LetterLayoutAnalyzer.analyze(pageBlocks))
                CandidateExtractor.extract(pageBlocks, zones)
            }
        }
        val inputs = Json.parseToJsonElement(resource("golden-inputs.json")).jsonArray
        inputs.forEachIndexed { i, el ->
            val o = el.jsonObject
            val pages = o.getValue("pages").jsonArray.map { p ->
                p.jsonArray.map { b ->
                    val bo = b.jsonObject
                    val r = bo.getValue("b").jsonArray.map { it.jsonPrimitive.content.toFloat() }
                    OcrBlock(bo.getValue("t").jsonPrimitive.content, TextBounds(r[0], r[1], r[2], r[3]), 0.9f)
                }
            }
            val zones = o.getValue("zones").jsonObject.entries.associate { (k, v) ->
                val (p, idx) = k.split(":").map { it.toInt() }
                BlockKey(p, idx) to BlockZone.valueOf(v.jsonPrimitive.content)
            }
            val letter = o["letterDate"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse)
            out += Case("input:%03d".format(i)) { CandidateExtractor.extract(pages, zones, letter) }
        }
        out += Case("text:plain") {
            CandidateExtractor.extractFromText("Datum: 28.09.2026\nGesamtbetrag 1.284,50 €\nIBAN DE89 3704 0044 0532 0130 00")
        }
        return out
    }

    private fun render(cases: List<Case>): String {
        val sb = StringBuilder("[\n")
        cases.forEachIndexed { ci, c ->
            val set = c.run()
            sb.append("{\"case\":").append(JsonPrimitive(c.name)).append(",\"letterDate\":")
                .append(set.letterDate?.let { JsonPrimitive(it.toString()) } ?: JsonNull).append(",\"candidates\":[\n")
            set.candidates.forEachIndexed { i, cand ->
                sb.append(candidateJson(cand)).append(if (i < set.candidates.size - 1) ",\n" else "\n")
            }
            sb.append("]}").append(if (ci < cases.size - 1) ",\n" else "\n")
        }
        return sb.append("]\n").toString()
    }

    private fun candidateJson(c: Candidate): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(c.id))
        put("kind", JsonPrimitive(c.kind.name))
        put("raw", JsonPrimitive(c.raw))
        put("normalized", JsonPrimitive(c.normalized))
        put("page", JsonPrimitive(c.page))
        put(
            "bbox",
            c.bbox?.let { JsonArray(listOf(it.left, it.top, it.right, it.bottom).map { f -> JsonPrimitive(f.toString()) }) } ?: JsonNull,
        )
        put("evidence", JsonPrimitive(c.evidence))
        put("label", JsonPrimitive(c.label))
        put("labelKind", c.labelKind?.let { JsonPrimitive(it.name) } ?: JsonNull)
        put("subtype", c.subtype?.let { JsonPrimitive(it.name) } ?: JsonNull)
        put("validation", JsonPrimitive(c.validation.toString().let { v -> if (c.validation is Validation.Invalid) "INVALID: ${(c.validation as Validation.Invalid).reason}" else v }))
        put("attrs", JsonArray(c.attrs.entries.map { (k, v) -> JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(v))) }))
    }

    private fun resource(name: String): String =
        CandidateGoldenTest::class.java.getResourceAsStream("/extraction/candidates/$name")!!.bufferedReader().use { it.readText() }
}
