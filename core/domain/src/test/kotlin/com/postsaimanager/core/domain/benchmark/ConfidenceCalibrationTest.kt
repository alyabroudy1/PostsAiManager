package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.zones.ScoreCuts
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * One scored answer of a recorded run with what its confidence rests on (the margin over the runner-up, the winner's own
 * score; both parsed from the value's `score margin` note) and whether it was right against the manifest.
 */
internal class ScoredAnswer(val key: String, val what: String, val margin: Double, val best: Double, val right: Boolean)

/** Fits [ScoreCuts] on scored answers, offline. Pure: the same answers always give the same cuts. */
internal object ScoreCutsFitter {

    /** The share a HIGH bucket should reach, and the share a LOW bucket should stay at or under. */
    const val HIGH_TARGET = 0.85
    const val LOW_CEILING = 0.50

    /**
     * A bucket needs at least this many answers in a training set to be used: fewer is an accident, not a cut point. The
     * training set must also beat the targets by [FIT_BUFFER]: cuts chosen where they just reach a target fall back under it
     * on letters they were not fitted on (the winner's curse), so the held-out share lands near the target instead.
     */
    const val MIN_ANSWERS = 6
    const val FIT_BUFFER = 0.05

    private val NEG = Double.NEGATIVE_INFINITY
    private val MARGINS = listOf(0.0, 0.03, 0.06, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5, 0.75, 1.0, 1.5, 2.0)
    private val BESTS = listOf(NEG, -1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, 1.0)

    private fun List<ScoredAnswer>.share(): Double = if (isEmpty()) 0.0 else count { it.right }.toDouble() / size

    /**
     * All four cut points at once, over a grid: the HIGH bucket must be at least [HIGH_TARGET] right and the LOW bucket at
     * most [LOW_CEILING] right, each with at least [MIN_ANSWERS] answers; among the settings that hold, the one that puts the
     * most answers in the two decisive buckets wins (ties: the more accurate HIGH). When no setting gives a LOW bucket that
     * holds, LOW is empty and only HIGH is decided; when none gives a HIGH that holds, nothing is HIGH.
     */
    fun fit(train: List<ScoredAnswer>): ScoreCuts {
        val inf = Double.POSITIVE_INFINITY
        val neverHigh = ScoreCuts(mediumMargin = NEG, mediumBest = NEG, highMargin = inf, highBest = NEG)
        var best = neverHigh
        var bestKey = Triple(-1, -1, 0.0) // (hasHigh and hasLow rank, decisive answers, HIGH share)
        for (hm in MARGINS) for (hb in BESTS) for (mm in listOf(NEG) + MARGINS) for (mb in BESTS) {
            val cuts = ScoreCuts(mediumMargin = mm, mediumBest = mb, highMargin = hm, highBest = hb)
            val high = train.filter { bucket(cuts, it) == "HIGH" }
            val low = train.filter { bucket(cuts, it) == "LOW" }
            if (high.size < MIN_ANSWERS || high.share() < HIGH_TARGET + FIT_BUFFER) continue
            val lowHolds = low.size >= MIN_ANSWERS && low.share() <= LOW_CEILING - FIT_BUFFER
            val key = Triple(if (lowHolds) 1 else 0, high.size + if (lowHolds) low.size else 0, high.share())
            if (!lowHolds && (mm != NEG || mb != NEG)) continue // no LOW bucket that holds: keep the medium cut open
            if (compareValuesBy(key, bestKey, { it.first }, { it.second }, { it.third }) > 0) {
                best = cuts
                bestKey = key
            }
        }
        return best
    }

    fun bucket(cuts: ScoreCuts, a: ScoredAnswer) = cuts.word(a.margin, a.best)
}

/**
 * Fits the confidence cut points of the scoring interpreter on the recorded scores and reports the calibration table per
 * bucket, cross-fitted: the letters are split into two folds (alternating, as the threshold tuner does), the cuts fitted on
 * one fold are applied to the other, both ways, and the two held-out tables are added. Writes `$ZONES_CALIB_OUT` when set;
 * a tool, it asserts nothing. The cuts to ship (fitted on every letter) are printed at the end.
 */
class ConfidenceCalibrationTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings")).filter { it.variant == "zonesscoring" }

    private val noteRegex = Regex("score margin ([+-][0-9.]+) \\(winner ([+-][0-9.]+) of (\\d+)\\)")

    private fun fits(name: String, expected: String) =
        ExtractionBenchmark.squash(name).contains(ExtractionBenchmark.squash(expected.substringBefore(',')))

    internal fun answers(): List<ScoredAnswer> {
        val out = ArrayList<ScoredAnswer>()
        for ((m, f) in docs) {
            val rec = recordings.firstOrNull { it.key == m.key } ?: continue
            // The abstain thresholds are off (every scored answer is kept), so every answer has its numbers.
            val result = InterpreterMetrics.replayResult(rec, f, ScoringProfile(defaultThreshold = -12.0))
            val expectations = ExtractionBenchmark.score(m, f).facts.map { it.exp }
            fun numbers(notes: List<String>) = notes.firstNotNullOfOrNull { noteRegex.find(it) }?.let { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
            for (v in result.slots.values + result.slotLists.values.flatten()) {
                if (v.slot?.kind == SlotKind.ACTION) continue
                val (margin, best) = numbers(v.notes) ?: continue
                out += ScoredAnswer(m.key, "slot:${v.slot?.json}", margin, best, expectations.any { Expectations.matchesValue(v.normalized, it) })
            }
            for (p in result.parties.all) {
                val (margin, best) = numbers(p.value.notes) ?: continue
                val right = when (p.role) {
                    PartyRole.SENDER -> m.senderName?.let { fits(p.name, it) }
                    PartyRole.ADDRESSEE -> m.roles.addressees.takeIf { it.isNotEmpty() }?.any { fits(p.name, it) }
                    else -> null
                } ?: continue
                out += ScoredAnswer(m.key, "party:${p.role.name.lowercase()}", margin, best, right)
            }
        }
        return out
    }

    private fun pct(a: Int, b: Int) = if (b == 0) "-" else String.format(Locale.ROOT, "%.0f%%", 100.0 * a / b)

    private fun table(title: String, cuts: List<ScoreCuts>, sets: List<List<ScoredAnswer>>): String {
        val buckets = listOf("LOW", "MEDIUM", "HIGH")
        val n = buckets.associateWith { 0 }.toMutableMap()
        val k = buckets.associateWith { 0 }.toMutableMap()
        for ((c, s) in cuts.zip(sets)) for (a in s) {
            val b = ScoreCutsFitter.bucket(c, a)
            n[b] = n.getValue(b) + 1
            if (a.right) k[b] = k.getValue(b) + 1
        }
        return "$title\n\n| bucket | answers | right | share right |\n|---|---|---|---|\n" +
            buckets.joinToString("\n") { "| $it | ${n[it]} | ${k[it]} | ${pct(k.getValue(it), n.getValue(it))} |" } +
            "\n| all | ${n.values.sum()} | ${k.values.sum()} | ${pct(k.values.sum(), n.values.sum())} |\n"
    }

    @Test
    fun calibrate() {
        val out = System.getenv("ZONES_CALIB_OUT") ?: return
        val all = answers()
        val keys = all.map { it.key }.distinct().sorted()
        val a = keys.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        val b = keys.filterIndexed { i, _ -> i % 2 == 1 }.toSet()
        val onA = all.filter { it.key in a }
        val onB = all.filter { it.key in b }
        val cutsFromA = ScoreCutsFitter.fit(onA)
        val cutsFromB = ScoreCutsFitter.fit(onB)
        val shipped = ScoreCutsFitter.fit(all)
        val sb = StringBuilder("# Confidence from scores: calibration\n\n")
        sb.appendLine("${all.size} scored answers on ${keys.size} letters (${all.count { it.what.startsWith("slot") }} slots, ${all.count { it.what.startsWith("party") }} parties); right = equals a manifest fact / the manifest's sender or addressee. The manifest is not exhaustive, so a share is a lower bound.\n")
        sb.appendLine("Fold A (${a.size} letters, ${onA.size} answers): ${cutsFromA}")
        sb.appendLine("Fold B (${b.size} letters, ${onB.size} answers): ${cutsFromB}\n")
        sb.appendLine(table("## Cross-fitted (cuts fitted on the other fold), both folds together", listOf(cutsFromB, cutsFromA), listOf(onA, onB)))
        sb.appendLine(table("### Fold B answers with cuts from A", listOf(cutsFromA), listOf(onB)))
        sb.appendLine(table("### Fold A answers with cuts from B", listOf(cutsFromB), listOf(onA)))
        sb.appendLine(table("## In-sample with the shipped cuts (fitted on all letters): $shipped", listOf(shipped), listOf(all)))
        sb.appendLine("## Share right by margin band (all answers, no cuts)\n\n| margin | answers | right | share |\n|---|---|---|---|")
        for ((lo, hi) in listOf(-9.0 to 0.03, 0.03 to 0.1, 0.1 to 0.2, 0.2 to 0.4, 0.4 to 0.75, 0.75 to 9.0)) {
            val s = all.filter { it.margin >= lo && it.margin < hi }
            sb.appendLine("| [$lo, $hi) | ${s.size} | ${s.count { it.right }} | ${pct(s.count { it.right }, s.size)} |")
        }
        sb.appendLine("\n## Share right by the winner's own score (all answers)\n\n| winner score | answers | right | share |\n|---|---|---|---|")
        for ((lo, hi) in listOf(-9.0 to -0.25, -0.25 to 0.0, 0.0 to 0.25, 0.25 to 0.5, 0.5 to 1.0, 1.0 to 9.0)) {
            val s = all.filter { it.best >= lo && it.best < hi }
            sb.appendLine("| [$lo, $hi) | ${s.size} | ${s.count { it.right }} | ${pct(s.count { it.right }, s.size)} |")
        }
        val mBands = listOf(-9.0 to 0.03, 0.03 to 0.2, 0.2 to 0.5, 0.5 to 9.0)
        val bBands = listOf(-9.0 to -0.25, -0.25 to 0.25, 0.25 to 0.6, 0.6 to 9.0)
        sb.appendLine("\n## Share right by margin band (rows) and winner score band (columns): right/answers\n")
        sb.appendLine("| margin \\ winner | " + bBands.joinToString(" | ") { "[${it.first}, ${it.second})" } + " |\n|---|" + bBands.joinToString("") { "---|" })
        for ((ml, mh) in mBands) {
            sb.appendLine("| [$ml, $mh) | " + bBands.joinToString(" | ") { (bl, bh) ->
                val s = all.filter { it.margin >= ml && it.margin < mh && it.best >= bl && it.best < bh }
                "${s.count { it.right }}/${s.size}"
            } + " |")
        }
        sb.appendLine("\n## The least accurate set of 8 or more answers the grid can cut (in-sample, for the record)\n")
        var worst: Triple<ScoreCuts, Int, Double>? = null
        for (mm in listOf(0.03, 0.06, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5)) for (mb in listOf(Double.NEGATIVE_INFINITY, -0.5, -0.25, 0.0, 0.25, 0.5)) {
            val low = all.filter { it.margin < mm || it.best < mb }
            if (low.size < 8) continue
            val share = low.count { it.right }.toDouble() / low.size
            if (worst == null || share < worst.third) worst = Triple(ScoreCuts(mediumMargin = mm, mediumBest = mb), low.size, share)
        }
        sb.appendLine(worst?.let { "LOW = margin < ${it.first.mediumMargin} or winner < ${it.first.mediumBest}: ${it.second} answers, ${pct((it.second * it.third).toInt(), it.second)} right" } ?: "none")
        sb.appendLine("\n## Share right per question (all answers)\n")
        for ((q, s) in all.groupBy { it.what }.toSortedMap()) sb.appendLine("- $q: ${s.count { it.right }}/${s.size}")
        File(out).writeText(sb.toString())
    }
}
