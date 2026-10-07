package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The specific name of a document: a line in its language, kept only when it claims nothing the letter does not give. */
class DocumentNameTest {

    private val letter = """
        Kfz-Versicherung Beispielstadt
        Beitragsrechnung 2027
        Vertragsnummer WV-2291
        Zahnarztpraxis Dr. Weber, Termin am 14.10.2026 um 09:30 Uhr
        Gesamtbetrag 612,40 EUR
    """.trimIndent()

    private val verifier = DocumentNameVerifier()

    @Test
    fun `a name whose numbers and names are printed in the letter is kept as it is`() {
        assertThat(verifier.verify("Kfz-Versicherung – Beitragsrechnung 2027", letter)).isEqualTo("Kfz-Versicherung – Beitragsrechnung 2027")
        assertThat(verifier.verify("Terminbestätigung Zahnarzt", letter)).isEqualTo("Terminbestätigung Zahnarzt")
        assertThat(verifier.verify("Terminbestätigung Zahnarzt Dr. Weber 14.10.2026", letter)).isNotNull()
    }

    @Test
    fun `the first word, where a name puts the kind of document, is not checked, and nor is a word in lower case`() {
        // "Bescheinigung" is not in the letter, but it is the first word; "von" is no name.
        assertThat(verifier.verify("Bescheinigung von Dr. Weber", letter)).isEqualTo("Bescheinigung von Dr. Weber")
        assertThat(verifier.verify("termin beim Zahnarzt", letter)).isNotNull()
    }

    @Test
    fun `a name with a number the letter does not print is dropped, a changed digit included`() {
        assertThat(verifier.verify("Kfz-Versicherung – Beitragsrechnung 2031", letter)).isNull()
        assertThat(verifier.verify("Rechnung 612,41 EUR", letter)).isNull()
        assertThat(verifier.verify("Rechnung 612,40 EUR", letter)).isNotNull()
    }

    @Test
    fun `a name with a proper noun the letter does not print is dropped`() {
        assertThat(verifier.verify("Rechnung Stadtwerke Hamburg", letter)).isNull()
        assertThat(verifier.verify("Termin Zahnarztpraxis Schmidt", letter)).isNull()
        // An inflected or compounded form of a printed word is printed enough.
        assertThat(verifier.verify("Rechnung Versicherungsbeitrag", letter)).isNotNull()
    }

    @Test
    fun `an empty name, a name with a line break and a name over the limit are dropped`() {
        assertThat(verifier.verify("", letter)).isNull()
        assertThat(verifier.verify("   ", letter)).isNull()
        assertThat(verifier.verify("Beitragsrechnung\nKfz-Versicherung", letter)).isNull()
        assertThat(verifier.verify("Beitragsrechnung ".repeat(4), letter)).isNull()
        assertThat(verifier.verify("Beitragsrechnung", "")).isNull()
    }

    @Test
    fun `quotes around the name are not part of it, and a script with no capitals is checked by its numbers`() {
        assertThat(verifier.verify("\"Kfz-Versicherung 2027\"", letter)).isEqualTo("Kfz-Versicherung 2027")
        val arabic = "فاتورة الكهرباء رقم ٤٥٦ بتاريخ 2026"
        assertThat(verifier.verify("فاتورة الكهرباء ٤٥٦", arabic)).isEqualTo("فاتورة الكهرباء ٤٥٦")
        assertThat(verifier.verify("فاتورة الكهرباء ٤٥٧", arabic)).isNull()
    }

    @Test
    fun `the grammar allows one line of at most 60 characters and nothing else`() {
        val grammar = DocumentNameFormat.grammar()
        assertThat(grammar).startsWith("root ::= nchar")
        // The length is in the grammar: one optional nest per character after the first, and no character class admits a line break.
        assertThat(grammar.count { it == '(' }).isEqualTo(DocumentNameFormat.MAX_CHARS - 1)
        assertThat(grammar).contains("[^\"\\\\\\n\\r]")
        assertThat(DocumentNameFormat.MAX_CHARS).isEqualTo(60)
    }

    // ── the writer ──

    private fun session(answer: String?) = FakePromptSession().apply {
        runBlocking { open("the letter") }
        responder = { _, _ -> answer }
    }

    @Test
    fun `the writer shows what was read and the category, asks in the language of the document, and keeps a grounded name`() = runTest {
        val session = session("Kfz-Versicherung – Beitragsrechnung 2027")
        val read = ReadFacts.block(mapOf("sender" to "Kfz-Versicherung Beispielstadt"), emptyList())
        val name = DocumentNameWriter(session).write(read, "The user says this document is a bill or an invoice.", letter, "de")
        assertThat(name).isEqualTo("Kfz-Versicherung – Beitragsrechnung 2027")
        val ask = session.asks.single()
        assertThat(ask.question).contains("- sender: Kfz-Versicherung Beispielstadt")
        assertThat(ask.question).contains("The user says this document is a bill or an invoice.")
        assertThat(ask.question).contains("language with the code \"de\"")
        assertThat(ask.question).contains("at most 60 characters")
        assertThat(ask.grammar).isEqualTo(DocumentNameFormat.grammar())
        assertThat(ask.maxTokens).isEqualTo(DocumentNameFormat.MAX_TOKENS)
    }

    @Test
    fun `the writer drops a name that is not grounded and one the engine failed`() = runTest {
        assertThat(DocumentNameWriter(session("Beitragsrechnung 2031")).write("", "", letter, "de")).isNull()
        assertThat(DocumentNameWriter(session(null)).write("", "", letter, null)).isNull()
        val noLanguage = session("Beitragsrechnung")
        DocumentNameWriter(noLanguage).write("", "", letter, null)
        assertThat(noLanguage.asks.single().question).contains("document's own language")
    }

    @Test
    fun `without an open session there is no name`() = runTest {
        assertThat(DocumentNameWriter(FakePromptSession()).write("", "", letter, "de")).isNull()
    }
}
