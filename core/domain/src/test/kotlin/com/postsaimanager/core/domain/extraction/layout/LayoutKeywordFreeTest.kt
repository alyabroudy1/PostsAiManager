package com.postsaimanager.core.domain.extraction.layout

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

/**
 * Zones come from geometry and shape only. The same page geometry with German words, with words of
 * an invented language, and with no words a program could know must give the same zones.
 */
class LayoutKeywordFreeTest {

    private class Words(
        val letterhead: List<String>,
        val returnLine: String,
        val address: List<String>,
        val info: List<Pair<String, String>>,
        val subject: String,
        val salutation: String,
        val body: List<String>,
        val footer: List<String>,
        val closing: String,
    )

    private fun b(text: String, x: Float, y: Float, w: Float? = null, h: Float = 0.014f): OcrBlock {
        val width = w ?: (text.lines().maxOf { it.length } * 0.0075f).coerceAtMost(0.95f - x)
        return OcrBlock(text, TextBounds(x, y - h / 2, x + width, y + h / 2), 0.9f)
    }

    private fun page(w: Words, smallReturnLine: Boolean = false): List<OcrBlock> {
        val out = mutableListOf<OcrBlock>()
        w.letterhead.forEachIndexed { i, t -> out += b(t, 0.89f - t.length * 0.0075f, 0.052f + 0.0115f * i) }
        out += b(w.returnLine, 0.114f, 0.157f, w = w.returnLine.length * 0.0045f, h = if (smallReturnLine) 0.009f else 0.014f)
        w.address.forEachIndexed { i, t -> out += b(t, 0.115f, 0.178f + 0.016f * i) }
        w.info.forEachIndexed { i, (label, value) ->
            out += b(label, 0.59f, 0.173f + 0.0145f * i, w = 0.09f)
            out += b(value, 0.70f, 0.173f + 0.0145f * i)
        }
        out += b(w.subject, 0.117f, 0.345f)
        out += b(w.salutation, 0.117f, 0.375f)
        var y = 0.398f
        for (t in w.body) {
            if (t.isNotEmpty()) out += b(t, 0.117f, y)
            y += 0.0165f
        }
        out += b(w.closing, 0.117f, y + 0.02f)
        w.footer.forEachIndexed { i, t -> out += b(t, 0.11f + 0.27f * (i % 3), 0.911f + 0.0105f * (i / 3), w = 0.22f) }
        return out
    }

