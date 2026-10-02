package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Locale

/** Baseline file format: `{"metrics": {"recall.all": 0.83, ...}}`. Lower-is-better: `shownNoise` and names starting `leak`. Recorded but never gated: [NOT_GATED]. */
object BaselineFile {
    fun render(metrics: Map<String, Double>): String =
        "{\n  \"metrics\": {\n" +
            metrics.entries.joinToString(",\n") { String.format(Locale.ROOT, "    \"%s\": %.4f", it.key, it.value) } +
            "\n  }\n}\n"

    fun load(): Map<String, Double> {
        val text = BaselineFile::class.java.getResourceAsStream("/benchmark/baseline.json")!!.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject.getValue("metrics").jsonObject
            .mapValues { it.value.jsonPrimitive.content.toDouble() }
    }

    fun lowerIsBetter(name: String) = name == "shownNoise" || name.startsWith("leak")

    /** Informational: offering receipt ids to the model is intended, so the count is recorded only. */
    val NOT_GATED = setOf("offeredNoise")
}

/**
 * Fails when a metric is worse than its baseline by more than [EPSILON]. Separate from the benchmark
 * run so it can be excluded (`--tests '*BenchmarkGateTest' ` is skipped with `-PskipBenchmarkGate` style filters)
 * while extraction is in flux. Improvements never fail; re-record the baseline deliberately:
 * run with BENCHMARK_WRITE_BASELINE=true and copy build/benchmark/baseline.candidate.json over
 * src/test/resources/benchmark/baseline.json.
 */
class BenchmarkGateTest {

    @Test
    fun `no metric drops below the baseline`() {
        val baseline = BaselineFile.load()
        val current = ExtractionBenchmark.run(BenchmarkFixtures.load().docs.filter { !it.first.web }).metrics
        val regressions = baseline.filterKeys { it !in BaselineFile.NOT_GATED }.mapNotNull { (name, base) ->
            val now = current[name] ?: return@mapNotNull "$name: missing from current run"
            val worse = if (BaselineFile.lowerIsBetter(name)) now > base + COUNT_EPSILON else now < base - EPSILON
            if (worse) String.format(Locale.ROOT, "%s: %.4f (baseline %.4f)", name, now, base) else null
        }
        assertThat(regressions).isEmpty()
    }

    private companion object {
        const val EPSILON = 0.005
        const val COUNT_EPSILON = 0.0
    }
}
