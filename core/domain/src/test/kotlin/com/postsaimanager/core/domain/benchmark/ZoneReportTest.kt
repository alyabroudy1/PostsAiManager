package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.v2.PartyRole
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Writes the comparison tables of experiment Z to `$ZONES_REPORT_OUT` when set: every recorded variant on the letters it has
 * in common, the scoring variants decided with cross-fitted thresholds (a letter is decided with thresholds tuned on the other
 * half), per-zone answers and first-candidate shares, and one line per letter. A tool, it asserts nothing.
 */
class ZoneReportTest {

    private val docs = BenchmarkFixtures.load().docs
    private val all = Recordings.load(File("src/test/resources/benchmark/recordings"))

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)

    @Test
    fun report() {
        val out = System.getenv("ZONES_REPORT_OUT") ?: return
        val tuner = ScoringTuner(docs, all)
        val variants = all.groupBy { it.variant }.filterValues { it.size >= 2 }.keys.sorted()
        val sb = StringBuilder("# Experiment Z: comparison\n\n")

        val memo = HashMap<String, (String) -> com.postsaimanager.core.domain.extraction.zones.ScoringProfile>()
        fun profileFor(variant: String, crossFit: Boolean): (String) -> com.postsaimanager.core.domain.extraction.zones.ScoringProfile =
            if (crossFit) memo.getOrPut(variant) { tuner.crossFitted(variant) } else { _ -> com.postsaimanager.core.domain.extraction.zones.ScoringProfile() }

        // 1. Overall on each variant's own letters, and on the letters every listed variant has.
        fun keysOf(prefix: String) = all.filter { it.variant.startsWith(prefix) }.map { it.key }.toSet()
        val subsets = listOf(
            "all 16 letters" to docs.map { it.first.key }.toSet(),
            "the ${keysOf("zonesctx").size}-letter subset the +CTX variants were run on" to keysOf("zonesctx"),
            "the ${keysOf("zonesscoring2b").size}-letter subset the 2B model was run on" to keysOf("zonesscoring2b"),
        ).filter { it.second.isNotEmpty() }
        sb.appendLine("## Overall. A variant is listed on a subset only when it has a recording for every letter of it.\n")
        sb.appendLine("Scoring variants are decided with thresholds tuned on the plain `zonesscoring` recordings of the other half of the 16 letters (cross-fitted), or with t=0.\n")
        for ((title, keys) in subsets) {
            sb.appendLine("### $title\n")
            sb.appendLine("| variant | letters | field match | roles | hallucination | extras/doc | s/letter | first-candidate share |")
            sb.appendLine("|---|---|---|---|---|---|---|---|")
            for (v in variants) {
                val scoring = v.startsWith(InterpreterMetrics.SCORING_VARIANT)
                for (crossFit in if (scoring) listOf(false, true) else listOf(false)) {
                    val recs = all.filter { it.variant == v && it.key in keys }
                    if (recs.map { it.key }.toSet() != keys) continue
                    val d = docs.filter { (m, _) -> recs.any { it.key == m.key } }
                    val s = InterpreterMetrics.score(v, d, recs, profileFor("zonesscoring", crossFit)) ?: continue
                    val label = if (scoring) (if (crossFit) "$v (thresholds cross-fitted)" else "$v (t=0)") else v
                    sb.appendLine(
                        "| $label | ${s.docs} | ${pct(s.fieldMatch)} | ${pct(s.rolesMatch)} | ${pct(s.hallucination)} | ${"%.2f".format(Locale.ROOT, s.extrasPerDoc)} | " +
                            "${s.secondsPerDoc?.let { "%.1f".format(Locale.ROOT, it) } ?: "-"} | ${s.first?.overall?.text() ?: "-"} |",
                    )
                }
            }
            sb.appendLine()
        }

        // 2. Per zone / question: answers and correct answers, roles, first-candidate share.
        sb.appendLine("## Per question (answers given / correct against a manifest fact), cross-fitted thresholds for scoring\n")
        val questionKeys = linkedSetOf("sender", "addressee")
        val stats = HashMap<String, MutableMap<String, IntArray>>() // variant -> question -> [answers, correct]
        for (v in variants) {
            val fit = profileFor("zonesscoring", v.startsWith(InterpreterMetrics.SCORING_VARIANT))
            val m = stats.getOrPut(v) { HashMap() }
            for (rec in all.filter { it.variant == v }) {
                val (manifest, fixture) = docs.firstOrNull { it.first.key == rec.key } ?: continue
                val result = InterpreterMetrics.replayResult(rec, fixture, fit(rec.key))
                val facts = ExtractionBenchmark.score(manifest, fixture).facts.map { it.exp }
                for ((slot, value) in result.slots) {
                    questionKeys += slot.json
                    val cell = m.getOrPut(slot.json) { IntArray(2) }
                    cell[0]++
                    if (facts.any { Expectations.matchesValue(value.normalized, it) }) cell[1]++
                }
                fun fits(name: String, expected: String) =
                    ExtractionBenchmark.squash(name).contains(ExtractionBenchmark.squash(expected.substringBefore(',')))
                manifest.senderName?.let { exp ->
                    val cell = m.getOrPut("sender") { IntArray(2) }
                    cell[0]++
                    if (result.parties.sender?.name?.let { fits(it, exp) } == true) cell[1]++
                }
                if (manifest.roles.addressees.isNotEmpty()) {
                    val cell = m.getOrPut("addressee") { IntArray(2) }
                    val names = result.parties.all.filter { it.role == PartyRole.ADDRESSEE || it.role == PartyRole.CO_ADDRESSEE }.map { it.name }
                    cell[0]++
                    if (manifest.roles.addressees.all { a -> names.any { fits(it, a) } }) cell[1]++
                }
            }
        }
        sb.appendLine("| question | " + variants.joinToString(" | ") + " |")
        sb.appendLine("|---|" + variants.joinToString("") { "---|" })
        for (q in questionKeys) {
            sb.appendLine("| $q | " + variants.joinToString(" | ") { v -> stats[v]?.get(q)?.let { "${it[1]}/${it[0]}" } ?: "-" } + " |")
        }
        sb.appendLine("\n(sender and addressee count letters whose manifest names them; the others count values the model gave.)\n")

        sb.appendLine("## First-candidate share per zone (questions with two or more candidates)\n")
        val zoneNames = variants.flatMap { v -> FirstCandidateShare.of(all.filter { it.variant == v }).byZone.keys }.distinct().sorted()
        sb.appendLine("| variant | overall | " + zoneNames.joinToString(" | ") + " |")
        sb.appendLine("|---|---|" + zoneNames.joinToString("") { "---|" })
        for (v in variants) {
            val r = FirstCandidateShare.of(all.filter { it.variant == v })
            sb.appendLine("| $v | ${r.overall.text()} | " + zoneNames.joinToString(" | ") { r.byZone[it]?.text() ?: "-" } + " |")
        }
        sb.appendLine("\nScored variants have no zone label per batch; their per-question share:\n")
        for (v in variants.filter { it.startsWith(InterpreterMetrics.SCORING_VARIANT) }) {
            val r = FirstCandidateShare.of(all.filter { it.variant == v })
            sb.appendLine("- $v: " + r.byQuestion.entries.joinToString("; ") { "${it.key} ${it.value.text()}" })
        }

        // 3. Per letter.
        sb.appendLine("\n## Per letter: field match / roles / hallucination / seconds\n")
        sb.appendLine("| letter | " + variants.joinToString(" | ") + " |")
        sb.appendLine("|---|" + variants.joinToString("") { "---|" })
        for ((m, f) in docs) {
            sb.appendLine(
                "| ${m.key} | " + variants.joinToString(" | ") { v ->
                    val rec = all.firstOrNull { it.variant == v && it.key == m.key } ?: return@joinToString "-"
                    val scoring = v.startsWith(InterpreterMetrics.SCORING_VARIANT)
                    val s = InterpreterMetrics.score(v, listOf(m to f), listOf(rec), profileFor("zonesscoring", scoring)) ?: return@joinToString "-"
                    "${pct(s.fieldMatch)} / ${pct(s.rolesMatch)} / ${pct(s.hallucination)} / ${s.secondsPerDoc?.let { "%.0f".format(Locale.ROOT, it) } ?: "-"}"
                } + " |",
            )
        }
        File(out).writeText(sb.toString())
    }
}
