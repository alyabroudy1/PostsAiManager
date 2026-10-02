package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.Letter
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.OracleQuestionnaire
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.ZoneInterpreter
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/** A recorded zone run (generated or scored) replays through the real interpreter to the result the live run gave. */
class ZoneReplayTest {

    private val letters = listOf(Letters.n1, Letters.n4, Letters.n6, Letters.invoice, Letters.english)

    private fun recording(letter: Letter, p: Prepared, session: FakePromptSession, variant: String, names: List<String>, answers: List<String?>): String =
        buildJsonObject {
            put("interpreter", variant)
            put("contextTokens", 4096)
            put("wallMs", 9_000)
            put("prefixMs", 2_000)
            put("prefixTokens", 500)
            put(
                "asks",
                buildJsonArray {
                    names.indices.forEach { i ->
                        add(buildJsonObject { put("name", names[i]); put("question", session.recordedQuestions[i]); put("answer", answers[i]); put("ms", 100) })
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
        }.toString()

    private fun ExtractionV2Result.shape() = listOf(
        documentType?.id,
        slots.map { (k, v) -> "${k.json}=${v.candidateId}/${v.normalized}/${v.role}" }.sorted(),
        parties.all.map { "${it.role}/${it.name}" }.sorted(),
        freeText.title?.value,
    )

    /** The ask log of a fake session in the order asked, with the interpreter's own names for them. */
    private val FakePromptSession.recordedQuestions: List<String> get() = asks.map { it.question.removePrefix("\n\n") }

    @Test
    fun `a generated zone run replays to the same result`() {
        for (letter in letters) {
            val p = Prepared(letter.pages)
            val base = OracleQuestionnaire.responder(letter, p)
            val session = FakePromptSession().apply { responder = { q, g -> base(if (q.contains("QUESTION:")) "QUESTION:" + q.substringAfter("QUESTION:") else q, g) } }
            val interpreter = ZoneInterpreter(FakeAiEngine(), session, contextTokens = 4096)
            val live = runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096) }
            val json = recording(letter, p, session, "zones", interpreter.transcript.map { it.name }, interpreter.transcript.map { it.answer })
            val rec = Recordings.parse(letter.id, "zones", json)!!
            val replayed = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneReplay(rec, null), 4096) }
            assertThat(replayed.shape()).isEqualTo(live.shape())
        }
    }

    @Test
    fun `a scored zone run replays to the same result and other thresholds change only the decisions`() {
        val letter = Letters.invoice
        val p = Prepared(letter.pages)
        val session = FakePromptSession().apply {
            scorer = { c -> if (c.contains("«28.09.2026»") && c.contains("the date of the letter itself") || c.contains("Is this document an invoice or bill")) 4.0 else -4.0 }
            responder = { _, _ -> "\"text\"" }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096)
        val live = runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096) }
        // The scored batches, as the device runner records them: name, the questions joined, the scores.
        val batches = session.scored
        val names = interpreter.transcript.map { it.name }
        val json = buildJsonObject {
            put("interpreter", "zonesscoring")
            put("contextTokens", 4096)
            put("asks", buildJsonArray {
                interpreter.transcript.forEachIndexed { i, a ->
                    add(buildJsonObject { put("name", names[i]); put("question", a.question); put("answer", a.answer); put("ms", 10) })
                }
            })
            put("candidates", buildJsonArray {
                for (row in p.offered.rows) add(buildJsonObject { put("id", row.candidate.id); put("kind", row.candidate.kind.name); put("raw", row.candidate.raw); put("normalized", row.candidate.normalized); put("page", row.pages.first()) })
            })
        }.toString()
        assertThat(batches).isNotEmpty()
        val rec = Recordings.parse(letter.id, "zonesscoring", json)!!
        val replayed = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneReplay(rec, ScoringProfile()), 4096) }
        assertThat(replayed.shape()).isEqualTo(live.shape())
        // A threshold above every recorded score turns every slot into none, without a new model run.
        val strict = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneReplay(rec, ScoringProfile(defaultThreshold = 100.0)), 4096) }
        assertThat(strict.slots).isEmpty()
    }

    @Test
    fun `the first candidate share counts the choices that took the first candidate shown`() {
        val asks = listOf(
            RecordedAsk("addressee", "ZONE address-field.\nCANDIDATES IN THESE ZONES\nM3: A\nM4: B\nQUESTION: x", "M3 PERSON NONE \"A\" HIGH"),
            RecordedAsk("sender", "ZONE letterhead.\nCANDIDATES IN THESE ZONES\nM1: A\nM2: B\nQUESTION: x", "M2 COMPANY \"B\" HIGH"),
            RecordedAsk("slot:total", "ZONE body.\nCANDIDATES IN THESE ZONES\nA1: 1\nQUESTION: x", "A1 GROSS HIGH"),
            RecordedAsk("contact", "ZONE letterhead.\nCANDIDATES IN THESE ZONES\nM1: A\nM2: B\nQUESTION: x", "NONE"),
            RecordedAsk("score:slot:total", "q", "1.0,3.0,-2.0"),
            RecordedAsk("score:type", "q", "5.0,1.0"),
        )
        val rec = Recording("k", "zones", "", null, 4096, asks = asks)
        val report = FirstCandidateShare.of(listOf(rec))
        // Counted: addressee (first of 2), sender (second of 2), the scored total batch (second of 3). Not counted: one candidate only, NONE, the type.
        assertThat(report.overall.cases).isEqualTo(3)
        assertThat(report.overall.first).isEqualTo(1)
        assertThat(report.byZone.keys).containsExactly("address-field", "letterhead")
        assertThat(report.overall.expected).isWithin(0.001).of((0.5 + 0.5 + 1.0 / 3) / 3)
    }
}
