package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.zones.DecoderKind
import com.postsaimanager.core.domain.extraction.zones.DecoderSpec
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.extraction.zones.ScoreCalibration
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.SharingRules
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Experiment E1: the slot decoders on the recorded score matrices of the 16 letters, offline. Every variant is read through the real
 * interpreter, pipeline and verifier; a weight or an abstain level is tuned on one half of the letters and tested on the other
 * (both ways, like the threshold tuner), and only those held-out numbers decide anything. Writes `$DECODER_OUT` when set; a
 * tool, it asserts nothing.
 */
class DecoderEvalTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings"))
    private val variant = InterpreterMetrics.SCORING_VARIANT
    private val scoring = recordings.filter { it.variant == variant }
    /** The model's scoring profile with the plain per-slot argmax: the baseline every decoder is compared with. */
    private val base: ScoringProfile = ModelProfiles.QWEN35_08B.scoring.copy(decoder = DecoderSpec())
    private val tuner = ScoringTuner(docs, recordings)
    private val folds = tuner.folds(variant)

    private fun withDecoder(spec: DecoderSpec) = base.copy(decoder = spec)

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)

    /** A family of decoders that differ only in tuned numbers; the first is the plainest. */
    private class Family(val label: String, val grid: List<DecoderSpec>)

    private val penalties = listOf(0.0, 0.25, 0.5, 1.0, 2.0)
    private val bonuses = listOf(0.0, 0.1, 0.25, 0.5, 1.0)

    private fun constrained(calibration: ScoreCalibration, temperature: Double = 1.0, pen: List<Double> = penalties, bon: List<Double> = bonuses) =
        pen.flatMap { p ->
            bon.map { b ->
                DecoderSpec(DecoderKind.JOINT_CONSTRAINED, calibration, temperature, dateOrderPenalty = p, tripleBonus = b)
            }
        }

    private val families: List<Family> = listOf(
        Family("joint (raw scores)", listOf(DecoderSpec(DecoderKind.JOINT))),
        Family(
            "joint + abstain level",
            listOf(null, -0.5, -0.25, 0.0, 0.25).map { DecoderSpec(DecoderKind.JOINT, abstain = it) },
        ),
        Family("joint + pair constraints (raw)", constrained(ScoreCalibration.RAW)),
        Family("joint + pair constraints, z-score", constrained(ScoreCalibration.ZSCORE)),
        Family("joint + pair constraints, rank", constrained(ScoreCalibration.RANK, pen = listOf(0.0, 0.1, 0.25, 0.5, 1.0), bon = listOf(0.0, 0.1, 0.25, 0.5, 1.0))),
        Family(
            "joint + pair constraints, softmax",
            listOf(0.1, 0.25, 0.5, 1.0).flatMap { t -> constrained(ScoreCalibration.SOFTMAX, t, pen = listOf(0.0, 0.5, 1.0, 2.0, 4.0), bon = listOf(0.0, 0.5, 1.0, 2.0)) },
        ),
        Family("joint, z-score, no pair terms", listOf(DecoderSpec(DecoderKind.JOINT, ScoreCalibration.ZSCORE))),
        Family("joint, rank, no pair terms", listOf(DecoderSpec(DecoderKind.JOINT, ScoreCalibration.RANK))),
        Family("joint, softmax, no pair terms", listOf(0.1, 0.25, 0.5, 1.0).map { DecoderSpec(DecoderKind.JOINT, ScoreCalibration.SOFTMAX, it) }),
        Family("joint, strict sharing (one value, one question)", listOf(DecoderSpec(DecoderKind.JOINT, sharing = SharingRules.NONE))),
    )

    /** Tuned on [train], the grid point with the best objective; a tie goes to the earlier (plainer) one. */
    private fun tune(f: Family, train: Set<String>): DecoderSpec {
        if (f.grid.size == 1) return f.grid.first()
        var best = f.grid.first()
        var bestObj = Double.NEGATIVE_INFINITY
        for (spec in f.grid) {
            val obj = tuner.eval(variant, train, withDecoder(spec))?.objective ?: continue
            if (obj > bestObj + 1e-9) { best = spec; bestObj = obj }
        }
        return best
    }

    private class Detail(val score: InterpreterScore, val perSlot: Map<String, IntArray>, val perLetter: Map<String, Triple<Double, Double, Double>>)

    private fun detail(profileFor: (String) -> ScoringProfile): Detail {
        val score = InterpreterMetrics.score(variant, docs, scoring, profileFor)!!
        val perSlot = linkedMapOf<String, IntArray>()
        val perLetter = linkedMapOf<String, Triple<Double, Double, Double>>()
        fun fits(name: String, expected: String) =
            ExtractionBenchmark.squash(name).contains(ExtractionBenchmark.squash(expected.substringBefore(',')))
        for ((m, f) in docs) {
            val rec = scoring.firstOrNull { it.key == m.key } ?: continue
            val result = InterpreterMetrics.replayResult(rec, f, profileFor(m.key))
            val facts = ExtractionBenchmark.score(m, f).facts.map { it.exp }
            for ((slot, value) in result.slots) {
                val cell = perSlot.getOrPut(slot.json) { IntArray(2) }
                cell[0]++
                if (facts.any { Expectations.matchesValue(value.normalized, it) }) cell[1]++
            }
            m.senderName?.let { exp ->
                val cell = perSlot.getOrPut("(sender)") { IntArray(2) }
                cell[0]++
                if (result.parties.sender?.name?.let { fits(it, exp) } == true) cell[1]++
            }
            if (m.roles.addressees.isNotEmpty()) {
                val cell = perSlot.getOrPut("(addressee)") { IntArray(2) }
                val names = result.parties.all.filter { it.role == PartyRole.ADDRESSEE || it.role == PartyRole.CO_ADDRESSEE }.map { it.name }
                cell[0]++
                if (m.roles.addressees.all { a -> names.any { fits(it, a) } }) cell[1]++
            }
            val s = InterpreterMetrics.score(variant, listOf(m to f), listOf(rec), profileFor(m.key))!!
            perLetter[m.key] = Triple(s.fieldMatch, s.rolesMatch, s.hallucination)
        }
        return Detail(score, perSlot, perLetter)
    }

    private fun facts(): Int = docs.sumOf { (m, f) -> ExtractionBenchmark.score(m, f).facts.count { it.found } }

    private fun row(label: String, s: InterpreterScore, extra: String = "") =
        "| $label | ${pct(s.fieldMatch)} | ${pct(s.rolesMatch)} | ${pct(s.hallucination)} | ${String.format(Locale.ROOT, "%.2f", s.extrasPerDoc)} | $extra |"

    @Test
    fun evaluate() {
        val out = System.getenv("DECODER_OUT") ?: return
        val (a, b) = folds
        val sb = StringBuilder("# E1: slot decoders on the recorded scores\n\n")
        sb.appendLine("${scoring.size} letters, ${facts()} manifest facts the extractor found (one fact = ${pct(1.0 / facts())} of field match); fold A ${a.size} letters, fold B ${b.size}. Decoders read the real recorded matrices through the real interpreter, pipeline and verifier.\n")

        val shipped = detail { base }
        val argmaxCf = detail(tuner.crossFitted(variant))
        sb.appendLine("## Baselines\n")
        sb.appendLine("| variant | field match | roles | hallucination | extras/doc | note |\n|---|---|---|---|---|---|")
        sb.appendLine(row("argmax (t = -12: always answer)", shipped.score, "plain per-slot baseline"))
        sb.appendLine(row("argmax, thresholds cross-fitted", argmaxCf.score, "Z's tuner, held out"))
        sb.appendLine()

        val heldOut = LinkedHashMap<String, Detail>()
        val chosen = LinkedHashMap<String, Pair<DecoderSpec, DecoderSpec>>()
        sb.appendLine("## Decoders, parameters tuned on one fold and tested on the other (both ways; every letter is decided with parameters fitted on the other 8)\n")
        sb.appendLine("| decoder | grid | held-out field match | held-out roles | held-out hallucination | held-out extras/doc | in-sample best (not a result) | tuned on A | tuned on B |\n|---|---|---|---|---|---|---|---|---|")
        for (f in families) {
            val onA = tune(f, a)
            val onB = tune(f, b)
            val d = detail { key -> withDecoder(if (key in a) onB else onA) }
            heldOut[f.label] = d
            chosen[f.label] = onA to onB
            val inSample = f.grid.maxOf { spec -> tuner.eval(variant, a + b, withDecoder(spec))?.fieldMatch ?: 0.0 }
            fun p(s: DecoderSpec) = "pen ${s.dateOrderPenalty}, bonus ${s.tripleBonus}, tau ${s.temperature}, abstain ${s.abstain}"
            sb.appendLine(
                "| ${f.label} | ${f.grid.size} | ${pct(d.score.fieldMatch)} | ${pct(d.score.rolesMatch)} | ${pct(d.score.hallucination)} | ${String.format(Locale.ROOT, "%.2f", d.score.extrasPerDoc)} | ${pct(inSample)} | ${if (f.grid.size > 1) p(onA) else "-"} | ${if (f.grid.size > 1) p(onB) else "-"} |",
            )
        }
        sb.appendLine()

        // Per-slot effects of every decoder against the shipped argmax.
        val slotNames = (shipped.perSlot.keys + heldOut.values.flatMap { it.perSlot.keys }).toSortedSet()
        sb.appendLine("## Per question: correct / answers (held-out decoders; slots count values the decoder gave, (sender) and (addressee) count letters)\n")
        sb.appendLine("| question | argmax | " + families.joinToString(" | ") { it.label } + " |")
        sb.appendLine("|---|---|" + families.joinToString("") { "---|" })
        for (n in slotNames) {
            fun c(d: Detail) = d.perSlot[n]?.let { "${it[1]}/${it[0]}" } ?: "-"
            sb.appendLine("| $n | ${c(shipped)} | " + families.joinToString(" | ") { c(heldOut.getValue(it.label)) } + " |")
        }
        sb.appendLine()

        // Per letter: field / roles of the shipped argmax against each decoder; only letters that differ are listed.
        sb.appendLine("## Per letter (field match / roles / hallucination), letters where any decoder differs from argmax\n")
        sb.appendLine("| letter | argmax | " + families.joinToString(" | ") { it.label } + " |")
        sb.appendLine("|---|---|" + families.joinToString("") { "---|" })
        fun t(x: Triple<Double, Double, Double>) = "${pct(x.first)} / ${pct(x.second)} / ${pct(x.third)}"
        for ((key, basis) in shipped.perLetter) {
            val cells = families.map { heldOut.getValue(it.label).perLetter.getValue(key) }
            if (cells.all { it == basis }) continue
            sb.appendLine("| $key | ${t(basis)} | " + cells.joinToString(" | ") { if (it == basis) "=" else t(it) } + " |")
        }
        File(out).writeText(sb.toString())
    }
}
