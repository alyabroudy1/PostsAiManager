package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.EventReading
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeActiveModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The "Questions" reading end to end with a scripted reader: a real letter's layout and candidates, the labelled answer scripted, and
 * everything after it (the mapper, the result, the adapter) real. What comes out is the understanding the app already stores.
 */
class QuestionReadingUseCaseTest {

    private val provider = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM, supportsImages = true)
    private val pages = Letters.n1.pages

    private fun read(text: String): GemmaReadingOutcome {
        val reader = ScriptedReader { GemmaReaderOutcome.Stated(text, "prompt", 1_500L, true, listOf("qa asked")) }
        return runBlocking { GemmaReadingUseCase(reader, EntityAnnotator.NONE, provider)(pages, listOf("/p1.png"), pageAspect = 0.707f) }
    }

    private val answer = """
        SENDER: Nordlicht Mobilfunk GmbH | company
        RECIPIENT: Erika Mustermann | person
        CONTACT: none
        ASKS: yes — pay — 09.10.2026
        DATES: 25.09.2026 — LETTER_DATE; 09.10.2026 — DUE_DATE
        AMOUNTS: 64,98 EUR — TOTAL_DUE
        REFERENCES: 2026-08-771204 — invoice_no
        TYPE: bill
        TITLE: Mahnung Mobilfunkrechnung
        LANGUAGE: de
        PAID: to_pay
        EVENT: payment_reminder
    """.trimIndent()

    @Test
    @DisplayName("the labelled answer comes out as the same understanding the JSON reader gives: family, parties, slots with meanings, the action, the event")
    fun `the same output types`() {
        val u = (read(answer) as GemmaReadingOutcome.Read).understanding

        assertThat(u.documentType).isEqualTo("invoice_bill")
        assertThat(u.modelUsed).isTrue()
        assertThat(u.entities.first { it.role == EntityRole.SENDER }.name).contains("Nordlicht Mobilfunk GmbH")
        assertThat(u.entities.first { it.role == EntityRole.RECIPIENT }.name).contains("Erika Mustermann")
        assertThat(u.facts.first { it.label == "Amount" }.provenance?.role).isEqualTo("meaning:TOTAL_DUE")
        assertThat(u.facts.first { it.label == "Document Date" }.value).contains("25.09.2026")
        assertThat(u.facts.first { it.label == "Invoice Number" }.value).contains("2026-08-771204")
        assertThat(u.actionItems!!.map { it.kind }).containsExactly("pay")
        assertThat(u.actionItems!!.single().bindings["amount"]).isEqualTo("total")
        assertThat(u.event).isEqualTo(EventReading("payment_reminder"))
    }

    @Test
    @DisplayName("a value the model wrote that the letter does not hold is stored, not dropped: a sender and a date nobody printed")
    fun `stored as the model gave them`() {
        val u = (read(answer.replace("Nordlicht Mobilfunk GmbH", "Fantasie Telekom AG").replace("09.10.2026 — DUE_DATE", "31.12.2027 — DUE_DATE")) as GemmaReadingOutcome.Read).understanding

        assertThat(u.entities.first { it.role == EntityRole.SENDER }.name).contains("Fantasie Telekom AG")
        assertThat(u.facts.any { it.value.contains("31.12.2027") }).isTrue()
    }

    @Test
    @DisplayName("an answer with none of the labels is no reading: the usual reader runs")
    fun `an unusable answer`() {
        assertThat(read("I am sorry, I cannot read this letter.")).isInstanceOf(GemmaReadingOutcome.Unavailable::class.java)
    }
}
