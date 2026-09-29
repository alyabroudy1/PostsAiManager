package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Runs the benchmark, writes `build/benchmark/scoreboard.md` and prints a summary. Never fails on
 * scores (that is [BenchmarkGateTest]'s job); it fails only if fixtures are missing entirely.
 *
 * The interpreter section replays recorded model answers (see InterpreterExtension.kt); with no
 * recordings it says so and moves on.
 */
class ExtractionBenchmarkTest {

    @Test
    fun `runs on real OCR fixtures and writes the scoreboard`() {
        val loaded = BenchmarkFixtures.load()
        assertThat(loaded.docs).isNotEmpty()
        val report = ExtractionBenchmark.run(loaded.docs)
        val interpreter = InterpreterMetrics.scoreAll(loaded.docs, RECORDINGS)
        val file = ExtractionBenchmark.writeScoreboard(report, loaded.skipped, InterpreterMetrics.section(interpreter))
        println("BENCHMARK scoreboard: ${file.absolutePath}")
        report.metrics.forEach { (k, v) -> println(String.format(Locale.ROOT, "BENCHMARK %-24s %.4f", k, v)) }
        report.webMetrics.forEach { (k, v) -> println(String.format(Locale.ROOT, "BENCHMARK web.%-20s %.4f", k, v)) }
        if (interpreter.isEmpty()) println("BENCHMARK interpreter: no recordings")
        interpreter.forEach { s ->
            println(
                String.format(
                    Locale.ROOT, "BENCHMARK interpreter[%s] docs=%d shownNoise=%d fieldMatch=%.3f roles=%.3f hallucination=%.3f extrasPerDoc=%.2f calibration=%s",
                    s.variant, s.docs, s.shownNoise, s.fieldMatch, s.rolesMatch, s.hallucination, s.extrasPerDoc, s.calibration,
                ),
            )
        }
        // Per letter and interpreter, so a recording set can be compared letter by letter.
        val recs = Recordings.load(RECORDINGS)
        for ((m, f) in loaded.docs) {
            for ((variant, group) in recs.groupBy { it.variant }) {
                val s = InterpreterMetrics.score(variant, listOf(m to f), group.filter { it.key == m.key }) ?: continue
                println(
                    String.format(
                        Locale.ROOT, "BENCHMARK letter[%s][%s] fieldMatch=%.3f roles=%.3f hallucination=%.3f extras=%.2f seconds=%s",
                        m.key, variant, s.fieldMatch, s.rolesMatch, s.hallucination, s.extrasPerDoc, s.secondsPerDoc?.let { "%.1f".format(Locale.ROOT, it) } ?: "-",
                    ),
                )
            }
        }
        if (System.getProperty("benchmark.writeBaseline") == "true" || System.getenv("BENCHMARK_WRITE_BASELINE") == "true") {
            val f = File("build/benchmark/baseline.candidate.json")
            f.writeText(BaselineFile.render(report.metrics))
            println("BENCHMARK baseline candidate: ${f.absolutePath}")
        }
    }

    @Test
    fun `an unusable recording scores zero instead of failing`() {
        val docs = BenchmarkFixtures.load().docs.take(2)
        val recs = docs.map { Recording(it.first.key, "broken", "not json", null, 4096) }
        val score = InterpreterMetrics.score("broken", docs, recs)!!
        assertThat(score.docs).isEqualTo(2)
        assertThat(score.fieldMatch).isEqualTo(0.0)
        assertThat(InterpreterMetrics.section(emptyList())).contains("No recordings")
    }

    private companion object {
        val RECORDINGS = File("src/test/resources/benchmark/recordings")
    }
}
