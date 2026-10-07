package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.Letter
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.extraction.zones.ScoringDescriptions
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A replay never reads a question the recording cannot answer as a silent zero.
 *
 * - A recording made for the current interpreter (it holds a real `score:family` batch) must answer everything the interpreter asks:
 *   a question that changed since the device run fails the replay, naming it.
 * - A recording made before the families answers its family and topic batch from its legacy type scores ([LegacyFamilyBridge]) and lists
 *   every other question it lacks (the address labels, the summary, a slot the old type never asked) as a miss, without failing.
 */
class ReplayStrictnessTest {

    private val letter: Letter = Letters.invoice
    private val profile = ModelProfiles.QWEN35_08B.scoring
    /** What the device runner records with: the shipped profile without its abstain thresholds (the default -12 abstains from nothing that matters). */
    private val recordingProfile = ModelProfiles.recordingProfile(profile)

    /** One live run of the real interpreter on a fake session, as the device runner would have recorded it. */
    private fun liveRecording(
        variant: String = "zonesscoring3",
        keep: (String) -> Boolean = { true },
        /** What the recording holds as the question of an ask (the device recorded it as it was asked; a test changes it to be a question the code no longer asks). */
        question: (name: String, text: String) -> String = { _, text -> text },
    ): Recording {
        // The pages of the benchmark fixture the replay will read, with the page shape it is given, so the same layout and candidates are found.
        val pages = fixture.pages.map { it.blocks }
        val first = fixture.pages.firstOrNull()?.takeIf { it.height > 0 }
        val p = Prepared(pages)
        val session = FakePromptSession().apply {
            // The addressee is found too, so an address is read (a document with an addressee has a recipient block).
            scorer = { c ->
                val addressee = c.contains("the addressee") && !c.contains(ScoringDescriptions.PARTY_BASELINE_NAME)
                if (c.contains("an invoice, a bill") || c.contains("the date of the letter itself") || addressee) 4.0 else -4.0
            }
            responder = { q, _ -> if (q.contains("BCP-47")) "de" else "\"Rechnung Nr. RE-2026-0815\"" }
        }
        // As the device runner records: every candidate scored and nothing abstained, but with the shipped decoder, so the decisions the
        // summary's facts rest on are the ones a replay under the shipped profile makes again.
        // As shipped (and as the replay reads it): the topics are scored with the category, in the second stage.
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, profile = recordingProfile, topicsInFirstStage = false)
        runBlocking {
            com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline().run(pages, interpreter, 4096, first?.let { it.width.toFloat() / it.height })
        }
        val json = buildJsonObject {
            put("interpreter", "zonesscoring")
            put("contextTokens", 4096)
            put(
                "asks",
                buildJsonArray {
                    interpreter.transcript.filter { keep(it.name) }.forEach { a ->
                        add(buildJsonObject { put("name", a.name); put("question", question(a.name, a.question)); put("answer", a.answer); put("ms", 10) })
                    }
                },
            )
            put(
                "candidates",
                buildJsonArray {
                    for (row in p.offered.rows) {
                        add(
                            buildJsonObject {
                                put("id", row.candidate.id); put("kind", row.candidate.kind.name)
                                put("raw", row.candidate.raw); put("normalized", row.candidate.normalized); put("page", row.pages.first())
                            },
                        )
                    }
                },
            )
        }
        return Recordings.parse(letter.id, variant, json.toString())!!
    }

    private val fixture: Fixture get() = BenchmarkFixtures.load().docs.first { it.first.key == letter.id }.second

    @Test
    fun `a recording of the current interpreter replays in full, with no miss`() {
        val rec = liveRecording()
        assertThat(rec.asks.map { it.name }).contains("score:family")
        val result = InterpreterMetrics.replayResult(rec, fixture, profile)
        assertThat(result.documentType).isEqualTo(ExtractionSchema.INVOICE_BILL)
        assertThat(result.diagnostics.modelUsed).isTrue()
    }

    @Test
    fun `a recording whose address labels are not the ones asked now fails the replay, naming the question`() {
        // The recording read an address, with other lines than the ones asked now: a question that changed since the device run.
        val rec = liveRecording(question = { name, text -> if (name == "score:addr") text.replace("[address-block]", "[another-block]") else text })
        assertThat(rec.asks.any { it.name == "score:addr" }).isTrue()
        val e = assertThrows(IllegalStateException::class.java) { InterpreterMetrics.replayResult(rec, fixture, profile) }
        assertThat(e.message).contains("has no answer for")
        assertThat(e.message).contains("record it again on the device")
    }

    @Test
    fun `a recording that read no address replays the address labels as not recorded, listed and never a quiet zero`() {
        // The recorded reading found no addressee (or its type had no recipient block); the reading now reads an address for any addressee.
        val rec = liveRecording(keep = { it != "score:addr" })
        val result = InterpreterMetrics.replayResult(rec, fixture, profile)
        assertThat(result.diagnostics.modelUsed).isTrue()
        val misses = InterpreterMetrics.replayMisses(rec, fixture, profile)
        assertThat(misses.hard).isEmpty()
        assertThat(misses.unrecorded.any { it.contains("[address-block]") }).isTrue()
    }

    @Test
    fun `a recording that lacks the summary is replayed with the template summary, and the miss is listed as scripted`() {
        val rec = liveRecording(keep = { it != "text:summary" })
        val result = InterpreterMetrics.replayResult(rec, fixture, profile)
        assertThat(result.summary?.code).isEqualTo("template")
        val misses = InterpreterMetrics.replayMisses(rec, fixture, profile)
        assertThat(misses.hard).isEmpty()
        assertThat(misses.scripted).isNotEmpty()
    }

    @Test
    fun `a recording that lacks the family batch is not read as an empty result`() {
        // Without score:family (and without the legacy score:type) nothing answers the family: the reading fails, loudly.
        val rec = liveRecording(keep = { it != "score:family" })
        val e = assertThrows(IllegalStateException::class.java) { InterpreterMetrics.replayResult(rec, fixture, profile) }
        assertThat(e.message).contains("has no answer for")
        assertThat(e.message).contains("Is this document")
    }

    @Test
    fun `a recording made before the families answers its family from the legacy scores and lists what it lacks`() {
        val recordings = Recordings.load(File("src/test/resources/benchmark/recordings")).filter { it.variant == InterpreterMetrics.SCORING_VARIANT }
        assertThat(recordings).isNotEmpty()
        for (rec in recordings) {
            val doc = BenchmarkFixtures.load().docs.firstOrNull { it.first.key == rec.key } ?: continue
            assertThat(rec.asks.map { it.name }).contains("score:type")
            val replay = ZoneReplay(rec, profile)
            val first = doc.second.pages.firstOrNull()
            val result = runBlocking {
                com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline()
                    .run(doc.second.pages.map { it.blocks }, replay, rec.contextTokens, first?.let { it.width.toFloat() / it.height })
            }
            assertThat(result.diagnostics.modelUsed).isTrue()
            assertThat(result.documentType).isNotNull()
            // The family and the topics are scripted, never listed as a miss; the address labels and the summary never existed then.
            assertThat(replay.misses.none { it.contains("Is this document") || it.contains("Does this document concern") }).isTrue()
            assertThat(replay.misses.any { it.contains("FACTS") }).isTrue()
            replay.requireComplete() // a legacy recording is exempt
        }
    }

    @Test
    fun `the legacy bridge scripts topics from the best legacy type and gives a family no legacy type maps onto a score nothing accepts`() {
        val rec = Recordings.load(File("src/test/resources/benchmark/recordings")).first { it.key == "N10-kinderarzt-termin-1p" && it.variant == "zonesscoring" }
        val view = LegacyFamilyBridge.viewOf(rec)!!
        assertThat(view.bestLegacyId).isEqualTo("health")
        assertThat(view.topics).containsExactly("health")
        val questions = ExtractionSchema.DEFAULT.familiesFor(com.postsaimanager.core.domain.extraction.v2.DocDirection.INCOMING).map { "Is this document ${it.description}? Answer:" } +
            ExtractionSchema.DEFAULT.topics.map { "Does this document concern ${it.description}? Answer:" }
        val scores = LegacyFamilyBridge.answer(view, questions)!!
        assertThat(scores).hasSize(questions.size)
        assertThat(scores[ExtractionSchema.DEFAULT.familiesFor(com.postsaimanager.core.domain.extraction.v2.DocDirection.INCOMING).indexOfFirst { it.id == "certificate_id" }])
            .isEqualTo(LegacyFamilyBridge.NO_LEGACY_TYPE)
        assertThat(LegacyFamilyBridge.answer(view, listOf("Is «x» the sender? Answer:"))).isNull()
    }

    @Suppress("unused")
    private fun JsonObject.keys(): Set<String> = keys

    @Suppress("unused")
    private fun JsonArray.size(): Int = size
}
