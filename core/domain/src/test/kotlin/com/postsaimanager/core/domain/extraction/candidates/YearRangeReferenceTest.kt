package com.postsaimanager.core.domain.extraction.candidates

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * "2025/2026" is a period by its shape (two plausible years, the second after the first), not an identifier, so it is never offered as a
 * reference (the din-letter's reference came out as the billing year instead of its customer number). A shape rule: no word is read.
 */
class YearRangeReferenceTest {

    private fun references(vararg lines: String) = run(page(*lines)).candidates.filter { it.kind == CandidateKind.REFERENCE }.map { it.raw }

    @Test
    @DisplayName("a billing year written dddd/dddd is not a reference; the customer number beside it is")
    fun `year range is not a reference`() {
        val refs = references("Jahresabrechnung Strom 2025/2026", "Kundennummer KD-0000-4711")

        assertThat(refs).doesNotContain("2025/2026")
        assertThat(refs).contains("KD-0000-4711")
    }

    @Test
    @DisplayName("the same period with a hyphen, and in any language, is a period too")
    fun `hyphen and other languages`() {
        assertThat(references("Annual statement 2025-2026")).doesNotContain("2025-2026")
        assertThat(references("Relevé annuel 2024/2025")).doesNotContain("2024/2025")
        assertThat(references("كشف سنوي 2025/2026")).doesNotContain("2025/2026")
    }

    @Test
    @DisplayName("the shape rule: both parts plausible years, the second after the first and at most ten years later")
    fun `the shape`() {
        assertThat(IdentifierFinder.isYearRange("2025/2026")).isTrue()
        assertThat(IdentifierFinder.isYearRange("1999/2000")).isTrue()
        assertThat(IdentifierFinder.isYearRange("2025/2035")).isTrue()
        // The second year is not after the first, or too far from it, or not a year at all: possibly an identifier.
        assertThat(IdentifierFinder.isYearRange("2026/2025")).isFalse()
        assertThat(IdentifierFinder.isYearRange("2025/2025")).isFalse()
        assertThat(IdentifierFinder.isYearRange("2025/2040")).isFalse()
        assertThat(IdentifierFinder.isYearRange("1234/5678")).isFalse()
        assertThat(IdentifierFinder.isYearRange("0123/0124")).isFalse()
        assertThat(IdentifierFinder.isYearRange("2025/2026/4711")).isFalse()
        assertThat(IdentifierFinder.isYearRange("AB-2025/2026")).isFalse()
    }

    @Test
    @DisplayName("an identifier that only contains years, or two numbers that are no years, is still offered")
    fun `identifiers stay`() {
        assertThat(references("Aktenzeichen AB-2025/2026")).contains("AB-2025/2026")
        assertThat(references("Vorgang 2026/0815/77")).contains("2026/0815/77")
        assertThat(references("Nummer 4711/9024")).contains("4711/9024")
    }
}
