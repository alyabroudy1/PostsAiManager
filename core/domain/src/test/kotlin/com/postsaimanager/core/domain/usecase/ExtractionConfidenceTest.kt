package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.RecognisedEntity
import com.postsaimanager.core.model.RecognisedFact
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ExtractionConfidence].
 *
 * Every case here is either a real device-run failure the on-device models produced against
 * a scanned German letter, or the property ("scores vary") that the whole exercise exists to
 * deliver. [letter] mirrors the page in `ExtractionModelTest`'s `jobcenterLetter`, as plain
 * text — the shape [AiExtractionUseCase] now hands this object.
 */
class ExtractionConfidenceTest {

    private fun fact(value: String, kind: FactKind, label: String = "x") =
        RecognisedFact(label, value, kind, confidence = 0.9f)

    private fun entity(
        name: String,
        role: EntityRole = EntityRole.MENTIONED,
        kind: EntityKind = EntityKind.PERSON,
    ) = RecognisedEntity(name, kind, role, confidence = 0.9f)

    /** A realistic German authority letter, as the OCR/layout pipeline would present it. */
    private val letter = """
        Jobcenter Berlin Mitte
        Müllerstraße 16, 13353 Berlin

        Frau
        Aylin Mustermann
        Seestraße 42
        13347 Berlin

        Aktenzeichen: BG 1234/5678
        Ihr Zeichen: WS-2026-0142
        Datum: 15.01.2026

        Widerspruchsbescheid

        Sehr geehrte Frau Mustermann,
        Ihr Widerspruch vom 12.01.2026 gegen unseren Bescheid vom 03.12.2025 wurde
        geprüft. Der Widerspruch wird als unbegründet zurückgewiesen.

        Bitte reichen Sie die noch fehlenden Unterlagen bis zum 31.01.2026 bei uns ein.
        Andernfalls müssen wir die laufenden Leistungen vorläufig einstellen.

        Ihre monatliche Regelleistung beträgt ab dem 01.02.2026 voraussichtlich 563,00 Euro.

        Für Rückfragen steht Ihnen Herr Schmidt unter 030 12345678 zur Verfügung.

        Mit freundlichen Grüßen
        i. A. Schmidt
    """.trimIndent()

    @Nested
    @DisplayName("Observed failure: hallucinated values")
    inner class Hallucination {

        @Test
        @DisplayName("a deadline with the wrong year, not present anywhere on the page, scores low")
        fun `hallucinated year is not grounded`() {
            // Device run: the model returned 31.01.2066 for a page that says 31.01.2026 —
            // a fabricated year, not a misread one. A substring check is the cheapest
            // possible defence and it is exactly what catches this.
            val hallucinated = fact("31.01.2066", FactKind.DEADLINE)

            assertThat(ExtractionConfidence.forFact(hallucinated, letter)).isLessThan(0.2f)
        }

        @Test
        fun `the real deadline on the same page scores high`() {
            // Same field, correct value — the point is that the check discriminates, not
            // that it distrusts every deadline.
            val real = fact("31.01.2026", FactKind.DEADLINE)

            assertThat(ExtractionConfidence.forFact(real, letter)).isAtLeast(0.9f)
        }
    }

    @Nested
    @DisplayName("Observed failure: format contradicts the claimed kind")
    inner class FormatMismatch {

        @Test
        @DisplayName("a phone number tagged as an IBAN scores low even though the digits are real")
        fun `phone number is not a valid IBAN shape`() {
            // Device run: the model labelled "030 12345678" (Herr Schmidt's phone number,
            // literally on the page) as kind IBAN. Grounding alone would call this
            // confident because the text really is there — the format check is what
            // catches that the *classification* is wrong.
            val misclassified = fact("030 12345678", FactKind.IBAN)

            assertThat(ExtractionConfidence.forFact(misclassified, letter)).isLessThan(0.2f)
        }

        @Test
        fun `a real IBAN shape on the page scores high`() {
            val realIban = fact("DE02120300000000202051", FactKind.IBAN)
            val page = "$letter\nIBAN: DE02120300000000202051"

            assertThat(ExtractionConfidence.forFact(realIban, page)).isAtLeast(0.9f)
        }

        @Test
        fun `a reference with no digit at all is not a plausible reference`() {
            val noDigits = fact("Aktenzeichen", FactKind.REFERENCE)

            assertThat(ExtractionConfidence.forFact(noDigits, letter)).isLessThan(0.2f)
        }
    }

    @Nested
    @DisplayName("Observed failure: salutation read as a name")
    inner class SalutationAsName {

        @Test
        @DisplayName("'Frau' alone, the earlier regex extractor's bug, scores low as an entity name")
        fun `bare salutation is not a name`() {
            // The regex extractor this replaced read the salutation "Frau" as the
            // recipient's name. It is grounded — "Frau" is right there on the page — so
            // grounding alone would score it high. The salutation check has to run first.
            val salutationOnly = entity("Frau", role = EntityRole.RECIPIENT)

            assertThat(ExtractionConfidence.forEntity(salutationOnly, letter)).isLessThan(0.2f)
        }

        @Test
        fun `a title attached to a real name is not treated as salutation-only`() {
            // "Frau Mustermann" (from the letter's own "Sehr geehrte Frau Mustermann,") is a
            // name with a title on it, not just a title. Only the exact bare salutation
            // should be caught, or every titled name in German business correspondence would
            // be flagged.
            val titledName = entity("Frau Mustermann", role = EntityRole.RECIPIENT)

            assertThat(ExtractionConfidence.forEntity(titledName, letter)).isGreaterThan(0.5f)
        }

        @Test
        fun `the actual recipient name scores high`() {
            val recipient = entity("Aylin Mustermann", role = EntityRole.RECIPIENT)

            assertThat(ExtractionConfidence.forEntity(recipient, letter)).isAtLeast(0.9f)
        }
    }

