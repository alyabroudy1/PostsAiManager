package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.OracleQuestionnaire
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.QuestionnaireInterpreter
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The zone interpreter through the real pipeline with a fake session that answers like a correct model. */
class ZoneInterpreterTest {

    private fun ExtractionV2Result.shape() = listOf(
        documentType?.id,
        slots.map { (k, v) -> "${k.json}=${v.normalized}" }.sorted(),
        parties.all.map { "${it.role}/${it.name}" }.sorted(),
    )

    /** The questionnaire oracle, matched on the question only: a zone block may mention words (a hint) the oracle keys on. */
    private fun oracle(letter: com.postsaimanager.core.domain.extraction.v2.Letter, p: Prepared): (String, String) -> String? {
        val base = OracleQuestionnaire.responder(letter, p)
        return { q, g -> base(if (q.contains("QUESTION:")) "QUESTION:" + q.substringAfter("QUESTION:") else q, g) }
    }

    private fun run(letterIndex: Int): Triple<ExtractionV2Result, ExtractionV2Result, FakePromptSession> {
        val letter = Letters.all[letterIndex]
        val p = Prepared(letter.pages)
        val zoneSession = FakePromptSession().apply { responder = oracle(letter, p) }
        val zones = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneInterpreter(FakeAiEngine(), zoneSession, contextTokens = 4096), 4096) }
        val questionnaire = runBlocking {
            val s = FakePromptSession().apply { responder = OracleQuestionnaire.responder(letter, p) }
            ExtractionV2Pipeline().run(letter.pages, QuestionnaireInterpreter(FakeAiEngine(), s, contextTokens = 4096), 4096)
        }
        return Triple(zones, questionnaire, zoneSession)
    }

    @Test
    fun `an oracle that answers correctly is read the same by zones as by the questionnaire`() {
        val report = StringBuilder()
        for ((i, letter) in Letters.all.withIndex()) {
            val (zones, questionnaire, _) = run(i)
            if (zones.shape() != questionnaire.shape()) {
                report.appendLine("${letter.id}\n zones=${zones.shape()}\n quest=${questionnaire.shape()}")
            }
        }
        // A value the oracle picked that sits in a zone the template does not place its slot on is still asked
        // (the placement is a prior), so the two readings agree letter by letter.
        assertWithMessage(report.toString()).that(report.lines().count { it.isNotBlank() && !it.startsWith(" ") }).isAtMost(MAX_DIFFERING)
    }

    @Test
    fun `each question sees only its zone's candidates and the body is read as its own prefix`() {
        val (_, _, session) = run(Letters.all.indexOf(Letters.n1))
        // Two sessions: the header (instructions only), then the body (re-opened with the body text as the prefix).
        assertThat(session.opens).hasSize(2)
        assertThat(session.opens[1]).contains("[body]")
        assertThat(session.opens[0]).doesNotContain("[body]")
        val addressee = session.asks.first { it.question.contains("To whom is the letter addressed") }
        assertThat(addressee.question).contains("ZONE address-field")
        assertThat(addressee.question).contains("This block is usually the recipient's address field")
        val candidateLines = addressee.question.substringAfter("CANDIDATES IN THESE ZONES").substringBefore("QUESTION:")
        // The address field of N1 holds a name and a street; the sender's letterhead is not offered here.
        assertThat(candidateLines).doesNotContain("Nordlicht")
        // The body questions never carry the body text again.
        val total = session.asks.first { it.question.contains("main amount of this document") }
        assertThat(total.question).contains("(the text of this zone is in the letter above)")
        // Every question started from its own prefix (rolled back after each answer).
        assertThat(session.stateAtAsk.toSet()).hasSize(2)
    }

    @Test
    fun `the header answers are summarised in front of the body`() {
        val (_, _, session) = run(Letters.all.indexOf(Letters.n1))
        assertThat(session.opens[1]).contains("ESTABLISHED FROM THE HEADER")
        assertThat(session.opens[1]).contains("sender: ")
        assertThat(session.opens[1]).contains("addressee: ")
    }

    @Test
    fun `an addressee written from outside the address field is kept, noted and capped`() {
        val letter = Letters.n1
        val p = Prepared(letter.pages)
        val base = oracle(letter, p)
        val sender = p.offered.rows.map { it.candidate }.first { it.kind.name == "NAME" && it.raw.contains("Nordlicht") }
        // Some other name of the letter that is not in the address field (and not the sender: that is a code invariant).
        val outside = p.offered.rows.map { it.candidate }.first {
            it.kind.name == "NAME" && it.id != sender.id && it.attrs["zone"] != "ADDRESS_FIELD"
        }
        val session = FakePromptSession().apply {
            // The model names it as the addressee (an id from outside the address field).
            responder = { q, g -> if (q.contains("To whom is the letter addressed")) "${outside.id} PERSON NONE \"${outside.raw}\" HIGH" else base(q, g) }
        }
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096) }
        val addressee = result.parties.all.firstOrNull { it.role == PartyRole.ADDRESSEE }
        assertThat(addressee).isNotNull()
        assertThat(addressee!!.value.confidence).isAtMost(0.5f)
        assertThat(addressee.value.blocked).isTrue()
        assertThat(result.diagnostics.conflicts.joinToString()).contains("is not in the address-field zone")
    }

    @Test
    fun `the generic template reads the whole letter as one body`() {
        fun block(text: String, top: Float) = com.postsaimanager.core.model.OcrBlock(
            text = text, bounds = com.postsaimanager.core.model.TextBounds(0.1f, top, 0.6f, top + 0.02f), confidence = 1f, language = null,
        )
        val pages = listOf(
            listOf(
                block("Beispielbank eG, Marktplatz 2, 12345 Beispielstadt", 0.50f),
                block("Ihr Kontostand am 03.09.2026 beträgt 1.234,56 €.", 0.55f),
            ),
        )
        val session = FakePromptSession().apply { responder = { q, _ -> if (q.contains("What kind of document")) "info_no_action de HIGH" else "NONE" } }
        val interpreter = ZoneInterpreter(FakeAiEngine(), session, contextTokens = 4096)
        runBlocking { ExtractionV2Pipeline().run(pages, interpreter, 4096) }
        assertThat(interpreter.templateId).isEqualTo("GENERIC")
        // One session only: with no header zones there is nothing to ask one at a time.
        assertThat(session.opens).hasSize(1)
    }

    @Test
    fun `a right to left letter is matched to the mirrored template`() {
        val letter = Letters.arabic
        val session = FakePromptSession().apply { responder = oracle(letter, Prepared(letter.pages)) }
        val interpreter = ZoneInterpreter(FakeAiEngine(), session, contextTokens = 4096)
        runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096) }
        assertThat(interpreter.templateId).isEqualTo("RTL_DIN")
        assertThat(session.opens[0]).contains("LAYOUT: RTL_DIN")
    }

    private companion object {
        const val MAX_DIFFERING = 0
    }
}
