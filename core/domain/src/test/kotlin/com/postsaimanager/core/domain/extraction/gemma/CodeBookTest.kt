package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CodeBookTest {

    private val book = CodeBook("d", listOf("LETTER_DATE", "DUE_DATE", "other"))

    @Test
    @DisplayName("a code is the id's place in the list, behind the prefix, and maps back")
    fun `round trip`() {
        assertThat(book.codes).containsExactly("d1", "d2", "d3").inOrder()
        assertThat(book.codeOf("DUE_DATE")).isEqualTo("d2")
        assertThat(book.idOf("d2")).isEqualTo("DUE_DATE")
        assertThat(book.idOf(" D3 ")).isEqualTo("other")
    }

    @Test
    @DisplayName("a word that is no code is read as the id it is (an id written in full), and an empty word is nothing")
    fun `ids in full`() {
        assertThat(book.idOf("DUE_DATE")).isEqualTo("DUE_DATE")
        assertThat(book.idOf("unknown")).isEqualTo("unknown")
        assertThat(book.idOf("")).isNull()
        assertThat(book.idOf(null)).isNull()
        assertThat(book.codeOf("missing")).isNull()
    }

    @Test
    @DisplayName("a repeated id has one code")
    fun `distinct`() {
        assertThat(CodeBook("x", listOf("a", "a", "b")).codes).containsExactly("x1", "x2").inOrder()
    }

    @Test
    @DisplayName("the vocabulary's books cover every registry list, with a code for none of these")
    fun `vocabulary books`() {
        val v = GemmaVocabulary.DEFAULT

        assertThat(v.categoryCodes.codes).hasSize(v.categoryIds.size)
        assertThat(v.dateMeaningCodes.idOf(v.dateMeaningCodes.codeOf(GemmaVocabulary.OTHER))).isEqualTo(GemmaVocabulary.OTHER)
        assertThat(v.eventKindCodes.codes).hasSize(v.eventKindIds.size)
        assertThat(v.partyRoleCodes.codes).hasSize(GemmaSchema.PARTIES.size)
        assertThat(v.actionKindCodes.codes).hasSize(v.actionKinds.size)
    }

    @Test
    @DisplayName("paid words are read case-insensitively and anything else is no state")
    fun `paid state`() {
        assertThat(PaidState.of("ALREADY_PAID")).isEqualTo(PaidState.ALREADY_PAID)
        assertThat(PaidState.of(" to_pay ")).isEqualTo(PaidState.TO_PAY)
        assertThat(PaidState.of("paid")).isNull()
        assertThat(PaidState.of(null)).isNull()
    }
}
