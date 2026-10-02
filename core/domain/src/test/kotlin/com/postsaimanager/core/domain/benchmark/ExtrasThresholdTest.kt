package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.zones.ScoringDescriptions
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * The threshold of the `extras` question on the recorded scores: a value becomes an extra only when its score is above it. Swept with the
 * other questions held at "take the best" (-12) as shipped, and cross-fitted (the best threshold of one half of the letters applied to the
 * other, both ways). Writes `$ZONES_EXTRAS_OUT` when set; a tool, it asserts nothing.
 *
 * The objective is the tuner's: `(right - 0.5 * wrong) / facts`, where an answer is right when it equals a manifest fact, so an extra that
 * is a real value the manifest does not list counts as wrong (a lower bound on precision, the same for every threshold).
 */
class ExtrasThresholdTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings")).filter { it.variant == "zonesscoring" }

    private fun profile(t: Double) = ScoringProfile(defaultThreshold = -12.0, thresholds = mapOf(ScoringDescriptions.EXTRAS_ASK to t))

    private val grid = (-8..8).map { it * 0.25 } + listOf(3.0, 99.0)

    @Test
    fun sweep() {
        val out = System.getenv("ZONES_EXTRAS_OUT") ?: return
        val tuner = ScoringTuner(docs, recordings)
        val all = recordings.map { it.key }.toSet()
        val (a, b) = tuner.folds("zonesscoring")
        fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)
        fun row(keys: Set<String>, t: Double): String {
            val d = docs.filter { it.first.key in keys }
            val r = recordings.filter { it.key in keys }
            val e = tuner.eval("zonesscoring", keys, profile(t))
            val s = InterpreterMetrics.score("zonesscoring", d, r, profile(t))
            return "| ${if (t == 99.0) "off" else String.format(Locale.ROOT, "%+.2f", t)} | ${s?.extrasPerDoc?.let { String.format(Locale.ROOT, "%.2f", it) }} | ${s?.let { pct(it.fieldMatch) }} | ${e?.let { String.format(Locale.ROOT, "%.3f", it.objective) }} | ${e?.answers} | ${e?.let { pct(it.precision) }} |"
        }
        val sb = StringBuilder("# The extras threshold\n\n## All ${all.size} letters\n\n| threshold | extras/doc | field match | objective | answers | precision |\n|---|---|---|---|---|---|\n")
        for (t in grid) sb.appendLine(row(all, t))
        fun best(keys: Set<String>) = grid.filter { it != 99.0 }.maxByOrNull { tuner.eval("zonesscoring", keys, profile(it))?.objective ?: -1e9 }!!
        // Ties go to the higher threshold: no extra without a gain.
        fun bestConservative(keys: Set<String>): Double {
            val scored = grid.filter { it != 99.0 }.map { it to (tuner.eval("zonesscoring", keys, profile(it))?.objective ?: -1e9) }
            val top = scored.maxOf { it.second }
            return scored.filter { it.second >= top - 1e-9 }.maxOf { it.first }
        }
        val tA = bestConservative(a)
        val tB = bestConservative(b)
        val tAll = bestConservative(all)
        sb.appendLine("\n## Cross-fitted\n\nBest on fold A (${a.size} letters): $tA; on fold B (${b.size}): $tB; on all: $tAll\n")
        sb.appendLine("| tested on | threshold from the other fold | extras/doc | field match | objective | answers | precision |\n|---|---|---|---|---|---|---|")
        for ((keys, t, name) in listOf(Triple(b, tA, "B"), Triple(a, tB, "A"))) {
            val e = tuner.eval("zonesscoring", keys, profile(t))
            val s = InterpreterMetrics.score("zonesscoring", docs.filter { it.first.key in keys }, recordings.filter { it.key in keys }, profile(t))
            val off = tuner.eval("zonesscoring", keys, profile(99.0))
            sb.appendLine("| $name | $t | ${s?.extrasPerDoc?.let { String.format(Locale.ROOT, "%.2f", it) }} | ${s?.let { pct(it.fieldMatch) }} | ${e?.let { String.format(Locale.ROOT, "%.3f", it.objective) }} (extras off ${off?.let { String.format(Locale.ROOT, "%.3f", it.objective) }}) | ${e?.answers} | ${e?.let { pct(it.precision) }} |")
        }
        sb.appendLine("\nThe best threshold by the plain objective (ties to the lowest): ${best(all)}")
        File(out).writeText(sb.toString())
    }
}
