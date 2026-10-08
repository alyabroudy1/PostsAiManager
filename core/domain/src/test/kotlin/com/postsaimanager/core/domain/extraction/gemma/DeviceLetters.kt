package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.v2.Din
import com.postsaimanager.core.model.OcrBlock

/**
 * The invented letters of the device passes (Jobcenter, Zahnarzt, Markt), as the tests of the Gemma path use them: the Jobcenter letter as
 * a laid-out page, and the three texts as the OCR reads them. No real data in any of them.
 */
internal object DeviceLetters {

    /** The Jobcenter's confirmation of an application: a letter with a contact person, a phone, an e-mail and the date 01.09.2026. */
    val jobcenterBlocks: List<OcrBlock> = Din.firstPage(
        Din.Sender(
            letterhead = listOf("Jobcenter Musterstadt", "Musterstraße 1", "12345 Musterstadt"),
            returnLine = "Jobcenter Musterstadt · Musterstraße 1 · 12345 Musterstadt",
            footer = emptyList(),
        ),
        address = listOf("Maria Mustermann", "Beispielweg 2", "12345 Musterstadt"),
        info = listOf(
            "BG-Nummer" to "12345BG0007777",
            "Ansprechpartnerin" to "Frau Nadine Beispiel",
            "Telefon" to "0123 456-701",
            "E-Mail" to "nadine.beispiel@jobcenter-musterstadt.example",
            "Datum" to "01.09.2026",
        ),
        subject = "Eingangsbestätigung Ihres Antrags auf Bürgergeld – BG-Nummer 12345BG0007777",
        body = listOf(
            "Sehr geehrte Frau Mustermann,",
            "wir bestätigen, dass Ihr Antrag auf Bürgergeld am 01.09.2026 bei uns eingegangen ist. Wir prüfen Ihren Antrag und melden uns, sobald eine Entscheidung vorliegt.",
            "Bitte geben Sie bei allen Rückfragen Ihre BG-Nummer an.",
            "Mit freundlichen Grüßen",
            "Nadine Beispiel",
            "Jobcenter Musterstadt",
        ),
    )

    val jobcenterText: String = jobcenterBlocks.joinToString("\n") { it.text }

    /** The practice's appointment reminder as a chat screenshot: three short messages, an avatar letter in front of the name. */
    val zahnarztText: String = listOf(
        "Zahnarztpraxis",
        "Terminerinnerung: Ihr Termin ist am 14.10.2026 um 10:30 Uhr.",
        "Bitte Versichertenkarte mitbringen. Absage bis 24 h vorher.",
    ).joinToString("\n")

    /** A till slip of a market: a card payment already made. */
    val marktText: String = listOf(
        "Markt Beispiel",
        "Musterstraße 5, 12345 Musterstadt",
        "Brot 2,50 EUR",
        "Milch 1,20 EUR",
        "Käse 10,48 EUR",
        "Summe 14,18 EUR",
        "Kartenzahlung girocard 14,18 EUR",
        "Vielen Dank für Ihren Einkauf",
    ).joinToString("\n")
}
