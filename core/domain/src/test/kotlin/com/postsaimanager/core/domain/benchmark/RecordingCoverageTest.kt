package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which recorded scoring letters the current code can still replay in full: a letter whose recording has no answer for a question the
 * interpreter now asks (a batch of candidates or a question that changed) has to be recorded again on the device; the others replay
 * as they are. Writes `$ZONES_COVERAGE_OUT` when set; a tool, it asserts nothing.
 */
class RecordingCoverageTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings")).filter { it.variant == "zonesscoring" }

    @Test
    fun coverage() {
        val out = System.getenv("ZONES_COVERAGE_OUT") ?: return
        val sb = StringBuilder()
        for ((m, f) in docs) {
            val rec = recordings.firstOrNull { it.key == m.key } ?: continue
            val replay = ZoneReplay(rec, ModelProfiles.QWEN35_08B.scoring)
            val first = f.pages.firstOrNull()?.takeIf { it.height > 0 }
            runBlocking { ExtractionV2Pipeline().run(f.pages.map { it.blocks }, replay, rec.contextTokens, first?.let { it.width.toFloat() / it.height }) }
            val unanswered = replay.transcript.filter { it.answer == null }.map { it.name }
            sb.appendLine("${m.key}: " + if (unanswered.isEmpty()) "replays in full" else "RE-RECORD, no recorded answer for ${unanswered.joinToString()}")
        }
        File(out).writeText(sb.toString())
    }
}