    private val german = Words(
        letterhead = listOf("Nordlicht Mobilfunk GmbH", "Beispielweg 7", "12345 Beispielstadt", "Tel. 0800 555 0100"),
        returnLine = "Nordlicht Mobilfunk GmbH · Beispielweg 7 · 12345 Beispielstadt",
        address = listOf("Frau", "Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
        info = listOf("Ihr Zeichen:" to "FIB 4402917", "Kundennummer:" to "4402917", "Datum:" to "25.09.2026"),
        subject = "Zahlungserinnerung Rechnung 2026-08-771204",
        salutation = "Sehr geehrte Frau Mustermann,",
        body = listOf(
            "zu unserer Rechnung konnten wir keinen Zahlungseingang feststellen.",
            "",
            "Nettobetrag                     100,00 EUR",
            "MwSt 19 %                        19,00 EUR",
            "Gesamtbetrag                    119,00 EUR",
            "",
            "Bitte überweisen Sie bis 09.10.2026 auf IBAN DE89 3704 0044 0532 0130 00",
            "",
            "Weitere Angaben finden Sie im Kundenportal.",
        ),
        footer = listOf("Nordlicht Mobilfunk GmbH", "Bankverbindung: Beispielbank", "Geschäftsführer: Nora Beispiel"),
        closing = "Mit freundlichen Grüßen",
    )

    /** An invented language: no word of it is known to the code, and nothing is labelled with a colon. */
    private val invented = Words(
        letterhead = listOf("Zorbak Velum Kesta", "Quilo 7", "12345 Marnu", "Fol 0800 555 0100"),
        returnLine = "Zorbak Velum Kesta · Quilo 7 · 12345 Marnu",
        address = listOf("Tevi", "Ulmo Rasta", "Brenda 12", "54321 Ostrel"),
        info = listOf("Sef ulu" to "FIB 4402917", "Dorna ulu" to "4402917", "Kaz" to "25.09.2026"),
        subject = "Melka trono vasi 2026-08-771204",
        salutation = "Lira tevi Rasta",
        body = listOf(
            "fen molo tasi kreva lun fedo miga sora pelu tanda kero.",
            "",
            "Alo                             100,00 EUR",
            "Bes 19 %                         19,00 EUR",
            "Sum                             119,00 EUR",
            "",
            "Sopa mek 09.10.2026 lun IBAN DE89 3704 0044 0532 0130 00",
            "",
            "Reda fon tuli kem nora pan.",
        ),
        footer = listOf("Zorbak Velum Kesta", "Gron Pleta", "Vaso Tunk"),
        closing = "Fanu moro",
    )

    /** Every word replaced by filler: only the shapes (digits, separators, punctuation) are left. */
    private val bare = Words(
        letterhead = listOf("aaaa bbbb cccc", "dd 7", "12345 eeee", "ff 0800 555 0100"),
        returnLine = "aaaa bbbb cccc · dd 7 · 12345 eeee",
        address = listOf("gg", "hhhh iiiii", "jjjjjj 12", "54321 kkkkk"),
        info = listOf("ll mm" to "FIB 4402917", "nn mm" to "4402917", "oo" to "25.09.2026"),
        subject = "pppp qqqq rrrr 2026-08-771204",
        salutation = "ssss tttt uuuuu",
        body = listOf(
            "vvv www xxx yyy zzz vvv www xxx yyy zzz vvv.",
            "",
            "aa                              100,00 EUR",
            "bb 19 %                          19,00 EUR",
            "cc                              119,00 EUR",
            "",
            "dd ee 09.10.2026 ff IBAN DE89 3704 0044 0532 0130 00",
            "",
            "gg hh ii jj kk ll.",
        ),
        footer = listOf("aaaa bbbb cccc", "dddd eeee", "ffff gggg"),
        closing = "hhhh iiii",
    )

    /** The zone of each row, by the row's top (rounded); the words and the x positions of the twins differ. */
    private fun zonesByRow(blocks: List<OcrBlock>): List<Pair<Int, LetterZone>> =
        LetterLayoutAnalyzer.analyze(listOf(blocks)).allLines
            .map { Math.round(it.bounds.top * 1000) to it.zone }
            .sortedWith(compareBy({ it.first }, { it.second.ordinal }))

    @Test
    fun `German words, an invented language and filler give identical zones`() {
        val g = zonesByRow(page(german))
        assertThat(zonesByRow(page(invented))).isEqualTo(g)
        assertThat(zonesByRow(page(bare))).isEqualTo(g)
    }

    @Test
    fun `the keyword-free letter has every zone the German one has`() {
        val zones = zonesByRow(page(invented)).map { it.second }.toSet()
        assertThat(zones).containsAtLeast(
            LetterZone.LETTERHEAD, LetterZone.RETURN_ADDRESS_LINE, LetterZone.ADDRESS_FIELD, LetterZone.INFO_BLOCK,
            LetterZone.SUBJECT, LetterZone.BODY, LetterZone.PAYMENT_SECTION, LetterZone.FOOTER,
        )
    }

    @Test
    fun `the subject is the line that stands apart, not the greeting, in any words`() {
        for (w in listOf(german, invented, bare)) {
            val l = LetterLayoutAnalyzer.analyze(listOf(page(w)))
            assertThat(l.zone(LetterZone.SUBJECT).map { it.text }).containsExactly(w.subject)
            assertThat(l.zone(LetterZone.ADDRESS_FIELD).map { it.text }).containsExactlyElementsIn(w.address).inOrder()
        }
    }

    @Test
    fun `an account number and a sum that adds up make the payment section, words or none`() {
        for (w in listOf(german, invented, bare)) {
            val l = LetterLayoutAnalyzer.analyze(listOf(page(w)))
            val payment = l.zone(LetterZone.PAYMENT_SECTION).joinToString("\n") { it.text }
            assertThat(payment).contains("IBAN DE89 3704 0044 0532 0130 00")
            assertThat(payment).contains("119,00 EUR")
            assertThat(payment).contains("100,00 EUR")
            assertThat(payment).contains("09.10.2026")
        }
    }

    @Test
    fun `a page with amounts but no account number and no sum that adds up has no payment section`() {
        val w = Words(
            letterhead = german.letterhead, returnLine = german.returnLine, address = german.address, info = german.info,
            subject = german.subject, salutation = german.salutation,
            body = List(8) { "Posten $it            ${it * 7 + 3},50 EUR" }, footer = german.footer, closing = german.closing,
        )
        val l = LetterLayoutAnalyzer.analyze(listOf(page(w)))
        assertThat(l.zone(LetterZone.PAYMENT_SECTION)).isEmpty()
    }

    @Test
    fun `a return-address line whose separators OCR dropped is found by its small font and digit run`() {
        val fused = german.returnLine.replace(" · ", " ").replace("Beispielweg 7", "Beispielweg 512345")
        val w = Words(
            german.letterhead, fused, german.address, german.info, german.subject, german.salutation, german.body, german.footer, german.closing,
        )
        val l = LetterLayoutAnalyzer.analyze(listOf(page(w, smallReturnLine = true)))
        assertThat(l.zone(LetterZone.RETURN_ADDRESS_LINE).map { it.text }).containsExactly(fused)
        assertThat(l.zone(LetterZone.ADDRESS_FIELD).map { it.text }).containsExactlyElementsIn(german.address).inOrder()
        assertThat(l.zone(LetterZone.ADDRESS_FIELD).none { it.text == fused }).isTrue()
    }

    @Test
    fun `a fused first line with its own postcode is the return line even at the normal font size`() {
        val fused = german.returnLine.replace(" · ", " ").replace("Beispielweg 7", "Beispielweg 512345")
        val w = Words(
            german.letterhead, fused, german.address, german.info, german.subject, german.salutation, german.body, german.footer, german.closing,
        )
        val l = LetterLayoutAnalyzer.analyze(listOf(page(w, smallReturnLine = false)))
        assertThat(l.zone(LetterZone.RETURN_ADDRESS_LINE).map { it.text }).containsExactly(fused)
        assertThat(l.zone(LetterZone.ADDRESS_FIELD).map { it.text }).containsExactlyElementsIn(german.address).inOrder()
    }

    @Test
    fun `without a return line the company line above a routing line stays in the address field`() {
        val address = listOf("Mustermann Consulting GmbH", "z. Hd. Frau Erika Mustermann", "Gewerbering 4", "54321 Beispieldorf")
        val out = mutableListOf<OcrBlock>()
        out += b("Beispiel KG", 0.11f, 0.05f)
        address.forEachIndexed { i, t -> out += b(t, 0.115f, 0.178f + 0.016f * i) }
        out += b("Datum 23.09.2026", 0.70f, 0.178f)
        out += b("Wartungsvertrag verlängert", 0.117f, 0.345f)
        out += b("Sehr geehrte Frau Mustermann,", 0.117f, 0.375f)
        val l = LetterLayoutAnalyzer.analyze(listOf(out))
        assertThat(l.zone(LetterZone.ADDRESS_FIELD).map { it.text }).containsExactlyElementsIn(address).inOrder()
        assertThat(l.zone(LetterZone.LETTERHEAD).map { it.text }).containsExactly("Beispiel KG")
    }

    @Test
    fun `small print low on the page is footer by its font size, whatever it says`() {
        val out = mutableListOf<OcrBlock>()
        for (i in 0 until 20) out += b("Lorem ipsum dolor sit amet consectetur $i", 0.117f, 0.40f + 0.02f * i)
        out += b("xx yy zz", 0.117f, 0.815f, h = 0.008f)
        out += b("qq rr ss", 0.117f, 0.828f, h = 0.008f)
        val l = LetterLayoutAnalyzer.analyze(listOf(out))
        assertThat(l.zone(LetterZone.FOOTER).map { it.text }).containsAtLeast("xx yy zz", "qq rr ss")
    }

    @Test
    fun `a word that looks like a footer or payment keyword does not change a zone`() {
        val out = mutableListOf<OcrBlock>()
        out += b("Geschäftsführer und IBAN und Betrag und fällig", 0.117f, 0.80f)
        out += b("Amtsgericht Zahlung Überweisung Frist Einspruch", 0.117f, 0.60f)
        val l = LetterLayoutAnalyzer.analyze(listOf(out))
        assertThat(l.zone(LetterZone.FOOTER)).isEmpty()
        assertThat(l.zone(LetterZone.PAYMENT_SECTION)).isEmpty()
    }
}
