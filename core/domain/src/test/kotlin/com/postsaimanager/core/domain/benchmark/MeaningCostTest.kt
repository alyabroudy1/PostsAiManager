package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

/**
 * How many scored questions the meaning of the dates and amounts and the baselines of the party and reference questions add to a reading,
 * counted on the recorded letters' replay (extraction-v2-14). A count of questions, never a timing: the replay answers them as "not
 * recorded", so only how many are asked is known here, and what they cost on the phone is watched in normal use.
 * `$MEANING_COST_OUT` writes the table.
 */
class MeaningCostTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings")).filter { it.variant.startsWith(InterpreterMetrics.SCORING_VARIANT) }
    private val profile = ModelProfiles.QWEN35_08B.scoring

    private fun statements(question: String) = question.split(ZoneScoringInterpreter.BATCH_SEPARATOR).size

    @Test
    fun `the meaning and baseline questions a reading adds are few next to the questions it already asked`() {
        val lines = StringBuilder("letter | scored questions | meaning | baselines (party, reference, meaning)\n")
        var letters = 0
        var meaningTotal = 0
        var baselineTotal = 0
        var allTotal = 0
        for ((m, f) in docs) {
            val rec = recordings.firstOrNull { it.key == m.key && it.asks.any { a -> a.name == "score:family" } } ?: continue
            val replay = ZoneReplay(rec, profile)
            val first = f.pages.firstOrNull()?.takeIf { it.height > 0 }
            runBlocking { ExtractionV2Pipeline().run(f.pages.map { it.blocks }, replay, rec.contextTokens, first?.let { it.width.toFloat() / it.height }) }
            val scored = replay.transcript.filter { it.name.startsWith("score:") }
            val meaning = scored.filter { it.name.startsWith("score:meaning:") }.sumOf { statements(it.question) }
            val baselines = scored.filter { it.name.startsWith("score:baseline:") }.sumOf { statements(it.question) }
            val all = scored.sumOf { statements(it.question) }
            lines.appendLine("${m.key} | $all | $meaning | $baselines")
            letters++
            meaningTotal += meaning
            baselineTotal += baselines
            allTotal += all
            // The questions are batched per value: far fewer than a score per candidate and meaning.
            assertThat(meaning + baselines).isLessThan(all)
        }
        lines.appendLine("mean over $letters letters | ${allTotal / letters} | ${meaningTotal / letters} | ${baselineTotal / letters}")
        System.getenv("MEANING_COST_OUT")?.let { File(it).writeText(lines.toString()) }
        println(lines)
        assertThat(letters).isGreaterThan(0)
    }
}
