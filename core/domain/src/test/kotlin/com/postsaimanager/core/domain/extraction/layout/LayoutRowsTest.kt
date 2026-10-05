package com.postsaimanager.core.domain.extraction.layout

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.benchmark.BenchmarkFixtures
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class LayoutRowsTest {

    private fun line(text: String, l: Float, t: Float, r: Float, b: Float, zone: LetterZone = LetterZone.BODY) =
        LayoutLine(text, 1, TextBounds(l, t, r, b), 0.9f, zone)

    private val fixture = BenchmarkFixtures.load().docs.first { it.first.key == "invoice-2p" }.second
    private val layout = LetterLayoutAnalyzer.analyze(fixture.pages.map { it.blocks })

    private fun rowTexts(page: Int): List<String> = LayoutRows.of(layout.page(page)!!.lines.filter { !it.isNoise }).map { it.text }

    @Test
    fun `the table of the invoice is rebuilt row by row, page 1`() {
        val rows = rowTexts(1)
        assertThat(rows).containsAtLeast(
            "Pos. Beschreibung | Menge | Einzelpreis € Gesamt €",
            "1 | Konzeption Webportal | 4h | 95,00 | 380,00",
            "2 | Layout-Entwurf | 3 h | 95,00 | 285,00",
            "3 | Softwarelizenz Modul B | 1 | 149,90 | 149,90",
            "4 | Hosting September 2026 | 1 | 29,00 | 29,00",
        ).inOrder()
    }

    @Test
    fun `the table and the totals of page 2 are rows, a label with its value`() {
        val rows = rowTexts(2)
        assertThat(rows).containsAtLeast(
            "5 | Support-Pauschale | 1 | 39,00 | 39,00",
            "6 | Anfahrt (2 x 18,50 €) | 2 | 18,50 | 37,00",
            "Nettobetrag | 1.079,41",
            "zzgl. 19 % MwSt. auf 1.079,41 € | 205,09",
            "Gesamtbetrag | 1.284,50€",
        ).inOrder()
    }

    @Test
    fun `a table row is never the subject`() {
        assertThat(layout.page(1)!!.lines.filter { it.zone == LetterZone.SUBJECT }.map { it.text }).isEmpty()
    }

    @Test
    fun `two blocks of two lines side by side are not merged`() {
        val lines = listOf(
            line("Erika Mustermann", 0.10f, 0.20f, 0.30f, 0.215f),
            line("Musterstrasse 12", 0.10f, 0.22f, 0.30f, 0.235f),
            line("Datum: 28.09.2026", 0.65f, 0.20f, 0.90f, 0.215f),
            line("Seite: 1", 0.65f, 0.22f, 0.90f, 0.235f),
        )
        val rows = LayoutRows.of(lines).map { it.text }
        assertThat(rows).containsExactly("Erika Mustermann", "Datum: 28.09.2026", "Musterstrasse 12", "Seite: 1").inOrder()
        assertThat(LayoutRows.of(lines).none { it.isMultiColumn }).isTrue()
    }

    @Test
    fun `header blocks are never put on a row with other lines`() {
        val lines = listOf(
            line("Max Muster", 0.10f, 0.20f, 0.30f, 0.215f, LetterZone.ADDRESS_FIELD),
            line("Ihr Zeichen", 0.60f, 0.20f, 0.75f, 0.215f, LetterZone.INFO_BLOCK),
            line("AB-123", 0.80f, 0.20f, 0.90f, 0.215f, LetterZone.INFO_BLOCK),
        )
        assertThat(LayoutRows.of(lines).none { it.isMultiColumn }).isTrue()
    }

    @Test
    fun `cells of a table on a right-to-left page read right to left`() {
        fun cells(y: Float, a: String, b: String, c: String) = listOf(
            line(a, 0.75f, y, 0.90f, y + 0.012f), line(b, 0.45f, y, 0.60f, y + 0.012f), line(c, 0.10f, y, 0.25f, y + 0.012f),
        )
        val lines = cells(0.30f, "الوصف", "الكمية", "السعر") + cells(0.33f, "خدمة", "2", "50,00")
        assertThat(LayoutRows.of(lines, rightToLeft = true).map { it.text }).containsExactly("الوصف | الكمية | السعر", "خدمة | 2 | 50,00").inOrder()
    }

    @Test
    fun `a label ending in a colon is followed by its value without a bar`() {
        val lines = listOf(
            line("Gesamt", 0.10f, 0.50f, 0.25f, 0.512f), line("10,00", 0.80f, 0.50f, 0.90f, 0.512f),
            line("Netto:", 0.10f, 0.53f, 0.25f, 0.542f), line("8,40", 0.80f, 0.53f, 0.90f, 0.542f),
            line("MwSt", 0.10f, 0.56f, 0.25f, 0.572f), line("1,60", 0.80f, 0.56f, 0.90f, 0.572f),
        )
        assertThat(LayoutRows.of(lines).map { it.text }).containsExactly("Gesamt | 10,00", "Netto: 8,40", "MwSt | 1,60").inOrder()
    }
}
