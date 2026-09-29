package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [LetterLayoutAnalyzer] and [LetterLayout].
 *
 * The fixtures mimic the geometry of the synthetic test letters (testdocs / testdocs2): positions are
 * fractions of the page, estimated from the rendered pages (N1 and N4 measured, the others follow the
 * same DIN 5008 template with their own address / sender / page structure). The wording is shortened.
 */
class LetterLayoutTest {

    // ── Fixture helpers ──

    private fun b(text: String, x: Float, y: Float, conf: Float = 0.9f, w: Float? = null): OcrBlock {
        val width = w ?: (text.lines().maxOf { it.length } * 0.0075f).coerceAtMost(0.95f - x)
        val lines = text.lines().size
        return OcrBlock(text, TextBounds(x, y - 0.007f, x + width, y + 0.007f + 0.016f * (lines - 1)), conf)
    }

    private class Sender(
        val letterhead: List<String>,
        val returnLine: String,
        val right: Boolean = true,
        val footer: List<String>,
    )

    private fun firstPage(
        sender: Sender,
        address: List<String>,
        addressAsOneBlock: Boolean = false,
        info: List<Pair<String, String>>,
        subject: String,
        salutation: String,
        body: List<String>,
        totalPages: Int = 1,
        withReturnLine: Boolean = true,
    ): List<OcrBlock> {
        val out = mutableListOf<OcrBlock>()
        sender.letterhead.forEachIndexed { i, t ->
            val x = if (sender.right) 0.89f - t.length * 0.0075f else 0.11f
            out += b(t, x, 0.052f + 0.0115f * i)
        }
        if (withReturnLine) out += b(sender.returnLine, 0.114f, 0.157f, w = sender.returnLine.length * 0.0045f)
        if (addressAsOneBlock) {
            out += b(address.joinToString("\n"), 0.115f, 0.178f, w = 0.3f)
        } else {
            address.forEachIndexed { i, t -> out += b(t, 0.115f, 0.178f + 0.016f * i) }
        }
        info.forEachIndexed { i, (label, value) ->
            out += b(label, 0.59f, 0.173f + 0.0145f * i, w = 0.09f)
            out += b(value, 0.70f, 0.173f + 0.0145f * i)
        }
        out += b(subject, 0.117f, 0.345f)
        out += b(salutation, 0.117f, 0.375f)
        var y = 0.398f
        for (t in body) {
            if (t.isNotEmpty()) out += b(t, 0.117f, y)
            y += 0.0165f
        }
        out += footer(sender, 1, totalPages)
        return out
    }

    private fun footer(sender: Sender, page: Int, total: Int): List<OcrBlock> {
        val out = sender.footer.mapIndexed { i, t -> b(t, 0.11f + 0.27f * (i % 3), 0.911f + 0.0105f * (i / 3), w = 0.22f) }
        return out + b("Seite $page von $total", 0.84f, 0.96f)
    }

    private fun continuation(sender: Sender, page: Int, total: Int, header: String, body: List<String>): List<OcrBlock> {
        val out = mutableListOf(b(header, 0.11f, 0.05f))
        var y = 0.12f
        for (t in body) {
            if (t.isNotEmpty()) out += b(t, 0.117f, y)
            y += 0.0165f
        }
        return out + footer(sender, page, total)
    }

    private fun LetterLayout.texts(zone: LetterZone, page: Int = 1) = zone(zone, page).map { it.text }

    private val nordlicht = Sender(
        letterhead = listOf("Nordlicht Mobilfunk GmbH", "Beispielweg 7", "12345 Beispielstadt", "Tel. 0800 555 0100", "service@nordlicht-mobil.example"),
        returnLine = "Nordlicht Mobilfunk GmbH · Beispielweg 7 · 12345 Beispielstadt",
        footer = listOf(
            "Nordlicht Mobilfunk GmbH", "Bankverbindung: Beispielbank", "Tel. 0800 555 0100",
            "Geschäftsführer: Nora Beispiel, Tim Muster", "IBAN DE02 1203 0000 0000 2020 51", "service@nordlicht-mobil.example",
        ),
    )

