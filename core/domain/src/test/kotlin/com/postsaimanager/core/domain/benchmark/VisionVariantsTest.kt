package com.postsaimanager.core.domain.benchmark

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Scores the on-device recordings of the vision spike (workstream H): text only (`t`), text plus
 * page-1 image (`ti`), image only (`i`). Skipped when no recordings exist. Writes
 * `build/reports/vision-variants.md`.
 *
 * Recordings hold invented letters only (`src/test/resources/benchmark/recordings/<key>.<variant>.json`);
 * the phone run that produced them is `VisionBenchmarkTest` in `:core:ai:local` androidTest.
 */
class VisionVariantsTest {

    private val variants = listOf("t" to "text only", "ti" to "text + page-1 image", "i" to "image only")

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    @Test
    fun `scores the recorded variants`() {
        val dir = File("src/test/resources/benchmark/recordings")
        assumeTrue(dir.isDirectory && dir.list().orEmpty().any { it.endsWith(".t.json") || it.endsWith(".ti.json") || it.endsWith(".i.json") }, "no vision recordings")
        val loaded = BenchmarkFixtures.load()
        val docs = loaded.docs.filter { !it.first.web }
        val report = ExtractionBenchmark.run(docs)
        val manifests = docs.associate { it.first.key to it.first }
        val fixtures = docs.associate { it.first.key to it.second }
        val results = report.docs.associateBy { it.key }

        val sb = StringBuilder("# Vision variants (real model on phone, invented letters)\n\n")
        val perVariant = variants.associate { (v, _) -> v to RecordedVariant(dir, v, fixtures, manifests) }

        // Only documents recorded for every variant present are comparable.
        val present = variants.map { it.first }.filter { v -> docs.any { File(dir, "${it.first.key}.$v.json").exists() } }
        val keys = docs.map { it.first.key }.filter { k -> present.all { File(dir, "$k.$it.json").exists() } }
        sb.appendLine("Documents scored (recorded for all of ${present.joinToString()}): ${keys.size} - ${keys.joinToString()}\n")

        sb.appendLine("## Summary\n")
        sb.appendLine("| variant | fieldMatch (right/answered) | recall (right/expected) | rolesMatch | hallucination | extras/doc | ok answers | mean s/letter | calibration (bucket: accuracy) |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|")
        class Row(val right: Int, val asked: Int, val expected: Int)
        val rows = mutableMapOf<String, Row>()
        for (v in present) {
            val interp = perVariant.getValue(v)
            val pairs = keys.map { results.getValue(it) to manifests.getValue(it) }
            val s = InterpreterMetrics.score(pairs, interp) ?: continue
            var right = 0
            var asked = 0
            var expected = 0
            for ((res, _) in pairs) {
                val out = interp.replay(res.key)!!.doc
                for (f in res.facts) {
                    expected++
                    val a = out.fields[f.exp.field.substringBefore(' ')] ?: continue
                    asked++
                    if (ExtractionBenchmark.squash(a) == ExtractionBenchmark.squash(f.exp.norm)) right++
                }
            }
            rows[v] = Row(right, asked, expected)
            val replays = keys.map { interp.replay(it)!! }
            sb.appendLine(
                "| $v (${variants.first { it.first == v }.second}) | ${fmt(s.fieldMatch)} ($right/$asked) | ${fmt(right.toDouble() / expected)} ($right/$expected) | " +
                    "${fmt(s.rolesMatch)} | ${fmt(s.hallucination)} | ${String.format(Locale.ROOT, "%.2f", s.extrasPerDoc)} | " +
                    "${replays.count { it.parsed }}/${replays.size} | ${String.format(Locale.ROOT, "%.1f", replays.map { it.call1Ms }.average() / 1000)} | " +
                    "${s.calibration.entries.joinToString { "${it.key}: ${fmt(it.value)}" }} |",
            )
        }

        sb.appendLine("\n## Per document (right/expected facts; roles ok; hallucinated answers; call-1 seconds)\n")
        sb.appendLine("| doc | expected | " + present.joinToString(" | ") { "$it right" } + " | " + present.joinToString(" | ") { "$it roles" } + " | " + present.joinToString(" | ") { "$it halluc" } + " | " + present.joinToString(" | ") { "$it s" } + " |")
        sb.appendLine("|---|---|" + "---|".repeat(present.size * 4))
        for (k in keys) {
            val res = results.getValue(k)
            val m = manifests.getValue(k)
            val cells = present.map { v ->
                val r = perVariant.getValue(v).replay(k)!!
                val right = res.facts.count { f -> r.doc.fields[f.exp.field.substringBefore(' ')]?.let { ExtractionBenchmark.squash(it) == ExtractionBenchmark.squash(f.exp.norm) } == true }
                val roleOk = InterpreterMetrics.score(listOf(res to m), perVariant.getValue(v))?.rolesMatch
                val text = ExtractionBenchmark.squash(res.layout.plainText())
                val cand = res.candidateSet.candidates.map { ExtractionBenchmark.squash(it.normalized) }.toSet()
                val halluc = r.doc.fields.values.count { x ->
                    !x.isNullOrBlank() && ExtractionBenchmark.squash(x) !in cand && !text.contains(ExtractionBenchmark.squash(x))
                }
                arrayOf("$right", if (roleOk == null) "-" else if (roleOk >= 1.0) "ok" else "no", "$halluc", String.format(Locale.ROOT, "%.0f", r.call1Ms / 1000.0))
            }
            sb.appendLine("| $k | ${res.facts.size} | " + (0..3).joinToString(" | ") { i -> cells.joinToString(" | ") { it[i] } } + " |")
        }

        sb.appendLine("\n## Per field kind (right/expected)\n")
        sb.appendLine("| kind | expected | " + present.joinToString(" | ") + " |")
        sb.appendLine("|---|---|" + "---|".repeat(present.size))
        val kinds = keys.flatMap { results.getValue(it).facts }.map { it.exp.kind }.distinct().sortedBy { it.name }
        for (kind in kinds) {
            val cells = present.map { v ->
                var right = 0
                for (k in keys) {
                    val out = perVariant.getValue(v).replay(k)!!.doc
                    right += results.getValue(k).facts.count { f ->
                        f.exp.kind == kind &&
                            out.fields[f.exp.field.substringBefore(' ')]?.let { ExtractionBenchmark.squash(it) == ExtractionBenchmark.squash(f.exp.norm) } == true
                    }
                }
                right
            }
            val total = keys.sumOf { k -> results.getValue(k).facts.count { it.exp.kind == kind } }
            sb.appendLine("| $kind | $total | " + cells.joinToString(" | ") + " |")
        }

        val text = sb.toString()
        println(text)
        File("build/reports").mkdirs()
        File("build/reports/vision-variants.md").writeText(text)
    }
}
