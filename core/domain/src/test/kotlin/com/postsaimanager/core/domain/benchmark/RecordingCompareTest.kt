package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * A tool, not a test: compares a fresh device recording of the scoring interpreter (`REC_COMPARE_DIR`) with the recorded
 * baseline, both decided with the shipped profile, and writes the table to `REC_COMPARE_OUT`. It asserts nothing.
 *
 * Per letter: the picks (slots, parties) that differ between the two, and for every question both recordings hold
 * (same text), how far the scores moved. The metrics are the benchmark's (field match, roles, hallucination, extras).
 */
class RecordingCompareTest {

    private val profile = ModelProfiles.QWEN35_08B.scoring
    private val docs = BenchmarkFixtures.load().docs

    private fun shape(r: ExtractionV2Result): List<String> =
        listOf("type=${r.documentType?.id}") + r.slots.map { (k, v) -> "${k.json}=${v.normalized}/${v.role}" }.sorted() +
            r.parties.all.map { "${it.role}=${it.name}/${it.kind}" }.sorted()

    private fun scores(rec: Recording): Map<String, List<Double>> = rec.asks.filter { it.name.startsWith("score:") && it.answer != null }
        .associate { a -> a.question to a.answer!!.split(',').map { it.trim().toDouble() } }

    @Test
    fun compare() {
        val newDir = System.getenv("REC_COMPARE_DIR") ?: return
        val out = System.getenv("REC_COMPARE_OUT") ?: return
        val base = Recordings.load(File("src/test/resources/benchmark/recordings")).filter { it.variant == "zonesscoring" }
        val fresh = Recordings.load(File(newDir)).filter { it.variant == "zonesscoring" }
        val keys = fresh.map { it.key }.filter { k -> base.any { it.key == k } }
        val pct = { v: Double -> String.format(Locale.ROOT, "%.1f%%", v * 100) }
        val sb = StringBuilder("# recordings: baseline vs $newDir\n\n")
        for ((label, recs) in listOf("baseline" to base, "new" to fresh)) {
            val s = InterpreterMetrics.score("zonesscoring", docs.filter { d -> d.first.key in keys }, recs.filter { it.key in keys }, profile)!!
            sb.appendLine(
                "- $label: letters=${s.docs} fieldMatch=${pct(s.fieldMatch)} roles=${pct(s.rolesMatch)} hallucination=${pct(s.hallucination)} " +
                    "extras/doc=${String.format(Locale.ROOT, "%.2f", s.extrasPerDoc)} shownNoise=${s.shownNoise} s/letter=${s.secondsPerDoc?.let { String.format(Locale.ROOT, "%.1f", it) }}",
            )
        }
        sb.appendLine()
        var allDeltas = 0
        var big = 0
        var flips = 0
        var maxDelta = 0.0
        for (k in keys) {
            val (m, f) = docs.first { it.first.key == k }
            val b = base.first { it.key == k }
            val n = fresh.first { it.key == k }
            val rb = shape(InterpreterMetrics.replayResult(b, f, profile))
            val rn = shape(InterpreterMetrics.replayResult(n, f, profile))
            val diff = (rb - rn.toSet()).map { "-$it" } + (rn - rb.toSet()).map { "+$it" }
            sb.appendLine("## $k: ${if (diff.isEmpty()) "same picks" else "DIFFERENT picks"}")
            diff.forEach { sb.appendLine("  $it") }
            val sb1 = scores(b)
            val sn = scores(n)
            var letterMax = 0.0
            var compared = 0
            for ((q, vb) in sb1) {
                val vn = sn[q] ?: continue
                if (vn.size != vb.size) continue
                compared += vb.size
                for (i in vb.indices) {
                    val d = abs(vb[i] - vn[i])
                    allDeltas++
                    if (d > 0.3) big++
                    if ((vb[i] > 0) != (vn[i] > 0)) flips++
                    if (d > letterMax) letterMax = d
                    if (d > maxDelta) maxDelta = d
                }
            }
            sb.appendLine("  scores compared=$compared maxDelta=${String.format(Locale.ROOT, "%.2f", letterMax)} questionsInBoth=${sb1.keys.count { it in sn }} of ${sb1.size}/${sn.size}")
        }
        sb.appendLine("\nTotal scores compared=$allDeltas, moved by >0.3: $big, sign flips: $flips, max delta ${String.format(Locale.ROOT, "%.2f", maxDelta)}")
        File(out).writeText(sb.toString())
    }
}
