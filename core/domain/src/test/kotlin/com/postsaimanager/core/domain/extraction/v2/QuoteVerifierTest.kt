package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class QuoteVerifierTest {

    private val letter = """
        Nordlicht Mobilfunk GmbH
        Herrn und Frau
        Max und Erika Mustermann
        Musterstraße 12
        Bitte überweisen Sie den offenen Betrag von 64,98 € innerhalb von 14 Tagen.
        Erziehungsberechtigte von
        Adam Mustermann
    """.trimIndent()

    private fun match(quote: String, text: String = letter) = QuoteVerifier.verify(quote, text)?.match

    @Test
    fun `exact text is an exact match`() {
        assertThat(match("Nordlicht Mobilfunk GmbH")).isEqualTo(QuoteMatch.EXACT)
    }

    @Test
    fun `case, spacing, punctuation and accents are ignored in the normalised tier`() {
        assertThat(match("nordlicht  mobilfunk gmbh")).isEqualTo(QuoteMatch.NORMALIZED)
        assertThat(match("Musterstrasse 12")).isEqualTo(QuoteMatch.NORMALIZED)
        assertThat(match("offenen Betrag von 64,98 € innerhalb von 14 Tagen")).isNotNull()
    }

    @Test
    fun `words on separate lines still match as one run`() {
        assertThat(match("Erziehungsberechtigte von Adam Mustermann")).isEqualTo(QuoteMatch.NORMALIZED)
    }

    @Test
    fun `a name assembled from two lines is a fuzzy match, not a rejection`() {
        // "Max und Erika Mustermann" is on one line: the second name is a plain substring, the first is assembled
        assertThat(match("Erika Mustermann")).isEqualTo(QuoteMatch.EXACT)
        assertThat(match("Max Mustermann")).isEqualTo(QuoteMatch.FUZZY)
    }

    @Test
    fun `an OCR slip of one letter in a long word still verifies`() {
        assertThat(match("Musterstralße 12", "Absender\nMusterstraße 12\n54321 Beispieldorf")).isEqualTo(QuoteMatch.FUZZY)
    }

    @Test
    fun `a short quote may be a couple of edits away`() {
        assertThat(match("Mustermnn", "Erika Mustermann")).isEqualTo(QuoteMatch.FUZZY)
    }

    @Test
    fun `something the letter never said is rejected`() {
        assertThat(match("Sie haben gewonnen")).isNull()
        assertThat(match("Finanzamt Beispielstadt")).isNull()
        assertThat(match("Nordlicht Telekom AG")).isNull()
    }

    @Test
    fun `a blank or one character quote is rejected`() {
        assertThat(match("")).isNull()
        assertThat(match("a")).isNull()
    }

    @Test
    fun `Arabic spelling variants match`() {
        val arabic = "أحمد علي\nشارع النخيل 5\nعيادة الدكتورة نور"
        // alef with hamza vs bare alef, teh marbuta vs heh, tatweel inside a word
        assertThat(match("احمد علي", arabic)).isEqualTo(QuoteMatch.NORMALIZED)
        assertThat(match("عيادة الدكتوره نور", arabic)).isEqualTo(QuoteMatch.NORMALIZED)
        assertThat(match("شـارع النخيل", arabic)).isEqualTo(QuoteMatch.NORMALIZED)
    }

    @Test
    fun `Arabic-Indic digits are the same as western digits`() {
        assertThat(match("التاريخ 28.09.2026", "التاريخ ٢٨.٠٩.٢٠٢٦")).isEqualTo(QuoteMatch.EXACT)
    }

    @Test
    fun `a text in another script is not confused with a Latin one`() {
        assertThat(match("Mustermann", "إيريكا موستيرمان")).isNull()
    }
}