    @Nested
    @DisplayName("Documented gap: role errors")
    inner class RoleErrorsNotCovered {

        @Test
        @DisplayName("tagging the sender's organisation as MENTIONED does not change its score")
        fun `role is not part of the signature this checks`() {
            // Device run: the model tagged the sender organisation's role as MENTIONED
            // instead of SENDER. The name is correct and grounded, so this design — which
            // only ever looks at name/value text against the page — cannot tell the
            // difference. Pinned here so that stays a documented, deliberate gap rather
            // than a silent one: if this test starts failing, either role has started
            // affecting the score (a real change) or the gap has been closed elsewhere.
            val asSender = entity(
                "Jobcenter Berlin Mitte",
                role = EntityRole.SENDER,
                kind = EntityKind.AUTHORITY,
            )
            val asMentioned = asSender.copy(role = EntityRole.MENTIONED)

            assertThat(ExtractionConfidence.forEntity(asSender, letter))
                .isEqualTo(ExtractionConfidence.forEntity(asMentioned, letter))
        }
    }

    @Nested
    @DisplayName("Grounding tiers")
    inner class Grounding {

        @Test
        @DisplayName("OCR's German diacritic noise (Strasse vs Straße) still scores as grounded")
        fun `diacritic folding tolerates ASCII spellings of German letters`() {
            // A model that types the address back without the umlaut/eszett — a common
            // normalisation models and some OCR passes apply — should not be punished for
            // spelling, only for making things up.
            val asciiSpelling = fact("Muellerstrasse 16", FactKind.OTHER)

            assertThat(ExtractionConfidence.forFact(asciiSpelling, letter)).isAtLeast(0.7f)
        }

        @Test
        @DisplayName("a value whose words are scattered across the page, not copied as one run")
        fun `token subset grounds a paraphrase below a direct copy`() {
            val paraphrased = fact("Unterlagen fehlenden reichen", FactKind.OTHER)
            val direct = fact("noch fehlenden Unterlagen", FactKind.OTHER)

            val paraphraseScore = ExtractionConfidence.forFact(paraphrased, letter)
            val directScore = ExtractionConfidence.forFact(direct, letter)

            // Real evidence, but weaker than a direct copy — and specifically below the
            // auto-link threshold, so this lands in review rather than being trusted.
            assertThat(paraphraseScore).isLessThan(directScore)
            assertThat(paraphraseScore).isLessThan(0.75f)
        }

        @Test
        fun `a value entirely absent from the page scores at the bottom`() {
            val invented = fact("Musterfirma GmbH", FactKind.OTHER)

            assertThat(ExtractionConfidence.forFact(invented, letter)).isLessThan(0.2f)
        }
    }

    @Nested
    @DisplayName("The property the review queue depends on")
    inner class Spread {

        @Test
        @DisplayName("a realistic mixed batch spreads across the range, not clustered at 0.9")
        fun `scores vary enough to sort a review list from a trusted list`() {
            // This is the entire point: the model that fed this returned 0.9 for every one
            // of these, which would make "needs review" either empty or everything.
            val scores = listOf(
                ExtractionConfidence.forFact(fact("31.01.2026", FactKind.DEADLINE), letter),
                ExtractionConfidence.forFact(fact("31.01.2066", FactKind.DEADLINE), letter),
                ExtractionConfidence.forFact(fact("030 12345678", FactKind.IBAN), letter),
                ExtractionConfidence.forFact(fact("BG 1234/5678", FactKind.REFERENCE), letter),
                ExtractionConfidence.forEntity(entity("Frau", role = EntityRole.RECIPIENT), letter),
                ExtractionConfidence.forEntity(
                    entity("Aylin Mustermann", role = EntityRole.RECIPIENT),
                    letter,
                ),
            )

            assertThat(scores.max() - scores.min()).isGreaterThan(0.5f)
        }
    }

    @Nested
    @DisplayName("Degenerate input")
    inner class Degenerate {

        @Test
        fun `blank page text does not crash and scores the lowest band`() {
            val result = ExtractionConfidence.forFact(fact("anything", FactKind.OTHER), "")

            assertThat(result).isLessThan(0.2f)
        }

        @Test
        fun `blank value does not crash and scores the lowest band`() {
            val result = ExtractionConfidence.forFact(fact("   ", FactKind.OTHER), letter)

            assertThat(result).isLessThan(0.2f)
        }

        @Test
        fun `blank entity name does not crash`() {
            val result = ExtractionConfidence.forEntity(entity("  "), letter)

            assertThat(result).isLessThan(0.2f)
        }

        @Test
        fun `every score stays within the declared confidence range`() {
            val values = listOf(
                ExtractionConfidence.forFact(fact("31.01.2026", FactKind.DEADLINE), letter),
                ExtractionConfidence.forFact(fact("nonsense", FactKind.OTHER), letter),
                ExtractionConfidence.forEntity(entity("Aylin Mustermann"), letter),
                ExtractionConfidence.forEntity(entity("Frau"), letter),
            )
            values.forEach {
                assertThat(it).isAtLeast(0f)
                assertThat(it).isAtMost(1f)
            }
        }
    }
}
