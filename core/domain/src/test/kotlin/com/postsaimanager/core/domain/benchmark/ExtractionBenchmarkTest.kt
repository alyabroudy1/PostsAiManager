package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Runs the benchmark, writes `build/benchmark/scoreboard.md` and prints a summary. Never fails on
 * scores (that is [BenchmarkGateTest]'s job); it fails only if fixtures are missing entirely.
 */
class ExtractionBenchmarkTest {

    @Test
    fun `runs on real OCR fixtures and writes the scoreboard`() {
        val loaded = BenchmarkFixtures.load()
        assertThat(loaded.docs).isNotEmpty()
        val report = ExtractionBenchmark.run(loaded.docs)
        val file = ExtractionBenchmark.writeScoreboard(report, loaded.skipped)
        println("BENCHMARK scoreboard: ${file.absolutePath}")
        report.metrics.forEach { (k, v) -> println(String.format(Locale.ROOT, "BENCHMARK %-24s %.4f", k, v)) }
        report.webMetrics.forEach { (k, v) -> println(String.format(Locale.ROOT, "BENCHMARK web.%-20s %.4f", k, v)) }
        if (System.getProperty("benchmark.writeBaseline") == "true" || System.getenv("BENCHMARK_WRITE_BASELINE") == "true") {
            val f = File("build/benchmark/baseline.candidate.json")
            f.writeText(BaselineFile.render(report.metrics))
            println("BENCHMARK baseline candidate: ${f.absolutePath}")
        }
    }

    @Test
    fun `scores recorded interpreter answers when recordings exist`() {
        val dir = File("src/test/resources/benchmark/recordings")
        assumeTrue(dir.isDirectory && !dir.list().isNullOrEmpty(), "no recordings yet (see InterpreterExtension.kt)")
        val loaded = BenchmarkFixtures.load()
        val report = ExtractionBenchmark.run(loaded.docs)
        val byKey = loaded.docs.associate { it.first.key to it.first }
        val score = InterpreterMetrics.score(report.docs.map { it to byKey.getValue(it.key) }, ScriptedInterpreter(dir))
        println("BENCHMARK interpreter: ${score?.let(InterpreterMetrics::format)}")
    }
}
