package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.address.LineAsk
import com.postsaimanager.core.domain.extraction.layout.AddressShapes
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * A tool, not a test: fits the thresholds of the new questions (the family, the topics, the address labels and the delivery points) on the
 * recordings of the current interpreter (`zonesscoring3` in `$ZONES_RECORDINGS_DIR`, default the committed ones) and writes the report to
 * `$P4_FIT_OUT`. It asserts nothing.
 *
 * Method, per question:
 * - **family** (13 letters with an expected family, `FamilyExpectations`): for each threshold of a grid, the share of letters whose decision
 *   (the argmax family, or the abstain `free_form` when it is not above the threshold) is accepted. Cross-fitted leave-one-out: each letter is
 *   decided with the threshold chosen on the other letters (the middle of the plateau of the best thresholds); the in-sample plateau is
 *   what the profile takes.
 * - **topics** (16 letters, the manifests' topics): micro F1 of "the topics above the threshold" against the manifest's, the same leave-one-out.
 * - **addr_label**: the scored word-only lines whose text is a manifest name (the addressee, a routing person, a care-of party) have a truth
 *   label; the share of them labelled right at each threshold. The manifests state no other address truth, so this is a small, partial check.
 * - **addr_delivery**: no benchmark letter has a post office box or a locker, so every positive is a false one: the threshold has to sit
 *   above the highest score the model gave, which is reported.
 */
class P4ThresholdFitTest {

    private val docs = BenchmarkFixtures.load().docs
    private val dir = File(System.getenv("ZONES_RECORDINGS_DIR") ?: "src/test/resources/benchmark/recordings")
    private val recordings = Recordings.load(dir).filter { it.variant == "zonesscoring3" }
    private val schema = ExtractionSchema.DEFAULT
    private val families = schema.familiesFor(DocDirection.INCOMING)
    private val topics = schema.topics
    private val grid = (-40..40).map { it * 0.05 }

    private class Scores(val key: String, val family: List<Double>, val topic: List<Double>)

    private fun scoresOf(): List<Scores> = recordings.mapNotNull { rec ->
        val ask = rec.asks.firstOrNull { it.name == "score:family" } ?: return@mapNotNull null
        val all = ask.answer?.split(',')?.map { it.trim().toDouble() } ?: return@mapNotNull null
        Scores(rec.key, all.take(families.size), all.drop(families.size))
    }

    private fun decide(s: Scores, t: Double): String {
        val best = s.family.indices.maxByOrNull { s.family[it] }!!
        return if (s.family[best] > t) families[best].id else "free_form"
    }

    /** The middle of the thresholds of the grid that score best on [rows]; ties are the plateau. */
    private fun plateauMiddle(value: (Double) -> Double): Double {
        val scored = grid.map { it to value(it) }
        val top = scored.maxOf { it.second }
        val tied = scored.filter { it.second >= top - 1e-9 }.map { it.first }.sorted()
        return tied[tied.size / 2]
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    @Test
    fun fit() {
        val out = System.getenv("P4_FIT_OUT") ?: return
        val sb = StringBuilder("# Threshold fit on ${recordings.size} recordings of `zonesscoring3`\n\n")
        val scores = scoresOf()
        sb.appendLine("Shipped profile: family ${ModelProfiles.QWEN35_08B.scoring.familyThreshold}, topics ${ModelProfiles.QWEN35_08B.scoring.topicsThreshold}, " +
            "addr_label ${ModelProfiles.QWEN35_08B.scoring.threshold(LineAsk.LABEL_ASK)}, addr_delivery ${ModelProfiles.QWEN35_08B.scoring.threshold(LineAsk.DELIVERY_ASK)}.\n")

        // ── family ──
        val fam = scores.filter { FamilyExpectations.BY_KEY.containsKey(it.key) }
        fun right(rows: List<Scores>, t: Double) = rows.count { decide(it, t) in FamilyExpectations.BY_KEY.getValue(it.key) }.toDouble()
        sb.appendLine("## family (${fam.size} letters)\n\n| threshold | right |\n|---|---|")
        for (t in grid.filter { it in -1.0..1.0 && Math.round(it * 20) % 2L == 0L }) sb.appendLine("| ${f(t)} | ${right(fam, t).toInt()} |")
        val inSample = plateauMiddle { right(fam, it) }
        val loo = fam.count { held ->
            val train = fam.filter { it !== held }
            val t = plateauMiddle { right(train, it) }
            decide(held, t) in FamilyExpectations.BY_KEY.getValue(held.key)
        }
        sb.appendLine("\nIn-sample best plateau middle: **${f(inSample)}** (${right(fam, inSample).toInt()}/${fam.size} right). Leave-one-out: **$loo/${fam.size}** right.")
        sb.appendLine("\n| letter | expected | decided at ${f(inSample)} | best | second | margin |\n|---|---|---|---|---|---|")
        for (s in fam) {
            val order = s.family.indices.sortedByDescending { s.family[it] }
            sb.appendLine("| ${s.key} | ${FamilyExpectations.BY_KEY.getValue(s.key).joinToString("/")} | ${decide(s, inSample)} | ${families[order[0]].id} ${f(s.family[order[0]])} | ${families[order[1]].id} ${f(s.family[order[1]])} | ${f(s.family[order[0]] - s.family[order[1]])} |")
        }

        // ── topics ──
        val truth = docs.associate { it.first.key to it.first.topics.toSet() }
        val top = scores.filter { truth.containsKey(it.key) }
        fun counts(rows: List<Scores>, t: Double): Triple<Int, Int, Int> {
            var tp = 0; var fp = 0; var fn = 0
            for (s in rows) {
                val got = topics.indices.filter { s.topic[it] > t }.map { topics[it].id }.toSet()
                val want = truth.getValue(s.key)
                tp += got.intersect(want).size; fp += (got - want).size; fn += (want - got).size
            }
            return Triple(tp, fp, fn)
        }
        fun f1(rows: List<Scores>, t: Double): Double {
            val (tp, fp, fn) = counts(rows, t)
            return if (2 * tp + fp + fn == 0) 1.0 else 2.0 * tp / (2 * tp + fp + fn)
        }
        sb.appendLine("\n## topics (${top.size} letters, ${truth.values.sumOf { it.size }} manifest topics)\n\n| threshold | tp | fp | fn | F1 |\n|---|---|---|---|---|")
        for (t in grid.filter { it in -2.0..2.0 && Math.round(it * 20) % 4L == 0L }) {
            val (tp, fp, fn) = counts(top, t)
            sb.appendLine("| ${f(t)} | $tp | $fp | $fn | ${f(f1(top, t))} |")
        }
        val topIn = plateauMiddle { f1(top, it) }
        // Cross-fitted: the held-out letter's own counts at the threshold fitted on the others, pooled.
        var tp = 0; var fp = 0; var fn = 0
        for (held in top) {
            val t = plateauMiddle { f1(top.filter { it !== held }, it) }
            val (a, b, c) = counts(listOf(held), t)
            tp += a; fp += b; fn += c
        }
        sb.appendLine("\nIn-sample best plateau middle: **${f(topIn)}** (F1 ${f(f1(top, topIn))}). Leave-one-out pooled F1: **${f(2.0 * tp / maxOf(1, 2 * tp + fp + fn))}** (tp $tp, fp $fp, fn $fn).")

        // ── address labels and delivery points ──
        val labelCells = ArrayList<Triple<String, String, Map<LineAsk, Double>>>() // key, line, scores per statement
        val deliveryMax = ArrayList<Double>()
        for (rec in recordings) {
            val perLine = LinkedHashMap<String, MutableMap<LineAsk, Double>>()
            for (a in rec.asks.filter { it.name == "score:addr" }) {
                val ask = LineAsk.entries.firstOrNull { a.question.contains(" ${it.statement}? Answer:") } ?: continue
                val lines = a.question.split(ZoneScoringInterpreter.BATCH_SEPARATOR).map { it.substringAfter("Is «").substringBefore("»") }
                val values = a.answer?.split(',')?.map { it.trim().toDouble() } ?: continue
                lines.forEachIndexed { i, line -> if (i < values.size) perLine.getOrPut(line) { mutableMapOf() }[ask] = values[i] }
            }
            for ((line, map) in perLine) {
                if (LineAsk.DELIVERY_POINTS.any { it in map }) LineAsk.DELIVERY_POINTS.mapNotNull { map[it] }.maxOrNull()?.let { deliveryMax += it }
                if (LineAsk.LABELS.all { it in map }) labelCells += Triple(rec.key, line, map)
            }
        }
        fun truthOf(key: String, line: String): LineAsk? {
            val m = docs.firstOrNull { it.first.key == key }?.first ?: return null
            val folded = QuoteVerifier.fold(line)
            fun hit(names: List<String>) = names.any { n -> n.isNotBlank() && folded.contains(QuoteVerifier.fold(n.substringBefore(','))) }
            return when {
                hit(m.expected.filter { it.field.startsWith("routing_person") || it.field.startsWith("care_of") }.map { it.value }) -> LineAsk.ROUTING
                hit(m.roles.addressees) -> LineAsk.PERSON
                else -> null
            }
        }
        val labelled = labelCells.mapNotNull { (key, line, map) -> truthOf(key, line)?.let { Triple(key, line, it) to map } }
        sb.appendLine("\n## addr_label (${labelCells.size} scored word-only lines, ${labelled.size} with a truth label from the manifest)\n")
        sb.appendLine("| threshold | accepted | accepted and right |\n|---|---|---|")
        for (t in grid.filter { it in -1.0..1.0 && Math.round(it * 20) % 2L == 0L }) {
            val accepted = labelCells.count { c -> c.third.values.max() > t }
            val ok = labelled.count { (k, m) -> val best = LineAsk.LABELS.maxByOrNull { m[it] ?: -99.0 }!!; m.getValue(best) > t && best == k.third }
            sb.appendLine("| ${f(t)} | $accepted of ${labelCells.size} | $ok of ${labelled.size} |")
        }
        val argmaxRight = labelled.count { (k, m) -> LineAsk.LABELS.maxByOrNull { m[it] ?: -99.0 } == k.third }
        sb.appendLine("\nThe argmax label is the manifest's on $argmaxRight of ${labelled.size} labelled lines (a threshold cannot change that, only whether the label is taken).")
        sb.appendLine("\n## addr_delivery (${deliveryMax.size} street-shaped lines scored, no benchmark letter has a box or a locker)\n")
        sb.appendLine("Highest score the model gave for a box or a locker: ${deliveryMax.maxOrNull()?.let(::f) ?: "-"}; positives at the shipped threshold ${f(ModelProfiles.QWEN35_08B.scoring.threshold(LineAsk.DELIVERY_ASK))}: " +
            "${deliveryMax.count { it > ModelProfiles.QWEN35_08B.scoring.threshold(LineAsk.DELIVERY_ASK) }} (all false).")
        sb.appendLine("\nStreet-shape pattern sanity: ${AddressShapes.STREET_NUMBER.pattern.length} chars (unused, keeps the shape owner linked).")

        // ── the summary: how often the model's own sentences pass the gate, as recorded ──
        sb.appendLine("\n## summary: the gate on the recorded model answers\n")
        val gate = com.postsaimanager.core.domain.extraction.text.SummaryGate()
        var model = 0
        var template = 0
        val rows = StringBuilder("| letter | asks | outcome |\n|---|---|---|\n")
        for (rec in recordings) {
            val fixture = docs.firstOrNull { it.first.key == rec.key }?.second ?: continue
            val ocr = fixture.pages.joinToString("\n") { p -> p.blocks.joinToString("\n") { com.postsaimanager.core.domain.extraction.candidates.OcrText.normalizeChars(it.text) } }
            val asks = rec.asks.filter { it.name == "text:summary" }
            val accepted = asks.any { a ->
                val values = a.question.substringAfter("FACTS (verified; use only these):\n").substringBefore("\n\nQUESTION")
                    .lines().mapNotNull { l -> l.removePrefix("- ").substringAfter(": ", "").takeIf { it.isNotBlank() } }
                val answer = a.answer?.let { com.postsaimanager.core.domain.extraction.v2.AnswerReader.line(it) } ?: return@any false
                gate.check(answer, ocr, values) is com.postsaimanager.core.domain.extraction.text.SummaryGate.Verdict.Accepted
            }
            if (accepted) model++ else template++
            rows.appendLine("| ${rec.key} | ${asks.size} | ${if (accepted) "MODEL" else "TEMPLATE"} |")
        }
        sb.appendLine("The model's sentences were accepted for **$model of ${model + template}** letters; the template stands in for **$template** (**${f(template.toDouble() / maxOf(1, model + template) * 100)}%** fallback).\n")
        sb.append(rows)

        // ── the structured address, under the shipped profile ──
        sb.appendLine("\n## structured address (shipped profile, replayed)\n")
        val profile = ModelProfiles.QWEN35_08B.scoring
        var blocks = 0
        var verified = 0
        var withStreet = 0
        var nameRight = 0
        var named = 0
        var senders = 0
        val arows = StringBuilder("| letter | family | recipient verified | postcode / city / street | recipient name | sender address |\n|---|---|---|---|---|---|\n")
        for (rec in recordings) {
            val doc = docs.firstOrNull { it.first.key == rec.key } ?: continue
            val r = InterpreterMetrics.replayResult(rec, doc.second, profile)
            val a = r.addresses[com.postsaimanager.core.domain.extraction.v2.PartyRole.ADDRESSEE]
            val s = r.addresses[com.postsaimanager.core.domain.extraction.v2.PartyRole.SENDER]
            if (a == null) {
                arows.appendLine("| ${rec.key} | ${r.documentType?.id} | no block read | | | ${if (s != null) "yes" else "no"} |")
                continue
            }
            blocks++
            if (a.verified) verified++
            val parts = a.postcode != null && a.city != null && a.street != null
            if (parts) withStreet++
            if (s != null) senders++
            val expectedNames = doc.first.roles.addressees
            val got = a.recipientNames.map { QuoteVerifier.fold(it.value) }
            val ok = expectedNames.isNotEmpty() && expectedNames.all { n -> got.any { it.contains(QuoteVerifier.fold(n.substringBefore(','))) } }
            if (expectedNames.isNotEmpty()) { named++; if (ok) nameRight++ }
            arows.appendLine("| ${rec.key} | ${r.documentType?.id} | ${a.verified} | ${parts} | ${if (expectedNames.isEmpty()) "-" else ok.toString()} | ${if (s != null) "yes" else "no"} |")
        }
        sb.appendLine("Recipient blocks read: $blocks; verified: $verified; with postcode, city and street: $withStreet; recipient name equal to the manifest's: $nameRight of $named; sender address found: $senders.\n")
        sb.append(arows)
        File(out).writeText(sb.toString())
    }
}
