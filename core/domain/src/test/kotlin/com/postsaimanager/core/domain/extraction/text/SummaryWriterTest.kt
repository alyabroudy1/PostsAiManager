package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SummaryWriterTest {

    private val ocr = """
        Musterfirma GmbH
        Erika Mustermann
        Rechnung 2026-08-771204
        Der Betrag von 1.284,50 € ist bis zum 19.08.2026 zu überweisen.
    """.trimIndent()

    private val facts = SummaryFacts(
        familyId = "invoice_bill", sender = "Musterfirma GmbH", addressee = "Erika Mustermann",
        amount = "1.284,50 €", dueDate = "19.08.2026", subject = "Rechnung 2026-08-771204",
    )

    private val good = "\"Erika Mustermann soll Musterfirma GmbH 1.284,50 € zahlen, fällig am 19.08.2026.\""
    private val wrongNumber = "\"Erika Mustermann soll Musterfirma GmbH 1.284,99 € zahlen.\""
    private val copied = "\"Der Betrag von 1.284,50 € ist bis zum 19.08.2026 zu überweisen.\""

    private fun write(vararg answers: String?, language: String? = "de", f: SummaryFacts = facts): Pair<SummaryResult, FakePromptSession> {
        val session = FakePromptSession()
        var i = 0
        session.responder = { _, _ -> answers.getOrNull(i++) }
        val result = runBlocking {
            session.open(ocr)
            SummaryWriter(session).write(f, ocr, language)
        }
        return result to session
    }

    @Test
    fun `a good first answer is kept with one ask`() {
        val (r, s) = write(good)
        assertThat(r.origin).isEqualTo(SummaryOrigin.MODEL)
        assertThat(r.text).startsWith("Erika Mustermann soll")
        assertThat(r.code).isNull()
        assertThat(s.asks).hasSize(1)
    }

    @Test
    fun `an unverified number is asked again with the anti-copy instruction and the retry is kept`() {
        val (r, s) = write(wrongNumber, good)
        assertThat(r.origin).isEqualTo(SummaryOrigin.MODEL)
        assertThat(s.asks).hasSize(2)
        assertThat(s.asks[0].question).doesNotContain("Do not copy")
        assertThat(s.asks[1].question).contains("Do not copy")
    }

    @Test
    fun `two rejected answers fall back to the template, never a third ask`() {
        val (r, s) = write(wrongNumber, copied, good)
        assertThat(r.origin).isEqualTo(SummaryOrigin.TEMPLATE)
        assertThat(r.text).isNull()
        assertThat(r.code).isEqualTo("template")
        assertThat(r.args).containsExactly("invoice_bill", "Musterfirma GmbH", "Erika Mustermann", "1.284,50 €", "19.08.2026", "Rechnung 2026-08-771204").inOrder()
        assertThat(s.asks).hasSize(SummaryWriter.MAX_ASKS)
    }

    @Test
    fun `a copied line is rejected`() {
        val (r, _) = write(copied, copied)
        assertThat(r.origin).isEqualTo(SummaryOrigin.TEMPLATE)
    }

    @Test
    fun `an engine failure still gives a summary`() {
        val (r, s) = write(null, null)
        assertThat(r.origin).isEqualTo(SummaryOrigin.TEMPLATE)
        assertThat(s.asks.size).isAtMost(SummaryWriter.MAX_ASKS)
    }

    @Test
    fun `an answer that is not a quoted line counts as a failed attempt`() {
        val (r, _) = write("no quotes here", good)
        assertThat(r.origin).isEqualTo(SummaryOrigin.MODEL)
    }

    @Test
    fun `a summary always exists, even with no facts and a silent model`() {
        val (r, _) = write(null, null, f = SummaryFacts("free_form"))
        assertThat(r.origin).isEqualTo(SummaryOrigin.TEMPLATE)
        assertThat(r.code).isEqualTo("template")
        assertThat(r.args).containsExactly("free_form", "", "", "", "", "").inOrder()
    }

    @Test
    fun `the prompt lists the verified facts and names the language`() {
        val (_, s) = write(good, language = "ar")
        val q = s.asks.single().question
        assertThat(q).contains("- sender: Musterfirma GmbH")
        assertThat(q).contains("- amount: 1.284,50 €")
        assertThat(q).contains("at most 30 words")
        assertThat(q).contains("\"ar\"")
        assertThat(q).doesNotContain("- reference")
    }
}
