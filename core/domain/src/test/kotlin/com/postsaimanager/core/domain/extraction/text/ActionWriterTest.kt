package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ActionWriterTest {

    private val ocr = """
        Musterfirma GmbH
        Erika Mustermann
        Rechnung 2026-08-771204
        Der Betrag von 1.284,50 € ist bis zum 19.08.2026 zu überweisen.
        Einspruch ist bis zum 02.09.2026 möglich.
        IBAN DE02 1203 0000 0000 2020 51
    """.trimIndent()

    private val facts = listOf(
        "sender" to "Musterfirma GmbH", "addressed_to" to "Erika Mustermann", "amount" to "1.284,50 €", "due_date" to "19.08.2026",
        "Iban" to "DE02 1203 0000 0000 2020 51",
    )
    private val hint = ExtractionSchema.INVOICE_BILL.hint

    private val payText = "Bitte überweise den Rechnungsbetrag von 1.284,50 € rechtzeitig vor dem 19.08.2026 an die Musterfirma GmbH."
    private val objectText = "Lege bis zum 02.09.2026 schriftlich Einspruch gegen den Bescheid ein."
    private val pay = "\"$payText\""
    private val object_ = "\"$objectText\""
    private val wrongAmount = "\"Bitte überweise den Rechnungsbetrag von 1.284,99 € rechtzeitig vor dem 19.08.2026 an die Musterfirma GmbH.\""
    private val wrongDate = "\"Bitte überweise den Rechnungsbetrag von 1.284,50 € rechtzeitig vor dem 20.08.2026 an die Musterfirma GmbH.\""
    private val inventedName = "\"Bitte überweise den Rechnungsbetrag von 1.284,50 € rechtzeitig an Hans Meier.\""
    private val copied = "\"Der Betrag von 1.284,50 € ist bis zum 19.08.2026 zu überweisen.\""

    private fun write(vararg answers: String?, language: String? = "de"): Pair<List<String>?, FakePromptSession> {
        val session = FakePromptSession()
        var i = 0
        session.responder = { _, _ -> answers.getOrNull(i++) }
        val result = runBlocking {
            session.open(ocr)
            ActionWriter(session).write(facts, hint, ocr, language)
        }
        return result to session
    }

    @Test
    fun `verified lines are kept in order, from one ask`() {
        val (lines, s) = write("$pay $object_")
        assertThat(lines).containsExactly(payText, objectText).inOrder()
        assertThat(s.asks).hasSize(1)
    }

    @Test
    fun `a line with a number the letter never printed is dropped, the verified one stays`() {
        val (lines, _) = write("$wrongAmount $pay $wrongDate")
        assertThat(lines).containsExactly(payText)
    }

    @Test
    fun `a line with an invented name is dropped`() {
        val (lines, _) = write("$inventedName $pay")
        assertThat(lines).containsExactly(payText)
    }

    @Test
    fun `a line that copies one line of the letter is dropped`() {
        val (lines, _) = write("$copied $object_")
        assertThat(lines).containsExactly(objectText)
    }

    @Test
    fun `at most three lines are kept and a repeated line only once`() {
        val (lines, _) = write("$pay $pay $object_ $pay")
        assertThat(lines).containsExactly(payText, objectText).inOrder()
    }

    @Test
    fun `NONE means the letter asks nothing, with one ask and an empty list`() {
        val (lines, s) = write("NONE")
        assertThat(lines).isEmpty()
        assertThat(s.asks).hasSize(1)
    }

    @Test
    fun `when every line is rejected it asks once more not to copy, then settles on none`() {
        val (lines, s) = write(wrongAmount, wrongDate)
        assertThat(lines).isEmpty()
        assertThat(s.asks).hasSize(ActionWriter.MAX_ASKS)
        assertThat(s.asks[0].question).doesNotContain("Do not copy")
        assertThat(s.asks[1].question).contains("Do not copy")
    }

    @Test
    fun `the second ask is the retry that is kept`() {
        val (lines, _) = write(wrongAmount, pay)
        assertThat(lines).containsExactly(payText)
    }

    @Test
    fun `an engine that fails every ask gives null, so a stored list stays`() {
        val (lines, s) = write(null, null)
        assertThat(lines).isNull()
        assertThat(s.asks.size).isAtMost(ActionWriter.MAX_ASKS)
    }

    @Test
    fun `the prompt lists the facts, carries the family's hint and names the language and the grammar`() {
        val (_, s) = write(pay, language = "ar")
        val q = s.asks.single().question
        assertThat(q).contains("- amount: 1.284,50 €")
        assertThat(q).contains("- Iban: DE02 1203 0000 0000 2020 51")
        assertThat(q).contains(hint)
        assertThat(q).contains("\"ar\"")
        assertThat(q).contains("answer NONE")
        assertThat(s.asks.single().grammar).contains("NONE")
        assertThat(s.asks.single().maxTokens).isEqualTo(ActionWriter.ACTION_TOKENS)
    }

    @Test
    fun `a family with no hint still asks, with no guidance sentence`() {
        val session = FakePromptSession().apply { responder = { _, _ -> "NONE" } }
        runBlocking {
            session.open(ocr)
            ActionWriter(session).write(facts, null, ocr, null)
        }
        assertThat(session.asks.single().question).contains("letter's own language")
    }
}
