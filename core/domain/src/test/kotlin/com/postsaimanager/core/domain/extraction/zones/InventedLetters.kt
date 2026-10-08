package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.v2.Din
import com.postsaimanager.core.model.OcrBlock

/**
 * The invented letters of `data/test-docs-p15` (plans 15 and 16), as OCR pages: the text of each rendered HTML in the order the page
 * prints it, with the DIN geometry of [Din]. The information block is a STACK (a label line, its value on the next line, ...) in ONE OCR
 * block, as the letters print it; the body lines are the paragraphs, a table row is `cell||cell`. Every value is invented.
 */
internal object InventedLetters {

    private fun stacked(vararg lines: String): OcrBlock = Din.b(lines.joinToString("\n"), 0.59f, 0.173f, w = 0.3f)

    private fun sender(name: String, street: String) = Din.Sender(
        letterhead = listOf(name, street, "12345 Musterstadt"),
        returnLine = "$name · $street · 12345 Musterstadt",
        footer = listOf(name),
    )

    /** din-letter.pdf: a yearly electricity bill; the period 01.09.2025 to 31.08.2026 is no deadline, 15.10.2026 is the due date. */
    val din: List<List<OcrBlock>> = listOf(
        Din.firstPage(
            sender("Stadtwerke Musterstadt", "Energieweg 5"),
            address = listOf("Erika Beispielfrau", "Beispielweg 7", "12345 Musterstadt"),
            subject = "Jahresabrechnung Strom 2025/2026",
            body = listOf(
                "Sehr geehrte Frau Beispielfrau,",
                "für den Abrechnungszeitraum 01.09.2025 bis 31.08.2026 haben wir Ihren Stromverbrauch abgerechnet. Nach Abzug Ihrer Abschlagszahlungen ergibt sich eine Nachzahlung:",
                "Rechnungsbetrag||584,20 EUR",
                "Bereits gezahlte Abschläge||480,00 EUR",
                "Zu zahlender Betrag||104,20 EUR",
                "Bitte überweisen Sie den Betrag bis zum 15.10.2026 auf das Konto IBAN DE89 3704 0044 0532 0130 00, Verwendungszweck KD-0000-4711.",
                "Bei Fragen erreichen Sie uns unter der oben genannten Telefonnummer.",
                "Mit freundlichen Grüßen",
                "Stadtwerke Musterstadt",
                "Kundenservice",
            ),
        ) + stacked(
            "Kundennummer", "KD-0000-4711", "Ansprechpartnerin", "Frau Ina Beispiel", "Telefon", "0123 456-789", "E-Mail",
            "kundenservice@stadtwerke-musterstadt.example", "Datum", "25.09.2026",
        ),
    )

    /** insurance-3-dates.pdf: three dates with three meanings: letter date 12.10.2026, due 01.01.2027, special-termination deadline 30.11.2026. */
    val insurance: List<List<OcrBlock>> = listOf(
        Din.firstPage(
            sender("Beispiel Versicherung AG", "Policenstraße 9"),
            address = listOf("Karl Mustermann", "Beispielweg 2", "12345 Musterstadt"),
            subject = "Kfz-Versicherung – Beitragsanpassung zum 01.01.2027",
            body = listOf(
                "Sehr geehrter Herr Mustermann,",
                "zum Jahreswechsel passen wir den Beitrag für Ihre Kfz-Haftpflicht- und Vollkaskoversicherung an. Ihr neuer Jahresbeitrag beträgt 612,40 EUR (bisher 548,90 EUR).",
                "Der Beitrag ist fällig zum 01.01.2027. Wir buchen ihn von Ihrem hinterlegten Konto ab.",
                "Da sich der Beitrag erhöht, haben Sie ein Sonderkündigungsrecht bis 30.11.2026. Ihre Kündigung muss uns bis zu diesem Tag in Textform erreichen.",
                "Bei Fragen erreichen Sie uns telefonisch unter 0123 456-300.",
                "Mit freundlichen Grüßen",
                "Beispiel Versicherung AG",
                "Kfz-Service",
            ),
        ) + stacked("Versicherungsschein-Nr.", "KFZ-0000-98765", "Ansprechpartner", "Herr Tom Beispiel", "Telefon", "0123 456-300", "Datum", "12.10.2026"),
    )

    /** jc-1-antrag-eingang.pdf: the authority's confirmation of receipt; the contact person sits in the information block. */
    val jobcenter: List<List<OcrBlock>> = listOf(
        Din.firstPage(
            sender("Jobcenter Musterstadt", "Musterstraße 1"),
            address = listOf("Maria Mustermann", "Beispielweg 2", "12345 Musterstadt"),
            subject = "Eingangsbestätigung Ihres Antrags auf Bürgergeld – BG-Nummer 12345BG0007777",
            body = listOf(
                "Sehr geehrte Frau Mustermann,",
                "wir bestätigen, dass Ihr Antrag auf Bürgergeld am 01.09.2026 bei uns eingegangen ist. Wir prüfen Ihren Antrag und melden uns, sobald eine Entscheidung vorliegt.",
                "Bitte geben Sie bei allen Rückfragen Ihre BG-Nummer an.",
                "Mit freundlichen Grüßen",
                "Nadine Beispiel",
                "Jobcenter Musterstadt",
            ),
        ) + stacked(
            "BG-Nummer", "12345BG0007777", "Ansprechpartnerin", "Frau Nadine Beispiel", "Telefon", "0123 456-701", "E-Mail",
            "nadine.beispiel@jobcenter-musterstadt.example", "Datum", "01.09.2026",
        ),
    )
}
