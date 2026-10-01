package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which recorded scoring letters the current code can still replay in full, and what it has to make up for the others.
 *
 * - A recording made for the current interpreter (a real `score:family` batch) must replay with no miss: a question that changed since the
 *   device run is a failing test here, naming the question, never a quiet zero ([everyRecordingOfTheCurrentInterpreterReplaysInFull]).
 * - A recording made before the families answers its family and topics from its legacy type scores ([LegacyFamilyBridge]: SCRIPTED); every
 *   other question it never held (the address labels, the summary, a slot the old type did not ask) is a MISS the report lists, and only
 *   a new device recording (variant `zonesscoring3`) removes it.
 *
 * Writes `$ZONES_COVERAGE_OUT` when set (the report); the replay is the shipped profile's.
 */
class RecordingCoverageTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings")).filter { it.variant.startsWith(InterpreterMetrics.SCORING_VARIANT) }
    private val profile = ModelProfiles.QWEN35_08B.scoring

    private fun isCurrent(rec: Recording) = rec.asks.any { it.name == "score:family" }

    @Test
    fun everyRecordingOfTheCurrentInterpreterReplaysInFull() {
        for ((m, f) in docs) {
            for (rec in recordings.filter { it.key == m.key && isCurrent(it) }) {
                assertThat(InterpreterMetrics.replayMisses(rec, f, profile)).isEmpty()
            }
        }
    }

    @Test
    fun coverage() {
        val out = System.getenv("ZONES_COVERAGE_OUT") ?: return
        val sb = StringBuilder("# Recording coverage under the current interpreter (shipped profile)\n\n")
        for ((m, f) in docs) {
            for (rec in recordings.filter { it.key == m.key }) {
                val misses = InterpreterMetrics.replayMisses(rec, f, profile)
                val kind = if (isCurrent(rec)) "CURRENT" else "LEGACY (family and topics SCRIPTED from the legacy type scores)"
                sb.appendLine("${m.key}.${rec.variant}: $kind: " + if (misses.isEmpty()) "replays in full" else "${misses.size} NOT RECORDED: ${misses.joinToString("; ")}")
            }
        }
        File(out).writeText(sb.toString())
    }
}
