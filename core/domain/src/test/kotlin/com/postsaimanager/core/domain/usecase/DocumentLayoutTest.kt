package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.LayoutZone
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [DocumentLayout].
 *
 * These encode the shape of a German business letter, because that is the corpus. The case
 * that matters is the address block and the reference block sitting side by side: read
 * naively they interleave, and a recipient's name ends up between two reference numbers.
 */
class DocumentLayoutTest {

    private fun block(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = OcrBlock(
        text = text,
        bounds = TextBounds(left, top, right, bottom),
        confidence = 0.9f,
    )

    /** The layout of the letter used throughout these tests, as fractions of the page. */
    private val letterhead = block("Jobcenter Berlin Mitte", 0.08f, 0.04f, 0.45f, 0.09f)
    private val address = block("Frau\nAylin Mustermann\nSeestraße 42", 0.08f, 0.20f, 0.40f, 0.30f)
    private val reference = block(
        "Aktenzeichen: BG 1234/5678\nIhr Zeichen: WS-2026-0142\nDatum: 15.01.2026",
        0.58f, 0.20f, 0.92f, 0.28f,
    )
    private val subject = block("Widerspruchsbescheid", 0.08f, 0.40f, 0.50f, 0.44f)
    private val body = block("Sehr geehrte Frau Mustermann, ...", 0.08f, 0.50f, 0.92f, 0.80f)
    private val footer = block("i. A. Schmidt", 0.08f, 0.92f, 0.35f, 0.96f)

    private val page = listOf(body, reference, letterhead, footer, address, subject)

    @Nested
    @DisplayName("Reading order")
    inner class Order {

        @Test
        @DisplayName("side-by-side columns are kept whole, not interleaved")
        fun `address and reference blocks do not interleave`() {
            val ordered = DocumentLayout.readingOrder(page)
            val texts = ordered.map { it.text }

            // Both sit in the same vertical band. Sorting by y alone would split them line
            // by line and put the recipient's name between two reference numbers.
            val addressIndex = texts.indexOf(address.text)
            val referenceIndex = texts.indexOf(reference.text)
            assertThat(addressIndex).isLessThan(referenceIndex)
            // Adjacent: nothing from elsewhere on the page wedged between them.
            assertThat(referenceIndex - addressIndex).isEqualTo(1)
        }

        @Test
        fun `runs top to bottom overall`() {
            val texts = DocumentLayout.readingOrder(page).map { it.text }

            assertThat(texts.first()).isEqualTo(letterhead.text)
            assertThat(texts.last()).isEqualTo(footer.text)
            assertThat(texts.indexOf(subject.text)).isLessThan(texts.indexOf(body.text))
        }

        @Test
        fun `is stable regardless of the order OCR returned blocks in`() {
            val shuffled = listOf(footer, subject, reference, address, body, letterhead)

            // ML Kit makes no ordering guarantee, so the result must not depend on it.
            assertThat(DocumentLayout.readingOrder(shuffled).map { it.text })
                .isEqualTo(DocumentLayout.readingOrder(page).map { it.text })
        }

        @Test
        @DisplayName("a tall block beside a short one still counts as side by side")
        fun `bands are decided by overlap, not by equal height`() {
            // A six-line address next to a one-line date share little of the address's
            // height but nearly all of the date's.
            val tall = block("six\nline\naddress\nblock\nhere\nnow", 0.08f, 0.20f, 0.40f, 0.34f)
            val short = block("15.01.2026", 0.70f, 0.21f, 0.92f, 0.235f)

            val ordered = DocumentLayout.readingOrder(listOf(short, tall))

            assertThat(ordered.map { it.text }).containsExactly(tall.text, short.text).inOrder()
        }

        @Test
        fun `empty and single-block pages are handled`() {
            assertThat(DocumentLayout.readingOrder(emptyList())).isEmpty()
            assertThat(DocumentLayout.readingOrder(listOf(body))).containsExactly(body)
        }
    }

    @Nested
    @DisplayName("Zones")
    inner class Zones {

        @Test
        fun `the letterhead is the header`() {
            assertThat(DocumentLayout.zoneOf(letterhead)).isEqualTo(LayoutZone.HEADER_LEFT)
        }

        @Test
        @DisplayName("the window-envelope area splits into addressee and reference")
        fun `address and reference are distinguished by side`() {
            // The whole point: same band, different meaning, and the only thing separating
            // them is which side of the page they are on.
            assertThat(DocumentLayout.zoneOf(address)).isEqualTo(LayoutZone.ADDRESS_BLOCK)
            assertThat(DocumentLayout.zoneOf(reference)).isEqualTo(LayoutZone.REFERENCE_BLOCK)
        }

        @Test
        fun `subject and body are the body`() {
            assertThat(DocumentLayout.zoneOf(subject)).isEqualTo(LayoutZone.BODY)
            assertThat(DocumentLayout.zoneOf(body)).isEqualTo(LayoutZone.BODY)
        }

        @Test
        fun `the signature is the footer`() {
            assertThat(DocumentLayout.zoneOf(footer)).isEqualTo(LayoutZone.FOOTER)
        }

        @Test
        fun `blocks can be pulled out by zone`() {
            assertThat(DocumentLayout.inZone(page, LayoutZone.REFERENCE_BLOCK))
                .containsExactly(reference)
        }
    }

    @Nested
    @DisplayName("Description for the model")
    inner class Description {

        @Test
        fun `labels every block with its zone and position`() {
            val described = DocumentLayout.describe(page)

            assertThat(described).contains("address block")
            assertThat(described).contains("reference block")
            assertThat(described).contains("Aktenzeichen: BG 1234/5678")
            // Position is what lets the model prefer a name in the address block over a
            // name in the footer.
            assertThat(described).contains("%")
        }

        @Test
        fun `multi-line blocks stay on one labelled line`() {
            val described = DocumentLayout.describe(listOf(address))

            // One line per block, so the zone label cannot drift away from its content.
            assertThat(described.lines()).hasSize(1)
            assertThat(described).contains("Aylin Mustermann")
        }

        @Test
        fun `follows reading order`() {
            val described = DocumentLayout.describe(page)
            assertThat(described.indexOf("Aylin Mustermann"))
                .isLessThan(described.indexOf("BG 1234/5678"))
        }

        @Test
        fun `an empty page describes as nothing`() {
            assertThat(DocumentLayout.describe(emptyList())).isEmpty()
        }
    }

    @Nested
    @DisplayName("Plain text")
    inner class Plain {

        @Test
        fun `keeps columns intact`() {
            val text = DocumentLayout.plainText(page)

            // Chunking and embedding consume this, so the same interleaving problem would
            // otherwise put an address and a reference number in one passage.
            assertThat(text.indexOf("Aylin Mustermann")).isLessThan(text.indexOf("BG 1234/5678"))
            assertThat(text.indexOf("Jobcenter")).isLessThan(text.indexOf("Aylin"))
        }
    }
}
