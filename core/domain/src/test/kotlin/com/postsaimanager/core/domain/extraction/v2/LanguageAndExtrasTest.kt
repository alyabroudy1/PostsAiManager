package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The two short constrained asks of the scoring reading: the letter's language, and the name of an extra. */
class LanguageAndExtrasTest {

    private val language = GbnfMatcher(QuestionGrammars.language())
    private val naming = GbnfMatcher(QuestionGrammars.labelAndKey())

    @Test
    fun `the language grammar accepts a bare code, with or without a region`() {
        for (code in listOf("de", "ar", "pt-BR", "fil", "zh-Hant")) assertThat(language.accepts(code)).isTrue()
    }

    @Test
    fun `the language grammar refuses anything that is not a code`() {
        for (text in listOf("", "D", "de;", "de es", "\"de\"", "DE", "deutsch1")) assertThat(language.accepts(text)).isFalse()
    }

    @Test
    fun `the naming grammar takes the printed words in quotes and a key`() {
        assertThat(naming.accepts("\"Rechnung\" invoice_number")).isTrue()
        assertThat(naming.accepts("\"Kunden-Nr.\" customer_number")).isTrue()
        assertThat(naming.accepts("Rechnung invoice_number")).isFalse()
        assertThat(naming.accepts("\"Rechnung\"")).isFalse()
        assertThat(naming.accepts("\"Rechnung\" Invoice")).isFalse()
    }

    @Test
    fun `the grammars have no counted repetition`() {
        for (grammar in listOf(QuestionGrammars.language(), QuestionGrammars.labelAndKey())) {
            assertThat(Regex("\\{\\d*,?\\d*\\}").containsMatchIn(grammar)).isFalse()
        }
    }

    @Test
    fun `the reader takes a bare language code and nothing else`() {
        assertThat(AnswerReader.language("de")).isEqualTo("de")
        assertThat(AnswerReader.language(" pt-BR\n")).isEqualTo("pt-br")
        assertThat(AnswerReader.language("de; en")).isNull()
        assertThat(AnswerReader.language("\"de\"")).isNull()
        assertThat(AnswerReader.language("")).isNull()
        assertThat(AnswerReader.language("german")).isNull()
    }

    @Test
    fun `the reader takes the words and the key`() {
        assertThat(AnswerReader.labelAndKey("\"Rechnung\" invoice_number")).isEqualTo("Rechnung" to "invoice_number")
        assertThat(AnswerReader.labelAndKey("\"  \" invoice_number")).isNull()
        assertThat(AnswerReader.labelAndKey("\"Rechnung\"")).isNull()
        assertThat(AnswerReader.labelAndKey("invoice_number")).isNull()
    }
}
