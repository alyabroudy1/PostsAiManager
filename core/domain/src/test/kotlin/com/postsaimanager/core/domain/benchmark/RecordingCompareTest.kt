package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * A tool, not a test: scores the recorded scoring runs in `ZONES_RECORDINGS_DIR` (default: the committed recordings) letter by letter with
 * the shipped profile and writes the table to `REC_COMPARE_OUT`: field match, roles, hallucination and extras per letter and overall, so
 * two recordings of the same letters (an earlier and a later design) can be compared letter by letter. It asserts nothing.
 */
class RecordingCompareTest {

    private val profile = ModelProfiles.QWEN35_08B.scoring
    private val docs = BenchmarkFixtures.load().docs

    @Test
    fun perLetter() {
        val out = System.getenv("REC_COMPARE_OUT") ?: return
        val dir = File(System.getenv("ZONES_RECORDINGS_DIR") ?: "src/test/resources/benchmark/recordings")
        val recordings = Recordings.load(dir).filter { it.variant == (System.getenv("ZONES_VARIANT") ?: "zonesscoring") }
        val pct = { v: Double -> String.format(Locale.ROOT, "%.1f%%", v * 100) }
        val sb = StringBuilder("# per letter, shipped profile, recordings in $dir\n\n| letter | field match | roles | hallucination | extras | s |\n|---|---|---|---|---|---|\n")
        for ((m, f) in docs) {
            val rec = recordings.firstOrNull { it.key == m.key } ?: continue
            val s = InterpreterMetrics.score("zonesscoring", listOf(m to f), listOf(rec), profile) ?: continue
            sb.appendLine("| ${m.key} | ${pct(s.fieldMatch)} | ${pct(s.rolesMatch)} | ${pct(s.hallucination)} | ${String.format(Locale.ROOT, "%.0f", s.extrasPerDoc)} | ${s.secondsPerDoc?.let { String.format(Locale.ROOT, "%.0f", it) }} |")
        }
        val all = InterpreterMetrics.score("zonesscoring", docs.filter { d -> recordings.any { it.key == d.first.key } }, recordings, profile)!!
        sb.appendLine("| ALL | ${pct(all.fieldMatch)} | ${pct(all.rolesMatch)} | ${pct(all.hallucination)} | ${String.format(Locale.ROOT, "%.2f", all.extrasPerDoc)} | ${all.secondsPerDoc?.let { String.format(Locale.ROOT, "%.0f", it) }} |")
        File(out).writeText(sb.toString())
    }
}
