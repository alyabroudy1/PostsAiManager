package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The one answer that holds the letter's language and its extras: its grammar, its reader and the bound of its list. */
class LanguageAndExtrasTest {

    private val matcher = GbnfMatcher(QuestionGrammars.languageAndExtras(listOf("N4", "D2")))

    private fun entry(id: String = "N4") = "$id \"Zählernummer\" meter_number \"\" MEDIUM"

    @Test
    fun `the grammar accepts a bare language code, with or without a region`() {
        assertThat(matcher.accepts("de")).isTrue()
        assertThat(matcher.accepts("ar")).isTrue()
        assertThat(matcher.accepts("pt-BR")).isTrue()
        assertThat(matcher.accepts("fil")).isTrue()
    }

    @Test
    fun `the grammar accepts extras after the language, by id or by quote, up to the bound`() {
        assertThat(matcher.accepts("de; ${entry()}")).isTrue()
        assertThat(matcher.accepts("de; NONE \"Klasse\" school_class \"2a\" HIGH")).isTrue()
        val full = List(StructuredGrammar.MAX_EXTRAS) { entry() }.joinToString("; ")
        assertThat(matcher.accepts("en; $full")).isTrue()
    }

    @Test
    fun `the grammar refuses a list longer than its bound`() {
        val tooMany = List(StructuredGrammar.MAX_EXTRAS + 1) { entry() }.joinToString("; ")
        assertThat(matcher.accepts("en; $tooMany")).isFalse()
    }

    @Test
    fun `the grammar refuses an id that was not offered, a missing language and a wrong confidence`() {
        assertThat(matcher.accepts("de; ${entry("N9")}")).isFalse()
        assertThat(matcher.accepts("; ${entry()}")).isFalse()
        assertThat(matcher.accepts("NONE")).isFalse()
        assertThat(matcher.accepts("de; N4 \"x\" meter_number \"\" CERTAIN")).isFalse()
    }

    @Test
    fun `the grammar has no counted repetition`() {
        val grammar = QuestionGrammars.languageAndExtras(listOf("N4"))
        assertThat(Regex("\\{\\d*,?\\d*\\}").containsMatchIn(grammar)).isFalse()
    }

    @Test
    fun `the reader takes the language and every complete extra`() {
        val read = AnswerReader.languageAndExtras("de; ${entry()}; NONE \"Klasse\" school_class \"2a\" HIGH")
        assertThat(read.language).isEqualTo("de")
        assertThat(read.extras.map { it.label }).containsExactly("Zählernummer", "Klasse").inOrder()
        assertThat(read.extras[1].id).isEqualTo("NONE")
        assertThat(read.extras[1].value).isEqualTo("2a")
    }

    @Test
    fun `the reader keeps the language when the extras are cut off`() {
        val read = AnswerReader.languageAndExtras("fr; N4 \"Zähl")
        assertThat(read.language).isEqualTo("fr")
        assertThat(read.extras).isEmpty()
    }

    @Test
    fun `the reader finds no language in an answer that is not a bare code`() {
        assertThat(AnswerReader.languageAndExtras("\"text\"").language).isNull()
        assertThat(AnswerReader.languageAndExtras("NONE \"x\" k \"v\" LOW").language).isNull()
        assertThat(AnswerReader.languageAndExtras("").extras).isEmpty()
    }

    @Test
    fun `the question lists only the candidates it was given`() {
        val prepared = Prepared(Letters.invoice.pages)
        val question = QuestionnairePrompt.languageAndExtras(OfferedCandidates(prepared.offered.rows.take(2)))
        val ids = prepared.offered.rows.take(2).map { it.candidate.id }
        for (id in ids) assertThat(question.grammar).contains("\"$id\"")
        val other = prepared.offered.rows.drop(2).first().candidate.id
        assertThat(question.grammar).doesNotContain("\"$other\"")
        assertThat(question.name).isEqualTo("langextras")
        assertThat(question.text).contains("BCP-47")
    }
}
