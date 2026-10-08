package com.postsaimanager.core.domain.extraction.candidates

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

/**
 * The label of a label/value pair is not offered as a name (plan 15, the regression after phase 1): an information block's rows, stacked
 * as label over value, and "Label: value" on one line. The fixtures are the invented letters of `data/test-docs-p15`, their lines in the
 * order the page prints them (the rendered HTML, one OCR block per line, with the zones the layout gives them). Decided by layout alone:
 * no test, and no code, knows what a label says.
 */
class LabelValueLayoutTest {

    /** One block per row, 0.02 apart, in reading order; `@HEAD`, `@RET` and `@ADDR` tag the zone like [page] does. */
    private fun letter(vararg rows: String): CandidateSet {
        val blocks = ArrayList<OcrBlock>()
        val zones = HashMap<BlockKey, BlockZone>()
        rows.forEachIndexed { r, raw ->
            var text = raw
            val zone = when {
                raw.startsWith("@ADDR ") -> BlockZone.ADDRESS_FIELD.also { text = raw.removePrefix("@ADDR ") }
                raw.startsWith("@HEAD ") -> BlockZone.LETTERHEAD.also { text = raw.removePrefix("@HEAD ") }
                raw.startsWith("@RET ") -> BlockZone.RETURN_ADDRESS.also { text = raw.removePrefix("@RET ") }
                else -> null
            }
            val top = 0.02f + 0.02f * r
            if (zone != null) zones[BlockKey(1, blocks.size)] = zone
            blocks.add(OcrBlock(text, TextBounds(0.6f, top, 0.9f, top + 0.015f), 0.9f))
        }
        return CandidateExtractor.extract(listOf(blocks), zones, null)
    }

    private fun names(set: CandidateSet) = set.ofKind(CandidateKind.NAME).map { it.normalized }

    /** The jobcenter letters' head: the sender, the return line, the addressee and the information block. */
    private fun jobcenter(contact: String, date: String) = arrayOf(
        "@HEAD Jobcenter Musterstadt", "@HEAD Musterstraße 1", "@HEAD 12345 Musterstadt",
        "@RET Jobcenter Musterstadt · Musterstraße 1 · 12345 Musterstadt",
        "@ADDR Maria Mustermann", "@ADDR Beispielweg 2", "@ADDR 12345 Musterstadt",
        "BG-Nummer", "12345BG0007777", "Ansprechpartnerin", contact, "Telefon", "0123 456-701", "E-Mail",
        "nadine.beispiel@jobcenter-musterstadt.example", "Datum", date,
    )

    @Test
    fun `the labels of the jobcenter letter's information block are no names, the contact person and the parties are`() {
        val names = names(letter(*jobcenter("Frau Nadine Beispiel", "01.09.2026")))
        assertThat(names).containsAtLeast("Frau Nadine Beispiel", "Maria Mustermann", "Jobcenter Musterstadt")
        for (label in listOf("BG-Nummer", "Ansprechpartnerin", "Telefon", "E-Mail", "Datum")) assertThat(names).doesNotContain(label)
    }

    @Test
    fun `the din letter's information block leaves its contact person and drops its labels`() {
        val names = names(
            letter(
                "@HEAD Stadtwerke Musterstadt", "@HEAD Energieweg 5", "@HEAD 12345 Musterstadt",
                "@RET Stadtwerke Musterstadt · Energieweg 5 · 12345 Musterstadt",
                "@ADDR Erika Beispielfrau", "@ADDR Beispielweg 7", "@ADDR 12345 Musterstadt",
                "Kundennummer", "KD-0000-4711", "Ansprechpartnerin", "Frau Ina Beispiel", "Telefon", "0123 456-789", "E-Mail",
                "kundenservice@stadtwerke-musterstadt.example", "Datum", "25.09.2026",
            ),
        )
        assertThat(names).containsAtLeast("Frau Ina Beispiel", "Erika Beispielfrau", "Stadtwerke Musterstadt")
        for (label in listOf("Kundennummer", "Ansprechpartnerin", "Telefon", "E-Mail", "Datum")) assertThat(names).doesNotContain(label)
    }

    @Test
    fun `the insurance letter with three dates has no contact label among its names`() {
        val names = names(
            letter(
                "@HEAD Beispiel Versicherung AG", "@HEAD Policenstraße 9", "@HEAD 12345 Musterstadt",
                "@RET Beispiel Versicherung AG · Policenstraße 9 · 12345 Musterstadt",
                "@ADDR Karl Mustermann", "@ADDR Beispielweg 2", "@ADDR 12345 Musterstadt",
                "Versicherungsschein-Nr.", "KFZ-0000-98765", "Ansprechpartner", "Herr Tom Beispiel", "Telefon", "0123 456-300", "Datum", "12.10.2026",
            ),
        )
        assertThat(names).containsAtLeast("Herr Tom Beispiel", "Karl Mustermann", "Beispiel Versicherung AG")
        for (label in listOf("Versicherungsschein-Nr.", "Ansprechpartner", "Telefon", "Datum")) assertThat(names).doesNotContain(label)
    }

    @Test
    fun `a stack of labels over plain values offers none of the labels`() {
        // The bill's meta block: every value is value-shaped.
        val names = names(
            letter(
                "@HEAD Elektro Beispiel GmbH", "@RET Elektro Beispiel GmbH · Handwerkerstraße 12 · 12345 Musterstadt",
                "@ADDR Lena Beispielfrau", "@ADDR Beispielweg 7", "@ADDR 12345 Musterstadt",
                "Rechnungsnummer", "RE-2026-0815", "Rechnungsdatum", "02.10.2026", "Kundennummer", "K-00042",
            ),
        )
        assertThat(names).containsAtLeast("Lena Beispielfrau", "Elektro Beispiel GmbH")
        for (label in listOf("Rechnungsnummer", "Rechnungsdatum", "Kundennummer")) assertThat(names).doesNotContain(label)
    }

    @Test
    fun `a name over its street over its postcode is an address block, not a label and its value`() {
        // The US letter has no address zone: the addressee is a plain stack, and one pair alone is no label/value layout.
        val names = names(
            letter("Example Insurance Inc.", "100 Sample Avenue, Suite 5", "Exampleville, ST 00000", "October 1, 2026", "John Q. Sample", "42 Placeholder Lane", "Exampleville, ST 00000"),
        )
        assertThat(names).containsAtLeast("Example Insurance Inc.", "John Q. Sample")
    }

    @Test
    fun `a label with its value on one line offers the value and never the label`() {
        val names = names(letter("Ansprechpartnerin: Frau Nadine Beispiel", "Telefon: 0123 456-701", "Datum: 01.09.2026", "Kundennummer: KD-0000-4711"))
        assertThat(names).containsExactly("Frau Nadine Beispiel")
    }

    @Test
    fun `the layout rule reads the parity of the confirmed labels, so a name value between two labels stays a value`() {
        val lines = listOf("BG-Nummer", "12345BG0007777", "Ansprechpartnerin", "Frau Nadine Beispiel", "Telefon", "0123 456-701", "Datum", "01.09.2026")
        val set = letter(*lines.toTypedArray())
        assertThat(names(set)).containsExactly("Frau Nadine Beispiel")
    }
}
