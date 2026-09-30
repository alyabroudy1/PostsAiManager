package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.CandidateTable
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.zones.QuestionNames
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.TemplateMatcher
import com.postsaimanager.core.domain.extraction.zones.ZoneSetup
import com.postsaimanager.core.testing.FakeAiEngine
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Lists, per letter, every manifest fact the deterministic stage found that the zone-scoring reading did not return, and the
 * roles it got wrong, with what is needed to tell the cause: the zones the value's candidate is printed in, the zones each slot
 * of its kind was asked on, and what the recorded scores ranked. Writes `$ZONES_ANALYSIS_OUT` when set; a tool, it asserts nothing.
 */
class ZoneErrorAnalysisTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings")).filter { it.variant == "zonesscoring" }

    private fun scoredBatches(rec: Recording): Map<String, List<Pair<String, Double>>> =
        rec.asks.filter { it.name.startsWith("score:") && it.answer != null }.associate { a ->
            val qs = a.question.split("\n@@\n")
            val scores = a.answer!!.split(',').map { it.trim().toDouble() }
            a.name.removePrefix("score:") to qs.indices.map { i ->
                (qs[i].substringAfterLast("Is «", "").substringBefore("»").ifEmpty { "?" }) to scores.getOrElse(i) { Double.NaN }
            }
        }

    @Test
    fun analyse() {
        val out = System.getenv("ZONES_ANALYSIS_OUT") ?: return
        val sb = StringBuilder()
        for ((m, f) in docs) {
            val rec = recordings.firstOrNull { it.key == m.key } ?: continue
            val det = ExtractionBenchmark.score(m, f)
            // As the app decides it: the shipped profile's thresholds (take the best; an extra only above its threshold).
            val result = InterpreterMetrics.replayResult(rec, f, com.postsaimanager.core.domain.extraction.zones.ModelProfiles.QWEN35_08B.scoring)
            val offered = CandidateTable.build(det.candidateSet)
            val first = f.pages.firstOrNull()
            val setup = ZoneSetup(FakeAiEngine(), det.layout, offered, TemplateMatcher(), first?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height })
            val batches = scoredBatches(rec)
            sb.appendLine("=== ${m.key}  template=${setup.template.id} (${String.format(Locale.ROOT, "%.2f", setup.match.score)})")
            sb.appendLine("  zones with text: " + com.postsaimanager.core.domain.extraction.layout.LetterZone.entries.filter { setup.zoned.hasText(it) }.joinToString { it.tag })
            sb.appendLine("  type=${result.documentType?.id}  (type scores: " + (batches["type"]?.joinToString { String.format(Locale.ROOT, "%.2f", it.second) } ?: "-") + ")")
            // result slots
            sb.appendLine("  result slots: " + result.slots.entries.joinToString("; ") { (k, v) -> "${k.json}=${v.candidateId}:${v.normalized}" })
            sb.appendLine("  result parties: " + result.parties.all.joinToString("; ") { "${it.role}=${it.name}" })
            sb.appendLine("  manifest sender=${m.senderName} addressees=${m.roles.addressees}")
            val values = result.slots.values.map { it.normalized } + result.slotLists.values.flatten().map { it.normalized } + result.extras.map { it.value.normalized }
            for (fact in det.facts) {
                val hit = values.any { Expectations.matchesValue(it, fact.exp) }
                if (hit) continue
                val c = fact.candidate
                if (!fact.found || c == null) {
                    sb.appendLine("  MISS ${fact.exp.field} ${fact.exp.norm}: candidate missing (${fact.cause})")
                    continue
                }
                val oc = offered.rows.firstOrNull { it.candidate.normalized == c.normalized && it.candidate.kind == c.kind }?.candidate
                if (oc == null) {
                    sb.appendLine("  MISS ${fact.exp.field} ${fact.exp.norm} cand '${c.raw}': NOT OFFERED (over the per-kind cap; dropped=${offered.dropped})")
                    continue
                }
                val zones = setup.zoned.zonesOfCandidate(oc.id).joinToString { it.tag }
                val asked = (result.documentType?.slots ?: emptyList()).filter { oc.kind in it.kind.candidates }.joinToString { s ->
                    "${s.json}@" + setup.plan.zones(QuestionNames.slot(s.json), s).joinToString("/") { it.tag }
                }
                val ranks = batches.filterKeys { it.startsWith("slot:") }.entries.joinToString(" | ") { (q, list) ->
                    val idx = list.indexOfFirst { it.first == oc.raw.replace('\n', ' ') }
                    if (idx < 0) "" else q.removePrefix("slot:") + "=" + String.format(Locale.ROOT, "%.2f", list[idx].second) + "(best " + String.format(Locale.ROOT, "%.2f", list.maxOf { it.second }) + "/" + list.size + ")"
                }.replace(Regex("( \\| )+"), " | ").trim(' ', '|')
                sb.appendLine("  MISS ${fact.exp.field} ${fact.exp.norm} cand=${oc.id} '${oc.raw}' zones=[$zones] slotsOfKind=[$asked] scores=[$ranks]")
            }
            // roles
            fun fits(name: String, expected: String) = ExtractionBenchmark.squash(name).contains(ExtractionBenchmark.squash(expected.substringBefore(',')))
            m.senderName?.let { exp ->
                val got = result.parties.sender?.name
                if (got == null || !fits(got, exp)) {
                    val zs = offered.rows.filter { it.candidate.kind == CandidateKind.NAME && fits(it.candidate.raw, exp) }
                        .joinToString { "${it.candidate.id}@" + setup.zoned.zonesOfCandidate(it.candidate.id).joinToString("/") { z -> z.tag } }
                    sb.appendLine("  ROLE sender wrong: got='$got' expected='$exp' candidates-matching=[$zs] asked=${setup.plan.zones(QuestionNames.SENDER).joinToString { it.tag }}")
                    batches[QuestionNames.SENDER]?.let { sb.appendLine("     sender scores: " + it.joinToString { (t, s) -> "'$t'=" + String.format(Locale.ROOT, "%.2f", s) }) }
                }
            }
            if (m.roles.addressees.isNotEmpty()) {
                val names = result.parties.all.filter { it.role == PartyRole.ADDRESSEE || it.role == PartyRole.CO_ADDRESSEE }.map { it.name }
                for (a in m.roles.addressees) {
                    if (names.none { fits(it, a) }) {
                        val zs = offered.rows.filter { it.candidate.kind == CandidateKind.NAME && fits(it.candidate.raw, a.substringBefore(',')) }
                            .joinToString { "${it.candidate.id}@" + setup.zoned.zonesOfCandidate(it.candidate.id).joinToString("/") { z -> z.tag } }
                        sb.appendLine("  ROLE addressee wrong: got=$names expected='$a' candidates-matching=[$zs] asked=${setup.plan.zones(QuestionNames.ADDRESSEE).joinToString { it.tag }}")
                        batches[QuestionNames.ADDRESSEE]?.let { sb.appendLine("     addressee scores: " + it.joinToString { (t, s) -> "'$t'=" + String.format(Locale.ROOT, "%.2f", s) }) }
                    }
                }
            }
            sb.appendLine()
        }
        File(out).writeText(sb.toString())
    }
}
