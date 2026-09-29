package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.postsaimanager.core.domain.benchmark.BenchmarkFixtures
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.layout.PageLayoutView
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

/**
 * The template matcher on the benchmark's real phone OCR (16 letters) and on synthetic geometry for the
 * classes the fixtures do not cover (a form, a right-to-left letter). The expected template of each letter is
 * this test's own hand label, from what the page looks like; the manifest does not carry it.
 */
class TemplateMatcherTest {

    /** What a person would call each fixture's page: the invoice and the cost statement are table letters. */
    private val expected = mapOf(
        "invoice-2p" to "INVOICE_TABLE",
        "tax-long-7p" to "DIN5008_A",
        "english-ambiguous-3p" to "DIN5008_B",
        "receipt-noise-1p" to "RECEIPT_NARROW",
        "degraded-3p" to "DIN5008_B",
        // The phone read only a Latin summary line of this Arabic page: no zones to match, so the fallback.
        "arabic-rtl-1p" to "GENERIC",
        "N1-mahnung-telco-qr-1p" to "DIN5008_B",
        "N2-kfz-verlaengerung-2p" to "DIN5008_B",
        "N3-schule-familie-2p" to "DIN5008_B",
        "N4-zhd-firma-1p" to "DIN5008_B",
        "N5-co-familie-1p" to "DIN5008_B",
        "N6-nebenkosten-3p" to "INVOICE_TABLE",
        "N7-beitragsservice-1p" to "DIN5008_B",
        "N8-info-bank-noaction-1p" to "DIN5008_B",
        "N9-fuzzy-name-1p" to "DIN5008_B",
        "N10-kinderarzt-termin-1p" to "DIN5008_B",
    )

    private val matcher = TemplateMatcher()

    @Test
    fun `every benchmark letter gets the template a person would give it`() {
        val docs = BenchmarkFixtures.load().docs
        assertThat(docs.map { it.first.key }).containsAtLeastElementsIn(expected.keys)
        val wrong = docs.filter { (m, _) -> m.key in expected }.mapNotNull { (m, f) ->
            val match = matcher.match(LetterLayoutAnalyzer.analyze(f.pages.map { it.blocks }))
            if (match.template.id == expected.getValue(m.key)) null else "${m.key}: ${match.template.id} (expected ${expected[m.key]}) ${match.scores}"
        }
        assertWithMessage("templates that differ from the label").that(wrong).isEmpty()
    }

    @Test
    fun `the known page shape does not change the picks`() {
        for ((m, f) in BenchmarkFixtures.load().docs.filter { it.first.key in expected }) {
            val layout = LetterLayoutAnalyzer.analyze(f.pages.map { it.blocks })
            val aspect = f.pages.first().width.toFloat() / f.pages.first().height
            assertWithMessage(m.key).that(matcher.match(layout, aspect).template.id).isEqualTo(matcher.match(layout).template.id)
        }
    }

    private fun line(zone: LetterZone, l: Float, t: Float, r: Float, b: Float, text: String = "x".repeat(((r - l) * 100).toInt().coerceAtLeast(2))) =
        LayoutLine(text, 1, TextBounds(l, t, r, b), 1f, zone)

    private fun layoutOf(vararg lines: LayoutLine) = LetterLayout(listOf(PageLayoutView(1, lines.toList())))

    @Test
    fun `a page of label and value pairs is a form`() {
        val rows = (0 until 12).flatMap { i ->
            val y = 0.2f + i * 0.04f
            listOf(line(LetterZone.BODY, 0.1f, y, 0.3f, y + 0.02f), line(LetterZone.BODY, 0.5f, y, 0.8f, y + 0.02f))
        }
        val layout = layoutOf(line(LetterZone.LETTERHEAD, 0.1f, 0.05f, 0.5f, 0.07f), *rows.toTypedArray())
        assertThat(matcher.match(layout).template.id).isEqualTo("FORM_KV")
    }

    @Test
    fun `a mirrored letter is right to left`() {
        val body = (0 until 10).map { i -> line(LetterZone.BODY, 0.55f - i * 0.02f, 0.4f + i * 0.03f, 0.9f, 0.42f + i * 0.03f) }
        val layout = layoutOf(
            line(LetterZone.LETTERHEAD, 0.6f, 0.04f, 0.9f, 0.06f),
            line(LetterZone.RETURN_ADDRESS_LINE, 0.6f, 0.15f, 0.9f, 0.16f),
            line(LetterZone.ADDRESS_FIELD, 0.7f, 0.17f, 0.9f, 0.19f),
            line(LetterZone.ADDRESS_FIELD, 0.65f, 0.19f, 0.9f, 0.21f),
            line(LetterZone.ADDRESS_FIELD, 0.7f, 0.21f, 0.9f, 0.23f),
            line(LetterZone.INFO_BLOCK, 0.1f, 0.17f, 0.35f, 0.19f),
            line(LetterZone.INFO_BLOCK, 0.1f, 0.19f, 0.35f, 0.21f),
            *body.toTypedArray(),
        )
        assertThat(matcher.match(layout).template.id).isEqualTo("RTL_DIN")
    }

    @Test
    fun `a page with nothing recognisable falls back to generic`() {
        val layout = layoutOf(line(LetterZone.BODY, 0.1f, 0.5f, 0.6f, 0.52f), line(LetterZone.BODY, 0.1f, 0.55f, 0.6f, 0.57f))
        val match = matcher.match(layout)
        assertThat(match.isFallback).isTrue()
        assertThat(match.template.id).isEqualTo("GENERIC")
    }

    @Test
    fun `a wide fixture-like page and a narrow one differ by shape alone`() {
        val docs = BenchmarkFixtures.load().docs.associateBy { it.first.key }
        val receipt = LayoutFeatures(LetterLayoutAnalyzer.analyze(docs.getValue("receipt-noise-1p").second.pages.map { it.blocks }))
        val letter = LayoutFeatures(LetterLayoutAnalyzer.analyze(docs.getValue("N1-mahnung-telco-qr-1p").second.pages.map { it.blocks }))
        assertThat(receipt.fontRatio).isGreaterThan(1.1f)
        assertThat(letter.fontRatio).isLessThan(1.0f)
    }

    @Test
    fun `every template names only questions of the schema and zones it can fold`() {
        for (t in LayoutTemplates.ALL + LayoutTemplates.GENERIC) {
            assertThat(t.zones).isNotEmpty()
            assertThat(t.zones.map { it.hint }).doesNotContain("")
            // A remapped zone is not also described as a zone of its own (the template would ask it twice).
            assertThat(t.zones.map { it.zone }.intersect(t.remap.keys)).isEmpty()
        }
        assertThat(LayoutTemplates.byId("RTL_DIN")).isNotNull()
    }
}
