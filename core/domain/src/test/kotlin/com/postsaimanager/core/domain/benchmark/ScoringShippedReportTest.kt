package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Scores the recorded `zonesscoring` runs exactly as the app decides them (the shipped qwen3.5-0.8b profile: its abstain
 * thresholds and confidence cuts) and lists, per letter, the language and the extras the model wrote. Writes
 * `$ZONES_SHIPPED_OUT` when set; a tool, it asserts nothing.
 */
class ScoringShippedReportTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File(System.getenv("ZONES_RECORDINGS_DIR") ?: "src/test/resources/benchmark/recordings"))
        .filter { it.variant == "zonesscoring" }
    private val profile = ModelProfiles.QWEN35_08B.scoring

    private fun languages(): Map<String, String?> =
        listOf("set1", "set2").flatMap { set ->
            val text = BenchmarkFixtures::class.java.getResourceAsStream("/benchmark/manifest-$set.json")?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isEmpty()) emptyList() else Json.parseToJsonElement(text).jsonObject.getValue("documents").jsonArray.map {
                it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject["language"]?.jsonPrimitive?.contentOrNull
            }
        }.toMap()

    @Test
    fun report() {
        val out = System.getenv("ZONES_SHIPPED_OUT") ?: return
        val pct = { v: Double -> String.format(Locale.ROOT, "%.1f%%", v * 100) }
        val score = InterpreterMetrics.score("zonesscoring", docs.filter { d -> recordings.any { it.key == d.first.key } }, recordings, profile)!!
        val sb = StringBuilder("# zonesscoring with the shipped qwen3.5-0.8b profile\n\n")
        sb.appendLine("| letters | field match | roles | hallucination | extras/doc | s/letter | first-candidate share |\n|---|---|---|---|---|---|---|")
        sb.appendLine(
            "| ${score.docs} | ${pct(score.fieldMatch)} | ${pct(score.rolesMatch)} | ${pct(score.hallucination)} | " +
                "${String.format(Locale.ROOT, "%.2f", score.extrasPerDoc)} | ${score.secondsPerDoc?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"} | ${score.first?.overall?.text()} |",
        )
        sb.appendLine("\nThe model's own confidence buckets, as the pipeline reads them after the verifier (accuracy, answers): " +
            score.calibration.entries.joinToString(", ") { "${it.key}: ${pct(it.value.first)} (${it.value.second})" })
        val truth = languages()
        var langRight = 0
        var langAsked = 0
        sb.appendLine("\n## Per letter\n")
        for ((m, f) in docs) {
            val rec = recordings.firstOrNull { it.key == m.key } ?: continue
            val result = InterpreterMetrics.replayResult(rec, f, profile)
            val expected = truth[m.key]
            langAsked++
            if (expected != null && result.language == expected) langRight++
            val ask = rec.asks.firstOrNull { it.name == "langextras" }
            sb.appendLine("### ${m.key}: language ${result.language} (manifest $expected), ${result.extras.size} extras, ${(rec.cost.wallMs ?: 0) / 1000.0} s")
            sb.appendLine("- ask (${ask?.ms ?: "-"} ms): `${ask?.answer}`")
            for (x in result.extras) sb.appendLine("- extra ${x.label} [${x.key}] = ${x.value.value} (${x.value.candidateId ?: "quote"}, conf ${x.value.confidence}, ai ${x.value.aiConfidence})")
            sb.appendLine("- rejections: ${result.diagnostics.rejections}")
        }
        sb.appendLine("\nLanguage right: $langRight of $langAsked.")
        File(out).writeText(sb.toString())
    }
}
