package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.testing.Fixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [EntityExtractor], the last resort when nothing else read the document.
 *
 * It reports values found by shape only: no label, no role, no document type, no language. The same
 * values come out of a German, an English and a keyword-free text.
 */
class EntityExtractorTest {

    private val extractor = EntityExtractor()

    private fun extract(text: String) = extractor.extract("doc-1", text, null)

    private fun values(text: String, type: ExtractedFieldType) =
        extract(text).fields.filter { it.fieldType == type }.map { it.fieldValue }

    @Nested
    @DisplayName("Found by shape")
    inner class Shapes {

        @Test
        fun `a valid IBAN is found in German, English and keyword-free text`() {
            val iban = "DE89 3704 0044 0532 0130 00"
            assertThat(values("Bitte überweisen Sie auf IBAN $iban bis morgen.", ExtractedFieldType.IBAN)).containsExactly(iban)
            assertThat(values("Please pay to account $iban today.", ExtractedFieldType.IBAN)).containsExactly(iban)
            assertThat(values("$iban", ExtractedFieldType.IBAN)).containsExactly(iban)
        }

        @Test
        fun `an IBAN failing its checksum is not offered`() {
            assertThat(values("DE89 3704 0044 0532 0130 01", ExtractedFieldType.IBAN)).isEmpty()
        }

        @Test
        fun `e-mail addresses are found and a trailing full stop is not part of them`() {
            assertThat(values("Mail an service@nordlicht-mobil.example.", ExtractedFieldType.EMAIL))
                .containsExactly("service@nordlicht-mobil.example")
            assertThat(values("a.b@mail.example.co.uk", ExtractedFieldType.EMAIL)).containsExactly("a.b@mail.example.co.uk")
        }

        @Test
        fun `dates and amounts are found without any label`() {
            val r = extract("25.09.2026\n64,98 €")
            assertThat(r.fields.filter { it.fieldType == ExtractedFieldType.DATE }.map { it.fieldValue }).containsExactly("25.09.2026")
            assertThat(r.fields.filter { it.fieldName.startsWith("Found amount") }.map { it.fieldValue }).containsExactly("64,98 €")
        }

        @Test
        fun `an English text gives the same kinds of values`() {
            val r = extract("Dear Sir,\nyour bill of 64,98 EUR is due on 2026-10-14.\nCall +49 30 1234567 or write to help@example.org")
            assertThat(r.fields.map { it.fieldType }).containsAtLeast(
                ExtractedFieldType.DATE, ExtractedFieldType.OTHER, ExtractedFieldType.EMAIL, ExtractedFieldType.PHONE,
            )
        }
    }

    @Nested
    @DisplayName("Nothing is decided")
    inner class NothingDecided {

        @Test
        fun `no sender, receiver, subject, date, deadline, type or summary is guessed`() {
            val r = extract(Fixtures.GERMAN_LETTER_JOBCENTER)
            assertThat(r.sender).isNull()
            assertThat(r.receiver).isNull()
            assertThat(r.subject).isNull()
            assertThat(r.date).isNull()
            assertThat(r.deadline).isNull()
            assertThat(r.documentType).isNull()
            assertThat(r.summary).isNull()
        }

        @Test
        fun `no field is named or typed after a meaning`() {
            for (text in listOf(Fixtures.GERMAN_LETTER_JOBCENTER, Fixtures.ENGLISH_LETTER)) {
                val fields = extract(text).fields
                assertThat(fields.map { it.fieldType }).containsNoneOf(
                    ExtractedFieldType.DEADLINE, ExtractedFieldType.PERSON_NAME, ExtractedFieldType.ORGANIZATION,
                    ExtractedFieldType.SUBJECT, ExtractedFieldType.ADDRESS, ExtractedFieldType.TEXT,
                )
                assertThat(fields.all { it.fieldName.startsWith("Found ") }).isTrue()
            }
        }

        @Test
        fun `every value is low confidence, needs review and is never pre-confirmed`() {
            val fields = extract(Fixtures.GERMAN_LETTER_JOBCENTER).fields + extract(Fixtures.ENGLISH_LETTER).fields
            assertThat(fields).isNotEmpty()
            for (f in fields) {
                assertThat(f.confidence).isEqualTo(0.3f)
                assertThat(f.needsReview).isTrue()
                assertThat(f.isConfirmed).isFalse()
            }
        }

        @Test
        fun `the language is not detected`() {
            assertThat(extract(Fixtures.ARABIC_TEXT).language).isNull()
            assertThat(extractor.extract("d", Fixtures.ENGLISH_LETTER, "en").language).isEqualTo("en")
        }
    }

    @Nested
    @DisplayName("Robustness")
    inner class Robustness {

        @Test
        fun `empty input returns an empty result`() {
            val r = extract("")
            assertThat(r.fields).isEmpty()
            assertThat(r.documentId).isEqualTo("doc-1")
        }

        @Test
        fun `whitespace-only input does not throw`() {
            assertThat(extract(Fixtures.WHITESPACE_ONLY).fields).isEmpty()
        }

        @Test
        fun `garbled OCR does not throw`() {
            extract("l1lI| ~~ ¦¦¦ @@@ ### 0OoO 12..34,,,56 ---")
        }

        @Test
        fun `very long input is handled and the result is bounded`() {
            val r = extract("25.09.2026 und 64,98 € ".repeat(5_000))
            assertThat(r.fields.size).isAtMost(24)
        }

        @Test
        fun `documentId is always propagated`() {
            val r = extract(Fixtures.GERMAN_LETTER_JOBCENTER)
            assertThat(r.documentId).isEqualTo("doc-1")
            assertThat(r.fields.all { it.documentId == "doc-1" }).isTrue()
        }
    }
}
