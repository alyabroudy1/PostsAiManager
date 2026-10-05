package com.postsaimanager.core.domain.extraction.layout

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.Letters
import org.junit.jupiter.api.Test

/** Zones come from geometry and shape; the German patterns are only hints, so other languages work. */
class LayoutOtherLanguagesTest {

    private fun LetterLayout.texts(zone: LetterZone) = zone(zone, 1).map { it.text }

    @Test
    fun `an English letter with a UK postcode gets its address field and return line`() {
        val l = LetterLayoutAnalyzer.analyze(Letters.english.pages)
        assertThat(l.texts(LetterZone.RETURN_ADDRESS_LINE)).hasSize(1)
        assertThat(l.texts(LetterZone.ADDRESS_FIELD)).containsAtLeast("Mr John Sample", "Exampleton EX2 3PL")
        assertThat(l.texts(LetterZone.LETTERHEAD)).contains("Northwind Utilities Ltd.")
    }

    @Test
    fun `a right-to-left letter is read as if mirrored, so its address field is found`() {
        val l = LetterLayoutAnalyzer.analyze(Letters.arabic.pages)
        assertThat(l.texts(LetterZone.ADDRESS_FIELD)).contains("السيدة إيريكا موستيرمان")
        assertThat(l.texts(LetterZone.LETTERHEAD)).contains("شركة المثال للخدمات المحدودة")
    }

    @Test
    fun `the subject is the first isolated line even without Betreff or a salutation`() {
        val l = LetterLayoutAnalyzer.analyze(Letters.arabic.pages)
        assertThat(l.texts(LetterZone.SUBJECT).single()).startsWith("الموضوع")
    }

    @Test
    fun `an account number marks a payment section in any language`() {
        val l = LetterLayoutAnalyzer.analyze(Letters.english.pages)
        assertThat(l.allLines.none { it.zone == LetterZone.PAYMENT_SECTION && it.text.startsWith("Dear") }).isTrue()
    }
}
