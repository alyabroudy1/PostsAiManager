package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.OracleQuestionnaire
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.v2.QuestionnaireInterpreter
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * A received letter (the invoice-2p benchmark letter, "Rechnung" from a company to a private person) is never read as "a letter you
 * sent", whichever strategy reads it and whether or not the user has a "Me" profile: the direction is incoming, the strategies that
 * let the model name the type offer only the families an incoming document can be, and the scoring strategy only scores those.
 */
class ReceivedLetterDirectionTest {

    private val letter = Letters.invoice
    private val outgoing = ExtractionSchema.OUTGOING_LETTER

    @Test
    fun `the schema narrowed to incoming offers neither the outgoing letter nor the payment proof but keeps the abstain family`() {
        val ids = ExtractionSchema.DEFAULT.forDirection(DocDirection.INCOMING).families.map { it.id }
        assertThat(ids).doesNotContain(outgoing.id)
        assertThat(ids).doesNotContain(ExtractionSchema.PAYMENT_PROOF.id)
        assertThat(ids).contains(ExtractionSchema.INVOICE_BILL.id)
        assertThat(ids).contains(ExtractionSchema.FREE_FORM.id)
        assertThat(ExtractionSchema.DEFAULT.forDirection(DocDirection.OUTGOING).families.map { it.id }).contains(outgoing.id)
    }

    @Test
    fun `the zone strategy never offers the outgoing letter for the invoice and reads an invoice or free form`() {
        val p = Prepared(letter.pages)
        val base = OracleQuestionnaire.responder(letter, p)
        val session = FakePromptSession().apply { responder = { q, g -> base(if (q.contains("QUESTION:")) "QUESTION:" + q.substringAfter("QUESTION:") else q, g) } }
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096) }
        assertThat(session.asks.none { it.question.contains(outgoing.id) }).isTrue()
        assertThat(result.documentType?.id).isIn(listOf(ExtractionSchema.INVOICE_BILL.id, ExtractionSchema.FREE_FORM.id))
    }

    @Test
    fun `the questionnaire strategy never offers the outgoing letter for the invoice`() {
        val p = Prepared(letter.pages)
        val session = FakePromptSession().apply { responder = OracleQuestionnaire.responder(letter, p) }
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, QuestionnaireInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096) }
        assertThat(session.asks.none { it.question.contains(outgoing.id) }).isTrue()
        assertThat(result.documentType?.id).isIn(listOf(ExtractionSchema.INVOICE_BILL.id, ExtractionSchema.FREE_FORM.id))
    }

    @Test
    fun `the single-call strategy offers no outgoing letter in its prompt or grammar`() {
        val engine = FakeAiEngine()
        runBlocking { ExtractionV2Pipeline().run(letter.pages, ModelDocumentInterpreter(engine, contextTokens = 4096), 4096) }
        assertThat(engine.generateRequests).isNotEmpty()
        assertThat(engine.generateRequests.none { r -> r.prompt.contains(outgoing.id) || (r.grammar ?: "").contains(outgoing.id) }).isTrue()
    }

    @Test
    fun `the scoring strategy does not score the outgoing letter even when the model would say yes to everything`() {
        val session = FakePromptSession().apply {
            scorer = { 5.0 }
            responder = { q, _ -> if (q.contains("BCP-47")) "de" else "\"text\"" }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096)
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096) }
        val batch = interpreter.transcript.single { it.name == "score:family" }
        assertThat(batch.question).doesNotContain(outgoing.description)
        assertThat(result.documentType?.id).isNotEqualTo(outgoing.id)
        assertThat(ExtractionSchema.DEFAULT.familiesFor(DocDirection.INCOMING)).doesNotContain(outgoing)
    }
}
