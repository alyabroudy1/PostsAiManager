package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The short constrained ask of the scoring reading that names nothing but the language. */
class LanguageAndExtrasTest {

    private val language = GbnfMatcher(QuestionGrammars.language())

    @Test
    fun `the language grammar accepts a bare code, with or without a region`() {
        for (code in listOf("de", "ar", "pt-BR", "fil", "zh-Hant")) assertThat(language.accepts(code)).isTrue()
    }

    @Test
    fun `the language grammar refuses anything that is not a code`() {
        for (text in listOf("", "D", "de;", "de es", "\"de\"", "DE", "deutsch1")) assertThat(language.accepts(text)).isFalse()
    }

    @Test
    fun `the grammar has no counted repetition`() {
        assertThat(Regex("\\{\\d*,?\\d*\\}").containsMatchIn(QuestionGrammars.language())).isFalse()
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
    fun `the name of an extra is one quoted line`() {
        val line = GbnfMatcher(QuestionGrammars.line())
        assertThat(line.accepts("\"Rechnung\"")).isTrue()
        assertThat(line.accepts("Rechnung")).isFalse()
        assertThat(AnswerReader.line("\"Kunden-Nr.\"")).isEqualTo("Kunden-Nr.")
    }
}
