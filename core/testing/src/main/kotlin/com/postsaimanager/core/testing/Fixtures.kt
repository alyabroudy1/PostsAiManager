package com.postsaimanager.core.testing

/**
 * Realistic document fixtures for extraction tests.
 *
 * These are the shapes `EntityExtractor` was written against: German business
 * correspondence following DIN 5008 layout. Keep them realistic — an extractor
 * that only passes on sanitised input is not tested.
 */
object Fixtures {

    /** A complete DIN 5008 letter: letterhead, address window, metadata, subject, body, footer. */
    val GERMAN_LETTER_JOBCENTER = """
        Jobcenter Berlin Mitte
        Karl-Marx-Allee 31
        10178 Berlin

        Jobcenter Berlin Mitte · Karl-Marx-Allee 31 · 10178 Berlin

        Herrn
        Max Mustermann
        Beispielstraße 12
        10115 Berlin

        Aktenzeichen: BG 1234/5678
        Kundennummer: 987654321
        Datum: 15.01.2026

        Betreff: Bescheid über Leistungen zur Sicherung des Lebensunterhalts

        Sehr geehrter Herr Mustermann,

        hiermit teilen wir Ihnen mit, dass Ihr Antrag vom 02.01.2026 bewilligt wurde.
        Der monatliche Betrag beträgt 563,00 EUR und wird auf Ihr Konto überwiesen.

        Bitte reichen Sie die fehlenden Unterlagen bis zum 31.01.2026 nach.

        Mit freundlichen Grüßen

        Sachbearbeiterin M. Schmidt
        Tel.: +49 30 555123456
        E-Mail: kontakt@jobcenter-berlin.de
        IBAN: DE89 3704 0044 0532 0130 00
    """.trimIndent()

    /** Invoice-shaped letter with amount and payment deadline. */
    val GERMAN_INVOICE = """
        Deutsche Telekom AG
        Landgrabenweg 151
        53227 Bonn

        Herrn
        Max Mustermann
        Beispielstraße 12
        10115 Berlin

        Rechnungsnummer: RE-2026-00841
        Datum: 03.02.2026

        Betreff: Ihre Rechnung für Januar 2026

        Sehr geehrter Herr Mustermann,

        der Rechnungsbetrag von 49,99 € ist zahlbar bis zum 17.02.2026.

        Mit freundlichen Grüßen
        Deutsche Telekom AG
    """.trimIndent()

    /** English correspondence — used to check language detection does not over-bias to German. */
    val ENGLISH_LETTER = """
        Acme Corporation
        1 Business Park
        London EC1A 1BB

        Dear Mr Mustermann,

        Thank you for your enquiry. Please find the reference details that you have
        requested attached to this letter. We have been advised that the matter will
        be resolved shortly.

        Yours sincerely,
        A. Smith
    """.trimIndent()

    /** Arabic text — checks the Unicode-ratio branch of language detection. */
    val ARABIC_TEXT = "مرحبا بك في هذا المستند. نرجو منك مراجعة التفاصيل المرفقة."

    /** Deliberately malformed OCR output: broken lines, noise characters, no structure. */
    val GARBLED_OCR = """
        ||| ~~~ 8@#
        Jobcen7er Ber1in
        ----------
        \n\n
        ###
    """.trimIndent()

    val EMPTY = ""
    val WHITESPACE_ONLY = "   \n\n   \t  \n  "
}
