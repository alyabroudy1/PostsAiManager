package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Reports the scoring thresholds tuned on one fold of letters and tested on the other, both ways (see [ScoringTuner]).
 * Writes `$ZONES_TUNE_OUT` when set; a tool, it asserts nothing.
 */
class ZoneScoringTuneTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings"))

    @Test
    fun tune() {
        val out = System.getenv("ZONES_TUNE_OUT") ?: return
        val tuner = ScoringTuner(docs, recordings)
        val variants = recordings.map { it.variant }.filter { it.startsWith(InterpreterMetrics.SCORING_VARIANT) }.distinct().sorted()
        val sb = StringBuilder("# Scoring thresholds: tuned on one fold, tested on the other\n\n")
        for (variant in variants) {
            val (foldA, foldB) = tuner.folds(variant)
            if (foldA.size < 2 || foldB.size < 2) continue
            sb.appendLine("## $variant (fold A ${foldA.size} letters, fold B ${foldB.size})\n")
            sb.appendLine("| tuned on | tested on | default t=0: objective / field match / precision | tuned: objective / field match / precision | answers | global t |")
            sb.appendLine("|---|---|---|---|---|---|")
            fun f(e: ScoringTuner.Eval?) = if (e == null) "-" else String.format(Locale.ROOT, "%.3f / %.1f%% / %.1f%%", e.objective, e.fieldMatch * 100, e.precision * 100)
            for ((train, test, names) in listOf(Triple(foldA, foldB, "A" to "B"), Triple(foldB, foldA, "B" to "A"))) {
                val profile = tuner.tune(variant, train)
                val trainEval = tuner.eval(variant, train, profile)
                sb.appendLine(
                    "| ${names.first} (train objective ${String.format(Locale.ROOT, "%.3f", trainEval?.objective ?: 0.0)}) | ${names.second} | " +
                        "${f(tuner.eval(variant, test, ScoringProfile()))} | ${f(tuner.eval(variant, test, profile))} | ${tuner.eval(variant, test, profile)?.answers ?: 0} | ${profile.defaultThreshold} |",
                )
                sb.appendLine("\nthresholds tuned on ${names.first}: " + profile.thresholds.entries.joinToString { "${it.key}=${it.value}" } + "\n")
            }
            val full = tuner.tune(variant, foldA + foldB)
            sb.appendLine("Tuned on all letters (the profile to ship; no held-out number for it): default ${full.defaultThreshold}, " + full.thresholds.entries.joinToString { "${it.key}=${it.value}" } + "\n")
        }
        File(out).writeText(sb.toString())
    }
}
