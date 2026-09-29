package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.testing.Fixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Characterisation tests for [EntityExtractor].
 *
 * This class is the highest-value test target in the repository: ~550 lines of
 * hand-written regex and zone-parsing heuristics with, until now, zero coverage.
 * Regex engines fail silently — they return the wrong span rather than throwing —
 * so absence of tests here meant absence of evidence, not absence of bugs.
 *
 * Tests marked **BUG** document behaviour that is currently wrong. They assert the
 * *actual* behaviour so the suite is green, and name the defect so it is not lost.
 * When the bug is fixed, the assertion flips and the test name changes.
 */
class EntityExtractorTest {

    private val extractor = EntityExtractor()

    private fun extract(text: String) = extractor.extract("doc-1", text, null)

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Language detection")
    inner class LanguageDetection {

        @Test
        fun `detects German from marker words`() {
            assertThat(extractor.detectLanguage(Fixtures.GERMAN_LETTER_JOBCENTER)).isEqualTo("de")
        }

        @Test
        fun `detects English from marker words`() {
            assertThat(extractor.detectLanguage(Fixtures.ENGLISH_LETTER)).isEqualTo("en")
        }

        @Test
        fun `detects Arabic by Unicode ratio`() {
            assertThat(extractor.detectLanguage(Fixtures.ARABIC_TEXT)).isEqualTo("ar")
        }

        @Test
        @DisplayName("empty input falls back to German rather than failing")
        fun `empty input defaults to German`() {
            assertThat(extractor.detectLanguage("")).isEqualTo("de")
            assertThat(extractor.detectLanguage(Fixtures.WHITESPACE_ONLY)).isEqualTo("de")
        }

        @Test
        @DisplayName("English with 3+ markers is correctly detected")
        fun `English is detected once the marker threshold is met`() {
            // "please", "the", "and" are all markers — exactly meets the >= 3 threshold.
            assertThat(extractor.detectLanguage("Please sign the form and return it."))
                .isEqualTo("en")
        }

        @Test
        @DisplayName("LIMITATION: English below the 3-marker threshold defaults to German")
        fun `short English without marker words defaults to German`() {
            // Zero markers in either list, so neither threshold branch fires and the
            // documented `else -> "de"` bias wins. Short notices, receipts and headings
            // are therefore labelled German regardless of their actual language.
            // Not a defect in itself — but a real ceiling on language detection that
            // matters once documents drive locale-specific handling.
            assertThat(extractor.detectLanguage("Invoice attached. Payment due on receipt."))
                .isEqualTo("de")
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("IBAN extraction")
    inner class Ibans {

        @Test
        fun `extracts spaced German IBAN and strips whitespace`() {
            val fields = extract(Fixtures.GERMAN_LETTER_JOBCENTER).fields
            val ibans = fields.filter { it.fieldType == ExtractedFieldType.IBAN }
            assertThat(ibans.map { it.fieldValue }).contains("DE89370400440532013000")
        }

        @Test
        fun `extracts unspaced IBAN`() {
            val fields = extract("Konto: DE89370400440532013000 bei der Bank").fields
            assertThat(fields.filter { it.fieldType == ExtractedFieldType.IBAN })
                .isNotEmpty()
        }

        @Test
        @DisplayName("FIXED: IBANs containing letters in the BBAN are extracted")
        fun `Dutch IBAN with letters in BBAN is extracted`() {
            val fields = extract("IBAN: NL91ABNA0417164300").fields
            assertThat(fields.filter { it.fieldType == ExtractedFieldType.IBAN }
                .map { it.fieldValue })
                .contains("NL91ABNA0417164300")
        }

        @Test
        fun `mod-97 checksum rejects a corrupted IBAN`() {
            // One digit altered — structurally valid, checksum invalid.
            val fields = extract("IBAN: DE89370400440532013001").fields
            assertThat(fields.filter { it.fieldType == ExtractedFieldType.IBAN }).isEmpty()
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Reference numbers")
    inner class ReferenceNumbers {

        @Test
        fun `extracts Aktenzeichen`() {
            val result = extract(Fixtures.GERMAN_LETTER_JOBCENTER)
            assertThat(result.referenceNumbers).isNotEmpty()
        }

        @Test
        fun `extracts Kundennummer`() {
            val fields = extract("Kundennummer: 987654321").fields
            val refs = fields.filter { it.fieldType == ExtractedFieldType.REFERENCE_NUMBER }
            assertThat(refs.map { it.fieldValue }).contains("987654321")
        }

        @Test
        @DisplayName("FIXED: reference capture stops before body prose")
        fun `Aktenzeichen capture stops at the first prose word`() {
            val text = "Aktenzeichen: AB123 Sehr geehrte Damen und Herren"
            val ref = extract(text).fields
                .first { it.fieldType == ExtractedFieldType.REFERENCE_NUMBER }

            assertThat(ref.fieldValue).isEqualTo("AB123")
        }

        @Test
        fun `multi-token reference with internal space is kept whole`() {
            // "BG 1234/5678" is one reference, not a reference plus prose.
            assertThat(extractor.cleanReferenceValue("BG 1234/5678 Sehr geehrte"))
                .isEqualTo("BG 1234/5678")
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Deadlines")
    inner class Deadlines {

        @Test
        fun `extracts 'bis zum' deadline`() {
            val result = extract(Fixtures.GERMAN_LETTER_JOBCENTER)
            assertThat(result.deadline).isEqualTo("31.01.2026")
        }

        @Test
        fun `extracts invoice payment deadline`() {
            val result = extract(Fixtures.GERMAN_INVOICE)
            assertThat(result.deadline).isEqualTo("17.02.2026")
        }

        @Test
        fun `extracts Frist with explicit label`() {
            val fields = extract("Frist: 01.03.2026").fields
            assertThat(fields.filter { it.fieldType == ExtractedFieldType.DEADLINE })
                .isNotEmpty()
        }

        @Test
        fun `no deadline present yields null`() {
            assertThat(extract("Ein Brief ohne jede Frist.").deadline).isNull()
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Emails and phones")
    inner class Contacts {

        @Test
        fun `extracts email address`() {
            val fields = extract(Fixtures.GERMAN_LETTER_JOBCENTER).fields
            val emails = fields.filter { it.fieldType == ExtractedFieldType.EMAIL }
            assertThat(emails.map { it.fieldValue })
                .contains("kontakt@jobcenter-berlin.de")
        }

        @Test
        @DisplayName("FIXED: trailing sentence period is not part of the email")
        fun `email at end of sentence excludes the full stop`() {
            val email = extract("Schreiben Sie an info@example.com.").fields
                .first { it.fieldType == ExtractedFieldType.EMAIL }

            assertThat(email.fieldValue).isEqualTo("info@example.com")
        }

        @Test
        fun `multi-part domains survive`() {
            val email = extract("Kontakt: a.b@sub.example.co.uk").fields
                .first { it.fieldType == ExtractedFieldType.EMAIL }

            assertThat(email.fieldValue).isEqualTo("a.b@sub.example.co.uk")
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Robustness — must never throw")
    inner class Robustness {

        @Test
        fun `empty input returns an empty result`() {
            val result = extract(Fixtures.EMPTY)
            assertThat(result.documentId).isEqualTo("doc-1")
            assertThat(result.fields).isEmpty()
        }

        @Test
        fun `whitespace-only input does not throw`() {
            assertThat(extract(Fixtures.WHITESPACE_ONLY).fields).isEmpty()
        }

        @Test
        fun `garbled OCR does not throw`() {
            val result = extract(Fixtures.GARBLED_OCR)
            assertThat(result).isNotNull()
        }

        @Test
        fun `very long input is handled`() {
            val long = Fixtures.GERMAN_LETTER_JOBCENTER.repeat(50)
            assertThat(extract(long)).isNotNull()
        }

        @Test
        fun `documentId is always propagated`() {
            assertThat(extractor.extract("abc-999", "irgendetwas", null).documentId)
                .isEqualTo("abc-999")
        }
    }

    // ═══════════════════════════════════════════════════════════
    @Nested
    @DisplayName("End-to-end on a full DIN 5008 letter")
    inner class FullLetter {

        @Test
        fun `produces a populated result`() {
            val result = extract(Fixtures.GERMAN_LETTER_JOBCENTER)

            assertThat(result.language).isEqualTo("de")
            assertThat(result.fields).isNotEmpty()
            assertThat(result.deadline).isNotNull()
        }

        @Test
        fun `every field carries a confidence between 0 and 1`() {
            val result = extract(Fixtures.GERMAN_LETTER_JOBCENTER)
            result.fields.forEach {
                assertThat(it.confidence).isAtLeast(0f)
                assertThat(it.confidence).isAtMost(1f)
            }
        }

        @Test
        fun `no field arrives pre-confirmed`() {
            // Extraction proposes; the user confirms. Nothing may claim confirmation.
            val result = extract(Fixtures.GERMAN_LETTER_JOBCENTER)
            assertThat(result.fields.none { it.isConfirmed }).isTrue()
        }
    }
}
