package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.Letter
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.Oracle
import com.postsaimanager.core.domain.extraction.v2.OracleQuestionnaire
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.v2.QuestionnaireInterpreter
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * A recorded questionnaire run replays through the real interpreter and the real pipeline, and finds its
 * candidates again by what they are when their ids moved.
 */
class QuestionnaireReplayTest {

    private val letters = listOf(Letters.n1, Letters.n4, Letters.n6, Letters.invoice, Letters.english, Letters.arabic)

    /** What a device run would have written for [letter]: every question and answer, and the candidate table. */
    private fun record(letter: Letter, p: Prepared, idOf: (String) -> String = { it }): String {
        val session = FakePromptSession().apply { responder = OracleQuestionnaire.responder(letter, p) }
        val model = QuestionnaireInterpreter(FakeAiEngine(), session, contextTokens = 4096)
        runBlocking { ExtractionV2Pipeline().run(letter.pages, model, 4096) }
        return buildJsonObject {
            put("interpreter", "questionnaire")
            put("contextTokens", 4096)
            put("wallMs", 12_000)
            put("prefixMs", 3_000)
            put("prefixTokens", 1200)
            put(
                "asks",
                buildJsonArray {
                    for (a in model.transcript) {
                        add(
                            buildJsonObject {
                                put("name", a.name)
                                put("question", a.question)
                                put("answer", a.answer?.let { renumber(it, p, idOf) })
                                put("ms", 200)
                                put("answerTokens", 6)
                            },
                        )
                    }
                },
            )
            put(
                "candidates",
                buildJsonArray {
                    for (row in p.offered.rows) {
                        add(
                            buildJsonObject {
                                put("id", idOf(row.candidate.id))
                                put("kind", row.candidate.kind.name)
                                put("raw", row.candidate.raw)
                                put("normalized", row.candidate.normalized)
                                put("page", row.pages.first())
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun renumber(answer: String, p: Prepared, idOf: (String) -> String): String {
        // The same scanner the replay uses, run the other way: ids outside quotes.
        val map = p.offered.rows.associate { it.candidate.id to idOf(it.candidate.id) }
        return IdRemap.inAnswer(answer, map)
    }

    private fun ExtractionV2Result.shape() = listOf(
        documentType?.id,
        slots.map { (k, v) -> "${k.json}=${v.candidateId}/${v.normalized}/${v.role}" }.sorted(),
        parties.all.map { "${it.role}/${it.name}" }.sorted(),
        extras.map { "${it.label}/${it.value.normalized}" }.sorted(),
        freeText.title?.value,
    )

    private fun replay(letter: Letter, recording: Recording) =
        runBlocking { ExtractionV2Pipeline().run(letter.pages, QuestionnaireReplay(recording), recording.contextTokens) }

    @Test
    fun `a recorded run replays to the result the live run gave`() {
        for (letter in letters) {
            val p = Prepared(letter.pages)
            val live = runBlocking {
                val session = FakePromptSession().apply { responder = OracleQuestionnaire.responder(letter, p) }
                ExtractionV2Pipeline().run(letter.pages, QuestionnaireInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096)
            }
            val recording = Recordings.parse(letter.id, "questionnaire", record(letter, p))!!
            assertThat(recording.isQuestionnaire).isTrue()
            assertThat(recording.asks).isNotEmpty()
            assertThat(replay(letter, recording).shape()).isEqualTo(live.shape())
        }
    }

    @Test
    fun `ids that moved since the recording are found again by kind, value and page`() {
        for (letter in letters) {
            val p = Prepared(letter.pages)
            val stable = Recordings.parse(letter.id, "questionnaire", record(letter, p))!!
            // Every id in the recording is another one now: the letter, letter by number, shifted by fifty.
            val moved = Recordings.parse(
                letter.id, "questionnaire",
                record(letter, p) { id -> id.take(1) + (id.drop(1).toInt() + 50) },
            )!!
            assertThat(moved.candidates.map { it.id }).containsNoneIn(p.offered.rows.map { it.candidate.id })
            assertThat(replay(letter, moved).shape()).isEqualTo(replay(letter, stable).shape())
        }
    }

    @Test
    fun `the single call's recording is remapped the same way`() {
        for (letter in letters) {
            val p = Prepared(letter.pages)
            val oracleJson = Oracle.structured(letter, p).json
            fun old(id: String) = id.take(1) + (id.drop(1).toInt() + 50)
            val root = kotlinx.serialization.json.Json.parseToJsonElement(oracleJson) as JsonObject
            val ids = p.offered.rows.map { it.candidate.id }.toSet()
            fun rename(el: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (el) {
                is JsonObject -> JsonObject(el.mapValues { (_, v) -> rename(v) })
                is JsonArray -> JsonArray(el.map { rename(it) })
                is JsonPrimitive -> if (el.isString && el.content in ids) JsonPrimitive(old(el.content)) else el
                else -> el
            }
            val recorded = buildJsonObject {
                put("call1", rename(root).toString())
                put("call2", Oracle.text(letter))
                put(
                    "candidates",
                    buildJsonArray {
                        p.offered.rows.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", old(row.candidate.id))
                                    put("kind", row.candidate.kind.name)
                                    put("raw", row.candidate.raw)
                                    put("normalized", row.candidate.normalized)
                                    put("page", row.pages.first())
                                },
                            )
                        }
                    },
                )
            }.toString()
            val rec = Recordings.parse(letter.id, "single", recorded)!!
            val replayed = runBlocking { ExtractionV2Pipeline().run(letter.pages, ScriptedInterpreter(rec), 4096) }
            val reference = runBlocking {
                ExtractionV2Pipeline().run(
                    letter.pages,
                    com.postsaimanager.core.domain.extraction.v2.ScriptedInterpreter(oracleJson, Oracle.text(letter)),
                    4096,
                )
            }
            assertThat(replayed.shape()).isEqualTo(reference.shape())
        }
    }

    @Test
    fun `the remap leaves quoted text alone and swaps ids at once`() {
        val map = mapOf("D1" to "D2", "D2" to "D1")
        assertThat(IdRemap.inAnswer("D1 LETTER_DATE HIGH", map)).isEqualTo("D2 LETTER_DATE HIGH")
        assertThat(IdRemap.inAnswer("D1 D2 HIGH", map)).isEqualTo("D2 D1 HIGH")
        assertThat(IdRemap.inAnswer("M1 PERSON \"Familie D1\" HIGH", mapOf("M1" to "M3", "D1" to "D9")))
            .isEqualTo("M3 PERSON \"Familie D1\" HIGH")
        assertThat(IdRemap.inJson("{\"id\":\"D1\"},{\"id\":\"D2\"}", map)).isEqualTo("{\"id\":\"D2\"},{\"id\":\"D1\"}")
    }

    @Test
    fun `an unanswered question in the recording is a failed question, not an exception`() {
        val letter = Letters.n1
        val p = Prepared(letter.pages)
        val recording = Recordings.parse(letter.id, "questionnaire", record(letter, p))!!
        val empty = Recording(letter.key(), "questionnaire", "", null, 4096, asks = recording.asks.take(1), candidates = recording.candidates)
        val result = replay(letter, empty)
        // Only the type was recorded: the type stands, everything after it is missing, nothing throws.
        assertThat(result.diagnostics.modelCalled).isTrue()
    }

    private fun Letter.key() = id

    @Test
    fun `the interpreter's scoreboard row carries the cost of the recorded runs`() {
        val docs = BenchmarkFixtures.load().docs.filter { (m, _) -> m.key == Letters.n1.id }
        if (docs.isEmpty()) return
        val letter = Letters.n1
        val rec = Recordings.parse(letter.id, "questionnaire", record(letter, Prepared(letter.pages)))!!
        val score = InterpreterMetrics.score("questionnaire", docs, listOf(rec))!!
        assertThat(score.secondsPerDoc).isWithin(0.001).of(12.0)
        assertThat(score.prefixSeconds).isWithin(0.001).of(3.0)
        assertThat(score.answerTokensPerQuestion).isWithin(0.001).of(6.0)
        assertThat(score.questionsPerDoc).isGreaterThan(10.0)
        assertThat(InterpreterMetrics.section(listOf(score))).contains("questionnaire")
    }
}