    private val n1: List<OcrBlock> = firstPage(
        nordlicht,
        address = listOf("Frau", "Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
        info = listOf(
            "Ihr Zeichen:" to "—", "Unser Zeichen:" to "FIB-Mahn 4402917", "Kundennummer:" to "4402917",
            "Ansprechpartner:" to "Team Forderungen", "Telefon:" to "0800 555 0199", "Datum:" to "25.09.2026",
        ),
        subject = "Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204",
        salutation = "Sehr geehrte Frau Mustermann,",
        body = listOf(
            "zu unserer Rechnung 2026-08-771204 vom 05.08.2026 konnten wir bis heute keinen",
            "Zahlungseingang feststellen. Die Rechnung war am 19.08.2026 fällig.",
            "",
            "Rechnung 2026-08-771204 (Mobilfunk Juli 2026)          05.08.2026",
            "Mahngebühr",
            "Offener Gesamtbetrag                                   64,98",
            "Bitte überweisen Sie den offenen Betrag von 64,98 € zahlbar innerhalb von 14 Tagen",
            "nach Zugang dieses Schreibens auf das unten genannte Konto.",
            "",
            "Kontoinhaber: Nordlicht Mobilfunk GmbH",
            "IBAN: DE02 1203 0000 0000 2020 51",
            "BIC: COBADEFFXXX",
            "Verwendungszweck: RE 2026-08-771204 Kd 4402917",
            "",
            "Mit freundlichen Grüßen",
            "Nordlicht Mobilfunk GmbH",
            "Forderungsmanagement",
        ),
    )

    private val buero = Sender(
        letterhead = listOf("Büro-Partner Beispiel KG", "Industriestraße 40", "12345 Beispielstadt", "Tel. 01234 888 0"),
        returnLine = "Büro-Partner Beispiel KG · Industriestraße 40 · 12345 Beispielstadt",
        footer = listOf(
            "Büro-Partner Beispiel KG", "Bankverbindung: Beispielbank", "Tel. 01234 888 0",
            "Persönlich haftende Gesellschafterin: BPB Verwaltungs GmbH", "IBAN DE89 3704 0044 0532 0130 00", "AG Beispielstadt HRA 000000",
        ),
    )

    private val n4: List<OcrBlock> = firstPage(
        buero,
        address = listOf("Mustermann Consulting GmbH", "z. Hd. Frau Erika Mustermann", "Gewerbering 4", "54321 Beispieldorf"),
        info = listOf("Ihr Zeichen:" to "EM/2026", "Unser Zeichen:" to "WV-2291", "Ansprechpartner:" to "Herr T. Muster", "Telefon:" to "01234 888 44", "Datum:" to "23.09.2026"),
        subject = "Wartungsvertrag WV-2291 – Verlängerung ab 01.01.2027",
        salutation = "Sehr geehrte Frau Mustermann,",
        body = listOf(
            "Ihr Wartungsvertrag WV-2291 für die Multifunktionsgeräte läuft zum 31.12.2026 aus.",
            "",
            "Wartung 2 Geräte inkl. Toner-Service                   1.428,00 €",
            "",
            "Bitte bestätigen Sie uns die Verlängerung bis 15.10.2026 durch Rücksendung.",
            "",
            "Mit freundlichen Grüßen",
            "T. Muster, Vertrieb",
        ),
    )

    private val hochschule = Sender(
        letterhead = listOf("Universität Beispielstadt", "Studierendenservice", "Universitätsallee 1, 12345 Beispielstadt"),
        returnLine = "Universität Beispielstadt, Universitätsallee 1, 12345 Beispielstadt",
        right = false,
        footer = listOf("Universität Beispielstadt", "Bankverbindung: Beispielbank", "Tel. 01234 999 0", "IBAN DE89 3704 0044 0532 0130 00"),
    )

    private val n5: List<OcrBlock> = firstPage(
        hochschule,
        address = listOf("Herrn", "Jonas Mustermann", "c/o Familie Beispiel", "Hauptstraße 3", "54321 Beispieldorf"),
        info = listOf("Ihr Zeichen:" to "—", "Matrikelnummer:" to "1234567", "Datum:" to "22.09.2026"),
        subject = "Rückmeldung Wintersemester 2026/27",
        salutation = "Sehr geehrter Herr Mustermann,",
        body = listOf(
            "der Semesterbeitrag von 187,50 EUR ist bis zum 12.10.2026 zu überweisen.",
            "",
            "Mit freundlichen Grüßen",
        ),
    )

    private val versicherer = Sender(
        letterhead = listOf("Beispiel Versicherung AG", "Versicherungsplatz 1", "12345 Beispielstadt"),
        returnLine = "Beispiel Versicherung AG · Versicherungsplatz 1 · 12345 Beispielstadt",
        footer = listOf(
            "Beispiel Versicherung AG", "Bankverbindung: Beispielbank", "Tel. 0800 123 45",
            "Vorsitzender des Aufsichtsrats: A. Beispiel", "Amtsgericht Beispielstadt HRB 000000", "IBAN DE89 3704 0044 0532 0130 00",
        ),
    )

    private val n2: List<List<OcrBlock>> = listOf(
        firstPage(
            versicherer,
            address = listOf("Herrn und Frau", "Max und Erika Mustermann", "Musterweg 5", "54321 Beispieldorf"),
            addressAsOneBlock = true,
            info = listOf("Versicherungsnummer:" to "KFZ-778812", "Ansprechpartner:" to "Frau Beispiel", "Datum:" to "24.09.2026"),
            subject = "Ihre Kfz-Versicherung: Anpassung zum 01.01.2027",
            salutation = "Sehr geehrte Frau Mustermann, sehr geehrter Herr Mustermann,",
            body = listOf("zum Jahreswechsel passen wir Ihren Vertrag an.", "", "Bisher 579,10 EUR, künftig 612,40 EUR."),
            totalPages = 2,
        ),
        continuation(
            versicherer, 2, 2, "Versicherungsnummer KFZ-778812",
            listOf(
                "Sie haben ein Sonderkündigungsrecht bis zum 30.11.2026.",
                "",
                "Mit freundlichen Grüßen",
            ),
        ),
    )

    private val hausverwaltung = Sender(
        letterhead = listOf("Hausverwaltung Beispiel GmbH", "Immobilienweg 9", "12345 Beispielstadt"),
        returnLine = "Hausverwaltung Beispiel GmbH · Immobilienweg 9 · 12345 Beispielstadt",
        footer = listOf(
            "Hausverwaltung Beispiel GmbH", "Bankverbindung: Beispielbank", "Tel. 01234 777 0",
            "Geschäftsführer: H. Beispiel", "IBAN DE89 3704 0044 0532 0130 00", "AG Beispielstadt HRB 000001",
        ),
    )

    private val n6: List<List<OcrBlock>> = listOf(
        firstPage(
            hausverwaltung,
            address = listOf("Herrn", "Jonas Mustermann", "Lindenallee 8", "54321 Beispieldorf"),
            info = listOf("Objekt:" to "Lindenallee 8", "Ansprechpartner:" to "Frau Verwalter", "Datum:" to "20.09.2026"),
            subject = "Nebenkostenabrechnung 2025",
            salutation = "Sehr geehrter Herr Mustermann,",
            body = List(24) { "Kostenart $it, Verteilung nach Wohnfläche, Anteil ${it * 3},00 m²" },
            totalPages = 3,
        ),
        continuation(
            hausverwaltung, 2, 3, "Nebenkostenabrechnung 2025",
            List(30) { "Kostenart ${it + 24}, Verteilung nach Personen, Anteil $it,00 Anteile" },
        ),
        continuation(
            hausverwaltung, 3, 3, "Nebenkostenabrechnung 2025",
            listOf(
                "Ergebnis der Abrechnung",
                "Nachzahlung: 186,32 EUR",
                "Bitte überweisen Sie den Betrag bis zum 31.10.2026 auf das Konto",
                "IBAN DE89 3704 0044 0532 0130 00",
                "",
                "Ab 01.11.2026 beträgt Ihre Vorauszahlung 245,00 EUR.",
                "",
                "Mit freundlichen Grüßen",
            ),
        ),
    )

    private val rechnungsfirma = Sender(
        letterhead = listOf("Werkstatt Beispiel GmbH", "Werkstattstraße 2", "12345 Beispielstadt"),
        returnLine = "Werkstatt Beispiel GmbH · Werkstattstraße 2 · 12345 Beispielstadt",
        footer = listOf(
            "Werkstatt Beispiel GmbH", "Bankverbindung: Beispielbank", "Tel. 01234 111 0",
            "Geschäftsführer: W. Beispiel", "IBAN DE89 3704 0044 0532 0130 00", "AG Beispielstadt HRB 000002",
        ),
    )

    private val invoice: List<List<OcrBlock>> = listOf(
        firstPage(
            rechnungsfirma,
            address = listOf("Frau", "Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
            info = listOf("Kundennummer:" to "10442", "Rechnungsnummer:" to "2026-1042", "Datum:" to "18.09.2026"),
            subject = "Rechnung 2026-1042",
            salutation = "Sehr geehrte Frau Mustermann,",
            body = List(28) { "Position $it   Ersatzteil ${it}x   ${it * 7},50 EUR" },
            totalPages = 2,
        ),
        continuation(
            rechnungsfirma, 2, 2, "Rechnung 2026-1042",
            List(6) { "Position ${it + 28}   Arbeitszeit   ${it * 9},00 EUR" } + listOf(
                "",
                "Summe netto                                     412,00 EUR",
                "MwSt 19 %                                        78,28 EUR",
                "Gesamtbetrag                                    490,28 EUR",
                "Zahlbar bis 02.10.2026 auf IBAN DE89 3704 0044 0532 0130 00",
            ),
        ),
    )

    private val finanzamt = Sender(
        letterhead = listOf("Finanzamt Beispielstadt", "Amtsplatz 1", "12345 Beispielstadt"),
        returnLine = "Finanzamt Beispielstadt · Amtsplatz 1 · 12345 Beispielstadt",
        footer = listOf(
            "Finanzamt Beispielstadt", "Bankverbindung: Landeskasse", "Tel. 01234 555 0",
            "Öffnungszeiten: Mo-Fr 8-12 Uhr", "IBAN DE89 3704 0044 0532 0130 00", "www.finanzamt.example",
        ),
    )

    private val taxHeader = "Aktenzeichen 123/456/78901"

    private fun filler(page: Int) = List(38) { "Absatz $page.$it: Die Festsetzung beruht auf den Angaben in Ihrer Erklärung und den Anlagen." }

    private val tax: List<List<OcrBlock>> = listOf(
        firstPage(
            finanzamt,
            address = listOf("Herrn", "Jonas Mustermann", "Musterweg 5", "54321 Beispieldorf"),
            info = listOf("Aktenzeichen:" to "123/456/78901", "Datum:" to "15.09.2026"),
            subject = "Einkommensteuerbescheid 2025",
            salutation = "Sehr geehrter Herr Mustermann,",
            body = listOf("es ergibt sich eine Nachzahlung von 1.284,00 EUR.") + filler(1).take(30),
            totalPages = 7,
        ),
    ) + (2..5).map { p ->
        continuation(finanzamt, p, 7, "$taxHeader Seite $p von 7", filler(p))
    } + listOf(
        continuation(
            finanzamt, 6, 7, "$taxHeader Seite 6 von 7",
            filler(6).take(10) + listOf(
                "Rechtsbehelfsbelehrung",
                "Gegen diesen Bescheid kann innerhalb eines Monats Einspruch eingelegt werden.",
                "Die Nachzahlung ist fällig am 03.11.2026.",
                "Bitte überweisen Sie auf IBAN DE89 3704 0044 0532 0130 00",
            ) + filler(66).take(10),
        ),
        continuation(finanzamt, 7, 7, "$taxHeader Seite 7 von 7", listOf("Mit freundlichen Grüßen", "Finanzamt Beispielstadt")),
    )

    private val allLetters: Map<String, List<List<OcrBlock>>> = mapOf(
        "N1" to listOf(n1), "N2" to n2, "N4" to listOf(n4), "N5" to listOf(n5),
        "N6" to n6, "invoice-2p" to invoice, "tax-long-7p" to tax,
    )

    private val senders = mapOf(
        "N1" to "Nordlicht", "N2" to "Beispiel Versicherung", "N4" to "Büro-Partner", "N5" to "Universität",
        "N6" to "Hausverwaltung", "invoice-2p" to "Werkstatt", "tax-long-7p" to "Finanzamt",
    )

    private fun analyze(name: String) = LetterLayoutAnalyzer.analyze(allLetters.getValue(name))

    // ── Tests ──

    @Nested
    @DisplayName("Zones on the test letters")
    inner class Zones {

        @Test
        fun `N1 separates letterhead, return line, address, info, subject, payment and footer`() {
            val l = analyze("N1")
            assertThat(l.texts(LetterZone.LETTERHEAD)).contains("Nordlicht Mobilfunk GmbH")
            assertThat(l.texts(LetterZone.RETURN_ADDRESS_LINE)).containsExactly(
                "Nordlicht Mobilfunk GmbH · Beispielweg 7 · 12345 Beispielstadt",
            )
            assertThat(l.texts(LetterZone.ADDRESS_FIELD))
                .containsExactly("Frau", "Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf").inOrder()
            assertThat(l.texts(LetterZone.INFO_BLOCK).joinToString(" ")).contains("Kundennummer:")
            assertThat(l.texts(LetterZone.INFO_BLOCK).joinToString(" ")).contains("25.09.2026")
            assertThat(l.texts(LetterZone.SUBJECT)).containsExactly("Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204")
            assertThat(l.texts(LetterZone.PAYMENT_SECTION).joinToString("\n")).contains("IBAN: DE02 1203 0000 0000 2020 51")
            assertThat(l.texts(LetterZone.PAYMENT_SECTION).joinToString("\n")).contains("Offener Gesamtbetrag")
            assertThat(l.texts(LetterZone.FOOTER).joinToString("\n")).contains("Geschäftsführer")
            // The letterhead's own phone line is not mistaken for the info block.
            assertThat(l.texts(LetterZone.INFO_BLOCK)).doesNotContain("Tel. 0800 555 0100")
        }

        @Test
        fun `N2 keeps Herrn und Frau as one address field in a single multi-line block`() {
            val l = analyze("N2")
            assertThat(l.texts(LetterZone.ADDRESS_FIELD))
                .containsExactly("Herrn und Frau", "Max und Erika Mustermann", "Musterweg 5", "54321 Beispieldorf").inOrder()
            assertThat(l.texts(LetterZone.RETURN_ADDRESS_LINE)).hasSize(1)
            assertThat(l.texts(LetterZone.SUBJECT)).hasSize(1)
        }

        @Test
        fun `N4 z Hd stays inside the address field`() {
            val l = analyze("N4")
            assertThat(l.texts(LetterZone.ADDRESS_FIELD)).containsExactly(
                "Mustermann Consulting GmbH", "z. Hd. Frau Erika Mustermann", "Gewerbering 4", "54321 Beispieldorf",
            ).inOrder()
            assertThat(l.texts(LetterZone.FOOTER).joinToString("\n")).contains("Persönlich haftende")
        }

        @Test
        fun `N5 c o is part of the address field and a top-left letterhead is still the letterhead`() {
            val l = analyze("N5")
            assertThat(l.texts(LetterZone.ADDRESS_FIELD)).containsExactly(
                "Herrn", "Jonas Mustermann", "c/o Familie Beispiel", "Hauptstraße 3", "54321 Beispieldorf",
            ).inOrder()
            assertThat(l.texts(LetterZone.LETTERHEAD)).contains("Universität Beispielstadt")
            assertThat(l.texts(LetterZone.RETURN_ADDRESS_LINE)).hasSize(1)
        }

        @Test
        fun `N6 payment is on page 3`() {
            val l = analyze("N6")
            val p3 = l.texts(LetterZone.PAYMENT_SECTION, 3).joinToString("\n")
            assertThat(p3).contains("Nachzahlung: 186,32 EUR")
            assertThat(p3).contains("31.10.2026")
            assertThat(p3).contains("Vorauszahlung 245,00 EUR")
            assertThat(l.texts(LetterZone.PAYMENT_SECTION, 2)).isEmpty()
        }

        @Test
        fun `invoice payment is on page 2`() {
            val l = analyze("invoice-2p")
            val p2 = l.texts(LetterZone.PAYMENT_SECTION, 2).joinToString("\n")
            assertThat(p2).contains("Gesamtbetrag")
            assertThat(p2).contains("Zahlbar bis 02.10.2026")
            assertThat(l.texts(LetterZone.PAYMENT_SECTION, 1)).isEmpty()
        }

        @Test
        fun `without a return address line the address field is found by its PLZ Ort line`() {
            val blocks = firstPage(
                nordlicht,
                address = listOf("Frau", "Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                info = listOf("Datum:" to "25.09.2026"),
                subject = "Zahlungserinnerung",
                salutation = "Sehr geehrte Frau Mustermann,",
                body = listOf("Text"),
                withReturnLine = false,
            )
            val l = LetterLayoutAnalyzer.analyze(listOf(blocks))
            assertThat(l.texts(LetterZone.RETURN_ADDRESS_LINE)).isEmpty()
            assertThat(l.texts(LetterZone.ADDRESS_FIELD))
                .containsExactly("Frau", "Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf").inOrder()
            assertThat(l.texts(LetterZone.LETTERHEAD)).contains("Nordlicht Mobilfunk GmbH")
        }

        @Test
        fun `a page with nothing recognisable falls back to the fixed-fraction prior`() {
            val blocks = listOf(
                b("Jobcenter Berlin Mitte", 0.08f, 0.065f),
                b("Frau\nAylin Mustermann", 0.08f, 0.20f, w = 0.3f),
                b("Aktenzeichen: BG 1234/5678", 0.60f, 0.20f),
                b("Ihre Ehefrau ist ebenfalls betroffen.", 0.08f, 0.55f),
            )
            val l = LetterLayoutAnalyzer.analyze(listOf(blocks))
            assertThat(l.texts(LetterZone.LETTERHEAD)).containsExactly("Jobcenter Berlin Mitte")
            assertThat(l.texts(LetterZone.ADDRESS_FIELD)).containsExactly("Frau", "Aylin Mustermann").inOrder()
            assertThat(l.texts(LetterZone.INFO_BLOCK)).containsExactly("Aktenzeichen: BG 1234/5678")
        }
    }

    @Nested
    @DisplayName("Sender is never the addressee")
    inner class SenderNotAddressee {

        @Test
        fun `the address field never contains the letterhead or the return address line`() {
            for ((name, sender) in senders) {
                val l = analyze(name)
                val address = l.texts(LetterZone.ADDRESS_FIELD)
                assertThat(address).isNotEmpty()
                assertThat(address.none { it.contains(sender) }).isTrue()
                assertThat(address.none { it.contains("·") }).isTrue()
                assertThat(l.texts(LetterZone.RETURN_ADDRESS_LINE).joinToString()).contains(sender)
            }
        }
    }

    @Nested
    @DisplayName("Pages")
    inner class Pages {

        @Test
        fun `pages never interleave in the layout or in the description`() {
            for (name in listOf("N2", "N6", "invoice-2p", "tax-long-7p")) {
                val l = analyze(name)
                l.pages.forEach { p -> assertThat(p.lines.all { it.page == p.pageNumber }).isTrue() }
                val text = l.describe().text
                val headers = (1..l.pages.size).map { text.indexOf("=== PAGE $it ===") }
                assertThat(headers.all { it >= 0 }).isTrue()
                assertThat(headers).isInStrictOrder()
            }
        }

        @Test
        fun `page 2 lines at the same height as page 1 lines stay on page 2`() {
            val l = analyze("N6")
            val text = l.describe().text
            val p2 = text.indexOf("=== PAGE 2 ===")
            assertThat(text.indexOf("Kostenart 23,")).isLessThan(p2)
            assertThat(text.indexOf("Kostenart 24,")).isGreaterThan(p2)
        }

        @Test
        fun `flat blocks with page counts give the same layout as per-page lists`() {
            val flat = LetterLayoutAnalyzer.analyze(n6.flatten(), n6.map { it.size })
            assertThat(flat.pages.map { it.lines.map { l -> l.text } })
                .isEqualTo(analyze("N6").pages.map { it.lines.map { l -> l.text } })
        }

        @Test
        fun `unknown page boundaries are treated as one page`() {
            val flat = LetterLayoutAnalyzer.analyze(n1, emptyList())
            assertThat(flat.pages).hasSize(1)
        }

        @Test
        fun `running headers repeated on later pages are flagged and the first copy kept`() {
            val l = analyze("tax-long-7p")
            val headerLines = l.allLines.filter { it.text.startsWith(taxHeader) }
            assertThat(headerLines).hasSize(6)
            assertThat(headerLines.all { it.noise == NoiseKind.REPEATED }).isFalse()
            assertThat(headerLines.first().isNoise).isFalse()
            assertThat(headerLines.drop(1).all { it.noise == NoiseKind.REPEATED }).isTrue()
        }
    }

    @Nested
    @DisplayName("Budget")
    inner class Budget {

        @Test
        fun `tax-long-7p keeps the page 6 Einspruch and fällig lines under a small budget`() {
            val l = analyze("tax-long-7p")
            val full = l.describe()
            val d = l.describe(3000)

            assertThat(full.text.length).isGreaterThan(10_000)
            assertThat(d.text.length).isAtMost(3000)
            assertThat(d.text).contains("Einspruch eingelegt werden")
            assertThat(d.text).contains("fällig am 03.11.2026")
            assertThat(d.text).contains("IBAN DE89 3704 0044 0532 0130 00")
            // Page 1 head survives too.
            assertThat(d.text).contains("[address-field] Herrn / Jonas Mustermann")
            assertThat(d.text).contains("[subject] Einkommensteuerbescheid 2025")
            // And the notice can say what was not read.
            assertThat(d.isComplete).isFalse()
            assertThat(d.omittedSummary()).contains("body")
            assertThat(d.omitted.any { it.page in 2..5 && it.zone == LetterZone.BODY }).isTrue()
        }

        @Test
        fun `N6 payment on page 3 survives although page 1 has a long body`() {
            val d = analyze("N6").describe(1100)
            assertThat(d.text.length).isAtMost(1100)
            assertThat(d.text).contains("Nachzahlung: 186,32 EUR")
            assertThat(d.text).contains("31.10.2026")
            assertThat(d.text).contains("[address-field] Herrn / Jonas Mustermann")
            assertThat(d.text).doesNotContain("Kostenart 5,")
            assertThat(d.omitted.any { it.page == 1 && it.zone == LetterZone.BODY }).isTrue()
        }

        @Test
        fun `invoice payment on page 2 survives`() {
            val d = analyze("invoice-2p").describe(1500)
            assertThat(d.text).contains("Gesamtbetrag")
            assertThat(d.text).contains("Zahlbar bis 02.10.2026")
            assertThat(d.omitted.map { it.page }).contains(1)
        }

        @Test
        fun `priority is page-1 head, then payment, then footer`() {
            val l = analyze("N1")
            val head = l.describe(0).text
            assertThat(head).isEmpty()
            // Just enough for the head and payment, not for the footer or the rest of the body.
            val d = l.describe(1000)
            assertThat(d.text).contains("[letterhead]")
            assertThat(d.text).contains("[address-field]")
            assertThat(d.text).contains("[payment]")
            assertThat(d.isComplete).isFalse()
        }

        @Test
        fun `a large budget describes everything, compactly and without coordinates`() {
            val d = analyze("N1").describe()
            assertThat(d.isComplete).isTrue()
            assertThat(d.omittedSummary()).isNull()
            assertThat(d.text).startsWith("=== PAGE 1 ===")
            assertThat(d.text).doesNotContain("%")
            assertThat(d.text).contains("[address-field] Frau / Erika Mustermann / Musterstraße 12 / 54321 Beispieldorf")
            // A label and its value on one row stay together.
            assertThat(d.text).contains("Kundennummer: 4402917")
            assertThat(d.pagesRead).isEqualTo(1)
            assertThat(d.totalPages).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("Noise")
    inner class Noise {

        private val receipt = listOf(
            b("Kaufhaus Beispiel", 0.2f, 0.05f),
            b("Summe 23,47", 0.2f, 0.30f),
            b("TSE-Signatur: MEUCIQDx7k2Lq9ZpV3nB8sYt4RwE5uJ1hG6fD0aCkMxN2oPq", 0.05f, 0.40f),
            b("Seriennummer: 7A3F9C0D11B2E4F5A6B7C8D9E0F1A2B3", 0.05f, 0.44f),
            b("4006381333931 4006381333931", 0.1f, 0.50f),
            b("Bon-Nr 4711", 0.2f, 0.34f),
            b("IBAN DE89370400440532013000", 0.2f, 0.36f),
            b("l1lI|", 0.2f, 0.60f, conf = 0.2f),
            b("Kein Wert bekannt", 0.2f, 0.62f, conf = 0f),
        )

        private val layout = LetterLayoutAnalyzer.analyze(listOf(receipt))
        private fun noiseOf(text: String) = layout.allLines.first { it.text == text }.noise

        @Test
        fun `signatures serial numbers and barcodes are flagged, not deleted`() {
            assertThat(noiseOf(receipt[2].text)).isEqualTo(NoiseKind.TOKEN)
            assertThat(noiseOf(receipt[3].text)).isEqualTo(NoiseKind.TOKEN)
            assertThat(noiseOf(receipt[4].text)).isEqualTo(NoiseKind.BARCODE)
            assertThat(layout.allLines).hasSize(receipt.size)
        }

        @Test
        fun `real content and IBANs are not noise`() {
            assertThat(noiseOf("Summe 23,47")).isNull()
            assertThat(noiseOf("Bon-Nr 4711")).isNull()
            assertThat(noiseOf("IBAN DE89370400440532013000")).isNull()
            assertThat(noiseOf("Kaufhaus Beispiel")).isNull()
        }

        @Test
        fun `low confidence is flagged, unknown confidence is not`() {
            assertThat(noiseOf("l1lI|")).isEqualTo(NoiseKind.LOW_CONFIDENCE)
            assertThat(noiseOf("Kein Wert bekannt")).isNull()
        }

        @Test
        fun `flagged lines are left out of the description and counted`() {
            val d = layout.describe()
            assertThat(d.text).doesNotContain("MEUCIQ")
            assertThat(d.text).doesNotContain("7A3F9C0D")
            assertThat(d.text).doesNotContain("4006381333931")
            assertThat(d.text).contains("Summe 23,47")
            assertThat(d.ignoredNoiseLines).isEqualTo(4)
            assertThat(layout.plainText()).doesNotContain("MEUCIQ")
        }
    }
}
