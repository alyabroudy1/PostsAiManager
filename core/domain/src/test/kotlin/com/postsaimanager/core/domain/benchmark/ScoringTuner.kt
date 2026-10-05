package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.zones.ScoringProfile

/**
 * Tunes the per-question abstain thresholds of the scoring interpreter on recorded raw scores, offline.
 *
 * The objective on a set of letters is `(correct - 0.5 * wrong) / expected facts`, where an answer is correct when it equals a
 * manifest fact: recall that also pays for a wrong answer, so "answer everything" does not win. The manifest is not exhaustive,
 * so a right answer it lacks counts as wrong: a lower bound on precision, the same for every setting.
 */
internal class ScoringTuner(
    private val docs: List<Pair<ManifestDoc, Fixture>>,
    private val recordings: List<Recording>,
) {
    class Eval(val objective: Double, val fieldMatch: Double, val precision: Double, val answers: Int)

    fun eval(variant: String, keys: Set<String>, profile: ScoringProfile): Eval? {
        val d = docs.filter { it.first.key in keys }
        val r = recordings.filter { it.variant == variant && it.key in keys }
        val s = InterpreterMetrics.score(variant, d, r, profile) ?: return null
        val answers = s.calibration.values.sumOf { it.second }
        val correct = s.calibration.values.sumOf { (acc, n) -> acc * n }
        val expected = d.sumOf { (m, f) -> ExtractionBenchmark.score(m, f).facts.count { it.found } }
        val obj = if (expected == 0) 0.0 else (correct - 0.5 * (answers - correct)) / expected
        return Eval(obj, s.fieldMatch, if (answers == 0) 1.0 else correct / answers, answers)
    }

    /** Whole numbers from -12 to 10, and quarters between -2 and 2, where the model's scores actually lie (they rarely pass 1.5 either way). */
    private val grid = ((-12..10).map { it.toDouble() } + (-8..8).map { it * 0.25 }).distinct().sorted()

    fun questions(variant: String, keys: Set<String>): List<String> =
        recordings.filter { it.variant == variant && it.key in keys }.flatMap { it.asks }.map { it.name }
            .filter { it.startsWith("score:") }.map { it.removePrefix("score:") }
            .filter { it != "type" && it != "household" && !it.startsWith("kind:") }.distinct().sorted()

    /** Best single threshold for all questions, then each question in turn, twice around. */
    fun tune(variant: String, keys: Set<String>): ScoringProfile {
        val best = grid.map { it to (eval(variant, keys, ScoringProfile(defaultThreshold = it))?.objective ?: -1e9) }.maxByOrNull { it.second }!!.first
        var thresholds = emptyMap<String, Double>()
        repeat(2) {
            for (q in questions(variant, keys)) {
                val scored = grid.map { t -> t to (eval(variant, keys, ScoringProfile(defaultThreshold = best, thresholds = thresholds + (q to t)))?.objective ?: -1e9) }
                val top = scored.maxOf { it.second }
                // Ties go to the threshold closest to the global one: no change without a gain.
                val pick = scored.filter { it.second >= top - 1e-9 }.minByOrNull { kotlin.math.abs(it.first - best) }!!.first
                thresholds = thresholds + (q to pick)
            }
        }
        return ScoringProfile(defaultThreshold = best, thresholds = thresholds)
    }

    /** The letters of [variant] split into two folds by alternating position in key order. */
    fun folds(variant: String): Pair<Set<String>, Set<String>> {
        val keys = recordings.filter { it.variant == variant }.map { it.key }.distinct().sorted()
        return keys.filterIndexed { i, _ -> i % 2 == 0 }.toSet() to keys.filterIndexed { i, _ -> i % 2 == 1 }.toSet()
    }

    /** Thresholds per letter, each letter decided with the profile tuned on the other fold. */
    fun crossFitted(variant: String): (String) -> ScoringProfile {
        val (a, b) = folds(variant)
        if (a.isEmpty() || b.isEmpty()) return { ScoringProfile() }
        val onA = tune(variant, a)
        val onB = tune(variant, b)
        return { key -> if (key in a) onB else onA }
    }
}
