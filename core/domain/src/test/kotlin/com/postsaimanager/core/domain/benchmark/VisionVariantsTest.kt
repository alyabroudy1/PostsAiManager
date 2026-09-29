package com.postsaimanager.core.domain.benchmark

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Scores the on-device recordings of the vision spike (workstream H) on the documents recorded for
 * every variant found: `t` text only, `ti` text + page-1 image, `i` image only, and any other
 * variant name (for example `t-2b`, a larger text model). Skipped without recordings. Writes
 * `build/reports/vision-variants.md`. See [VisionReplay] for how each kind is replayed.
 */
class VisionVariantsTest {

    private fun f3(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    @Test
    fun `scores the recorded variants`() {
        val dir = File("src/test/resources/benchmark/recordings")
        val names = dir.list().orEmpty().filter { it.endsWith(".json") }
        assumeTrue(names.isNotEmpty(), "no recordings")
        val docs = BenchmarkFixtures.load().docs.filter { !it.first.web }
        val replay = VisionReplay(dir, docs)
        val det = docs.associate { (m, f) -> m.key to ExtractionBenchmark.score(m, f) }
        val manifests = docs.associate { it.first.key to it.first }

        val variants = names.map { it.removeSuffix(".json").substringAfterLast('.') }.distinct().sorted()
        val keys = docs.map { it.first.key }.filter { k -> variants.all { File(dir, "$k.$it.json").exists() } }
        assumeTrue(keys.isNotEmpty(), "no document recorded for all variants: $variants")

        val sb = StringBuilder("# Vision variants (real model on the phone, invented letters)\n\n")
        sb.appendLine("Documents recorded for all of ${variants.joinToString()}: ${keys.size} (${keys.joinToString()})\n")

        class Tally(val variant: String) {
            var expected = 0; var right = 0; var foundExpected = 0; var foundRight = 0
            var given = 0; var bad = 0; var extras = 0; var roleDocs = 0; var roleRight = 0
            var ok = 0; var ms = 0L; var docs = 0
            val buckets = linkedMapOf("LOW" to (0 to 0), "MEDIUM" to (0 to 0), "HIGH" to (0 to 0))
        }

        val perDoc = linkedMapOf<String, MutableMap<String, String>>()
        val tallies = variants.associateWith { Tally(it) }
        for (v in variants) {
            val t = tallies.getValue(v)
            for (k in keys) {
                val out = replay.outcome(k, v) ?: continue
                val m = manifests.getValue(k)
                val d = det.getValue(k)
                t.docs++
                if (out.parsed) t.ok++
                t.ms += out.call1Ms
                var docRight = 0
                for (fact in d.facts) {
                    t.expected++
                    val hit = out.values.any { Expectations.matchesValue(it.normalized, fact.exp) }
                    if (hit) { t.right++; docRight++ }
                    if (fact.found) { t.foundExpected++; if (hit) t.foundRight++ }
                }
                val candNorms = d.candidateSet.candidates.map { ExtractionBenchmark.squash(it.normalized) }.toSet()
                var docBad = out.rejected
                t.given += out.values.size + out.rejected
                t.bad += out.rejected
                for (x in out.values) {
                    val isBad = !x.grounded && ExtractionBenchmark.squash(x.normalized) !in candNorms
                    if (isBad) { t.bad++; docBad++ }
                    val b = when { x.confidence < 0.5 -> "LOW"; x.confidence < 0.8 -> "MEDIUM"; else -> "HIGH" }
                    val ok = d.facts.any { Expectations.matchesValue(x.normalized, it.exp) }
                    val (n, kk) = t.buckets.getValue(b)
                    t.buckets[b] = (n + 1) to (kk + if (ok) 1 else 0)
                }
                t.extras += out.extras
                var roleOk: Boolean? = null
                if (m.roles.addressees.isNotEmpty() || m.senderName != null) {
                    t.roleDocs++
                    fun fits(name: String, exp: String) =
                        ExtractionBenchmark.squash(name).contains(ExtractionBenchmark.squash(exp.substringBefore(',')))
                    val s = m.senderName == null || out.sender?.let { fits(it, m.senderName!!) } == true
                    val a = m.roles.addressees.all { e -> out.addressees.any { fits(it, e) } }
                    roleOk = s && a
                    if (roleOk) t.roleRight++
                }
                perDoc.getOrPut(k) { linkedMapOf() }[v] =
                    "$docRight/${d.facts.size} ${roleOk?.let { if (it) "roles-ok" else "roles-no" } ?: "-"} bad=$docBad ${out.call1Ms / 1000}s"
            }
        }

        fun r(a: Int, b: Int) = if (b == 0) 0.0 else a.toDouble() / b
        sb.appendLine("## Summary\n")
        sb.appendLine("| variant | recall (right/all expected) | field match (of facts code found) | roles | hallucination | extras/doc | parsed | mean call-1 s | calibration (accuracy, n) |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (t in tallies.values) {
            sb.appendLine(
                "| ${t.variant} | ${f3(r(t.right, t.expected))} (${t.right}/${t.expected}) | ${f3(r(t.foundRight, t.foundExpected))} (${t.foundRight}/${t.foundExpected}) | " +
                    "${f3(r(t.roleRight, t.roleDocs))} (${t.roleRight}/${t.roleDocs}) | ${f3(r(t.bad, t.given))} (${t.bad}/${t.given}) | " +
                    "${String.format(Locale.ROOT, "%.2f", t.extras.toDouble() / t.docs)} | ${t.ok}/${t.docs} | " +
                    "${String.format(Locale.ROOT, "%.1f", t.ms / 1000.0 / t.docs)} | " +
                    t.buckets.entries.joinToString(", ") { "${it.key}: ${f3(r(it.value.second, it.value.first))} (${it.value.first})" } + " |",
            )
        }

        sb.appendLine("\n## Per document (facts right / expected, roles, hallucinated answers, call-1 seconds)\n")
        sb.appendLine("| doc | " + variants.joinToString(" | ") + " |")
        sb.appendLine("|---|" + "---|".repeat(variants.size))
        for ((k, cells) in perDoc) sb.appendLine("| $k | " + variants.joinToString(" | ") { cells[it] ?: "-" } + " |")

        sb.appendLine("\n## Per fact kind (right / expected)\n")
        sb.appendLine("| kind | expected | " + variants.joinToString(" | ") + " |")
        sb.appendLine("|---|---|" + "---|".repeat(variants.size))
        val kinds = keys.flatMap { det.getValue(it).facts }.map { it.exp.kindName }.distinct().sorted()
        for (kind in kinds) {
            val total = keys.sumOf { k -> det.getValue(k).facts.count { it.exp.kindName == kind } }
            val cells = variants.map { v ->
                keys.sumOf { k ->
                    val out = replay.outcome(k, v)
                    det.getValue(k).facts.count { f ->
                        f.exp.kindName == kind && out?.values?.any { Expectations.matchesValue(it.normalized, f.exp) } == true
                    }
                }
            }
            sb.appendLine("| $kind | $total | " + cells.joinToString(" | ") + " |")
        }

        val text = sb.toString()
        println(text)
        File("build/reports").mkdirs()
        File("build/reports/vision-variants.md").writeText(text)
    }
}
