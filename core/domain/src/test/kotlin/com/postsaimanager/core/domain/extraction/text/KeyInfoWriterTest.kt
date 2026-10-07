package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class KeyInfoWriterTest {

    private val ocr = """
        Stadtwerke Beispielstadt
        Rechnung R-2026-0815 vom 28.09.2026
        Zählernummer: 1EMH0012345678
        Vertragsbeginn: 01.01.2027
        Der Betrag von 1.284,50 € ist bis zum 15.10.2026 zu überweisen.
    """.trimIndent()

    private val read = listOf("Sender" to "Stadtwerke Beispielstadt", "Amount" to "1.284,50 €", "Invoice number" to "R-2026-0815")

    private fun write(answer: String?, languageCode: String? = "de"): Pair<List<com.postsaimanager.core.domain.extraction.v2.RawExtra>, FakePromptSession> {
        val session = FakePromptSession().apply { responder = { _, _ -> answer } }
        val result = runBlocking {
            session.open(ocr)
            KeyInfoWriter(session).write(read, ocr, languageCode)
        }
        return result to session
    }

    @Test
    fun `grounded facts are kept as open extras holding the quoted value, the label as written`() {
        val (extras, _) = write("Zählernummer: 1EMH0012345678\nVertragsbeginn: 01.01.2027")

        assertThat(extras.map { it.label to it.value }).containsExactly("Zählernummer" to "1EMH0012345678", "Vertragsbeginn" to "01.01.2027").inOrder()
        // A quote, never a candidate: the verifier checks it against the letter again.
        assertThat(extras.all { it.id == StructuredGrammar.NONE }).isTrue()
    }

    @Test
    fun `ungrounded facts and the read fields' duplicates are dropped`() {
        val (extras, _) = write("Zählernummer: 1EMH0012345678\nOrt: Beispieldorf\nGesamt: 1.284,50 €\nRechnung: R-2026-0815\nBetrag: 1.284,99 €")

        assertThat(extras.map { it.label }).containsExactly("Zählernummer")
    }

    @Test
    fun `NONE, an unreadable answer and a failed ask give no key information and never fail`() {
        assertThat(write("NONE").first).isEmpty()
        assertThat(write("irgendein Text ohne Struktur").first).isEmpty()
        assertThat(write(null).first).isEmpty()
    }

    @Test
    fun `one generation with the grammar and the token budget, showing the read fields and the language`() {
        val (_, session) = write("NONE", languageCode = "ar")

        val ask = session.asks.single()
        assertThat(ask.grammar).isEqualTo(KeyInfoFormat.grammar())
        assertThat(ask.maxTokens).isEqualTo(KeyInfoFormat.MAX_TOKENS)
        assertThat(ask.question).contains("READ FIELDS")
        read.forEach { (label, value) -> assertThat(ask.question).contains("- $label: $value") }
        assertThat(ask.question).contains("not among the read fields")
        assertThat(ask.question).contains("\"ar\"")
        // The letter was the prefix: nothing was decoded again.
        assertThat(session.prefixDecodes).isEqualTo(1)
    }

    @Test
    fun `without a language the labels are asked in the document's own, and with no read field the prompt says none`() {
        val session = FakePromptSession().apply { responder = { _, _ -> "NONE" } }
        runBlocking {
            session.open(ocr)
            KeyInfoWriter(session).write(emptyList(), ocr, null)
        }
        val q = session.asks.single().question
        assertThat(q).contains("document's own language")
        assertThat(q).contains("- none")
    }
}
