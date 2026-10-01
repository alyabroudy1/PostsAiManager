package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.Din.Sender
import com.postsaimanager.core.domain.extraction.v2.Din.continuation
import com.postsaimanager.core.domain.extraction.v2.Din.firstPage
import com.postsaimanager.core.model.OcrBlock

/** What the manifest says the roles are (N1-N10 have them; the first set only names the sender). */
internal class ManifestRoles(
    val sender: String,
    val addressees: List<String> = emptyList(),
    val coAddressees: List<String> = emptyList(),
    val routing: String? = null,
    val subjectPersons: List<String> = emptyList(),
    val household: Boolean = false,
)

/**
 * A value the model should choose for a slot; found among the candidates by kind and normalised value.
 * With [quote] the model answers a period in words as `{"rule": quote}` instead of an id, and [norm]
 * is what the verifier makes of it ("P14D" when the quote has a number and a unit, else the quote).
 */
internal class ExpSlot(
    val slot: SlotKey,
    val kind: CandidateKind,
    val norm: String,
    val role: String? = null,
    val quote: String? = null,
)

/** A party the model should name: a name candidate when the text matches one, else a quote. */
internal class ExpParty(
    val role: PartyRole,
    val text: String,
    val relation: PartyRelation = PartyRelation.NONE,
    val kind: PartyKind = PartyKind.PERSON,
    /** Answer with this quote instead of a candidate. */
    val quote: String? = null,
)

internal class ExpExtra(
    val label: String,
    val key: String,
    val kind: CandidateKind? = null,
    val norm: String? = null,
    val quote: String? = null,
    val confidence: String = "MEDIUM",
)

/**
 * One of the 16 test letters (testdocs and testdocs2): its OCR pages, and what a correct model would
 * answer. The expectations are the manifest facts; where the manifest is silent (roles of the first
 * six) they come from the letter's text.
 */
internal class Letter(
    val id: String,
    val pages: List<List<OcrBlock>>,
    val type: DocFamily,
    val language: String,
    val slots: List<ExpSlot>,
    val parties: List<ExpParty>,
    val extras: List<ExpExtra> = emptyList(),
    val subject: String? = null,
    val summary: String? = null,
    val manifest: ManifestRoles,
    /** Facts from the manifest that no slot of the schema holds (they are open metadata or out of scope). */
    val notInSchema: List<String> = emptyList(),
)

internal object Letters {

    private const val IBAN1 = "DE89370400440532013000"
    private const val IBAN2 = "DE02120300000000202051"
    private const val IBAN3 = "DE02100100100006820101"

    private fun amount(v: String) = CandidateKind.AMOUNT to v
    private val D = CandidateKind.DATE
    private val DT = CandidateKind.DATETIME

    // ── set 2 (N1-N10) ──────────────────────────────────────────────────────────

    private val nordlicht = Sender(
        letterhead = listOf("Nordlicht Mobilfunk GmbH", "Beispielweg 7", "12345 Beispielstadt", "Tel. 0800 555 0100", "service@nordlicht-mobil.example"),
        returnLine = "Nordlicht Mobilfunk GmbH · Beispielweg 7 · 12345 Beispielstadt",
        footer = listOf(
            "Nordlicht Mobilfunk GmbH", "Bankverbindung: Beispielbank", "Tel. 0800 555 0100",
            "Geschäftsführer: Nora Beispiel, Tim Muster", "IBAN DE02 1203 0000 0000 2020 51", "service@nordlicht-mobil.example",
        ),
    )

    val n1 = Letter(
        "N1-mahnung-telco-qr-1p",
        listOf(
            firstPage(
                nordlicht,
                address = listOf("Frau", "Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                info = listOf(
                    "Ihr Zeichen:" to "—", "Unser Zeichen:" to "FIB-Mahn 4402917", "Kundennummer:" to "4402917",
                    "Ansprechpartner:" to "Team Forderungen", "Telefon:" to "0800 555 0199", "Datum:" to "25.09.2026",
                ),
                subject = "Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204",
                body = listOf(
                    "Sehr geehrte Frau Mustermann,",
                    "zu unserer Rechnung 2026-08-771204 vom 05.08.2026 konnten wir bis heute keinen Zahlungseingang feststellen. Die Rechnung war am 19.08.2026 fällig. Falls sich Ihre Zahlung mit diesem Schreiben überschnitten hat, betrachten Sie es bitte als gegenstandslos.",
                    "Position||Rechnung vom||Fällig am||Betrag €",
                    "Rechnung 2026-08-771204 (Mobilfunk Juli 2026)||05.08.2026||19.08.2026||59,98",
                    "Mahngebühr||||||5,00",
                    "Offener Gesamtbetrag||||||64,98",
                    "Bitte überweisen Sie den offenen Betrag von 64,98 € zahlbar innerhalb von 14 Tagen nach Zugang dieses Schreibens auf das unten genannte Konto. Nach Ablauf dieser Frist behalten wir uns weitere Schritte, insbesondere die Übergabe an ein Inkassounternehmen, vor.",
                    "Kontoinhaber: Nordlicht Mobilfunk GmbH",
                    "IBAN: DE02 1203 0000 0000 2020 51",
                    "BIC: COBADEFFXXX",
                    "Verwendungszweck: RE 2026-08-771204 Kd 4402917",
                    "Mit freundlichen Grüßen",
                    "Nordlicht Mobilfunk GmbH",
                    "Forderungsmanagement",
                ),
            ),
        ),
        DocTypes.REMINDER,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-25", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "64.98 EUR", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "P14D", "DUE_DATE", quote = "innerhalb von 14 Tagen"),
            ExpSlot(Slots.FEE, CandidateKind.AMOUNT, "5.00 EUR", "FEE"),
            ExpSlot(Slots.ORIGINAL_DUE_DATE, D, "2026-08-19", "DUE_DATE"),
            ExpSlot(Slots.IBAN, CandidateKind.IBAN, IBAN2),
            ExpSlot(Slots.INVOICE_NO, CandidateKind.REFERENCE, "2026-08-771204"),
            ExpSlot(Slots.CUSTOMER_NO, CandidateKind.REFERENCE, "4402917"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Nordlicht Mobilfunk GmbH", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "Erika Mustermann"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Erika Mustermann"),
        ),
        extras = listOf(ExpExtra("Rechnung vom", "previous_invoice_date", D, "2026-08-05")),
        subject = "Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204",
        summary = "Bitte überweisen Sie den offenen Betrag von 64,98 € zahlbar innerhalb von 14 Tagen nach Zugang dieses Schreibens auf das unten genannte Konto.",
        manifest = ManifestRoles("Nordlicht Mobilfunk GmbH", addressees = listOf("Erika Mustermann"), subjectPersons = listOf("Erika Mustermann")),
    )

    private val nordstern = Sender(
        letterhead = listOf("Nordstern Direkt Versicherung AG", "Versicherungsallee 15", "12345 Beispielstadt", "Tel. 01234 555 200", "kfz@nordstern-direkt.example"),
        returnLine = "Nordstern Direkt Versicherung AG · Versicherungsallee 15 · 12345 Beispielstadt",
        right = false,
        footer = listOf(
            "Nordstern Direkt Versicherung AG", "Bankverbindung: Beispielbank", "Tel. 01234 555 200",
            "Vorstand: Dr. Uta Muster, Jan Beispiel", "IBAN DE89 3704 0044 0532 0130 00", "kfz@nordstern-direkt.example",
        ),
    )

    val n2 = Letter(
        "N2-kfz-verlaengerung-2p",
        listOf(
            firstPage(
                nordstern,
                address = listOf("Herrn und Frau", "Max und Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                addressAsOneBlock = true,
                info = listOf(
                    "Ihr Zeichen:" to "—", "Versicherungsnr.:" to "KFZ-4471-882-19", "Kennzeichen:" to "XX-AB 1234",
                    "Ansprechpartner:" to "Herr K. Beispiel", "Telefon:" to "01234 555 210", "Datum:" to "14.09.2026",
                ),
                subject = "Ihre Kfz-Versicherung – Beitrag ab 01.01.2027",
                body = listOf(
                    "Sehr geehrte Frau Mustermann, sehr geehrter Herr Mustermann,",
                    "vielen Dank für Ihr Vertrauen. Ihre Kfz-Versicherung mit der Versicherungsnummer KFZ-4471-882-19 verlängert sich zum 01.01.2027 um ein weiteres Jahr. Aufgrund der aktuellen Schadenentwicklung und der Regionalklasse müssen wir Ihren Beitrag anpassen.",
                    "Beitrag pro Jahr||bisher||ab 01.01.2027",
                    "Kfz-Haftpflicht und Teilkasko||579,10 €||612,40 €",
                    "Der neue Jahresbeitrag beträgt 612,40 € (bisher 579,10 €). Der Beitrag wird wie gewohnt per SEPA-Lastschrift von dem bei uns hinterlegten Konto eingezogen.",
                ),
                totalPages = 2,
            ),
            continuation(
                nordstern, 2, 2, "Nordstern Direkt Versicherung AG · Kfz-Versicherung KFZ-4471-882-19",
                listOf(
                    "Ihr Sonderkündigungsrecht",
                    "Wegen der Beitragserhöhung können Sie Ihren Vertrag außerordentlich kündigen. Ihre Kündigung muss uns bis 30.11.2026 in Textform (Brief, E-Mail) zugehen; der Vertrag endet dann zum 31.12.2026.",
                    "Möchten Sie Ihre Versicherung bei uns fortführen, müssen Sie nichts weiter tun. Bei Fragen berät Sie Herr Beispiel gern unter 01234 555 210.",
                    "Mit freundlichen Grüßen",
                    "Nordstern Direkt Versicherung AG",
                ),
            ),
        ),
        DocTypes.INSURANCE,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-14", "LETTER_DATE"),
            ExpSlot(Slots.NEW_AMOUNT, CandidateKind.AMOUNT, "612.40 EUR", "INSTALMENT"),
            ExpSlot(Slots.PREVIOUS_AMOUNT, CandidateKind.AMOUNT, "579.10 EUR", "PREVIOUS"),
            ExpSlot(Slots.EFFECTIVE_DATE, D, "2027-01-01", "EFFECTIVE_FROM"),
            ExpSlot(Slots.DUE_DATE, D, "2026-11-30", "DEADLINE"),
            ExpSlot(Slots.CONTRACT_END, D, "2026-12-31", "EFFECTIVE_FROM"),
            ExpSlot(Slots.POLICY_NO, CandidateKind.REFERENCE, "KFZ-4471-882-19"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Nordstern Direkt Versicherung AG", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "Max Mustermann", PartyRelation.HOUSEHOLD, quote = "Max Mustermann"),
            ExpParty(PartyRole.CO_ADDRESSEE, "Erika Mustermann", quote = "Erika Mustermann"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Max Mustermann", quote = "Max Mustermann"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Erika Mustermann", quote = "Erika Mustermann"),
        ),
        extras = listOf(ExpExtra("Kennzeichen", "vehicle_plate", quote = "XX-AB 1234")),
        subject = "Ihre Kfz-Versicherung – Beitrag ab 01.01.2027",
        manifest = ManifestRoles(
            "Nordstern Direkt Versicherung AG",
            addressees = listOf("Max Mustermann", "Erika Mustermann"), coAddressees = listOf("Erika Mustermann"),
            subjectPersons = listOf("Max Mustermann", "Erika Mustermann"), household = true,
        ),
    )

    private val schule = Sender(
        letterhead = listOf("Grundschule Am Beispielweg", "Beispielweg 20", "12345 Beispielstadt", "Tel. 01234 777 10", "sekretariat@gs-beispielweg.example"),
        returnLine = "Grundschule Am Beispielweg · Beispielweg 20 · 12345 Beispielstadt",
        right = false,
        footer = listOf(
            "Grundschule Am Beispielweg", "Schulleitung: R. Muster", "Beispielweg 20, 12345 Beispielstadt",
            "Förderverein Grundschule Am Beispielweg e. V.", "IBAN DE89 3704 0044 0532 0130 00", "Sparkasse Beispielstadt",
        ),
    )

    val n3 = Letter(
        "N3-schule-familie-2p",
        listOf(
            firstPage(
                schule,
                address = listOf("Familie", "Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                info = listOf(
                    "Ihr Zeichen:" to "—", "Unser Zeichen:" to "Kl. 3b / 2026-27", "Klassenlehrerin:" to "Frau S. Sommer",
                    "Telefon:" to "01234 777 10", "Datum:" to "28.09.2026",
                ),
                subject = "Klassenausflug der Klasse 3b am 24.11.2026",
                body = listOf(
                    "Liebe Eltern und Erziehungsberechtigte der Klasse 3b,",
                    "am Dienstag, 24.11.2026 fahren wir mit der Klasse 3b in das Naturkundemuseum Beispielstadt. Die Kinder nehmen an einem Workshop zum Thema „Wald im Winter“ teil. Abfahrt ist um 8:15 Uhr an der Schule, Rückkehr gegen 13:00 Uhr.",
                    "Die Kosten für Fahrt, Eintritt und Workshop betragen 35,00 € pro Kind. Bitte geben Sie den Betrag passend und in einem beschrifteten Umschlag zusammen mit dem Rückmeldeabschnitt Ihrem Kind mit. Ihre Tochter Lea Mustermann soll außerdem ein Lunchpaket und wetterfeste Kleidung mitbringen.",
                    "Bitte füllen Sie den Rückmeldeabschnitt auf der nächsten Seite aus und geben Sie ihn bitte unterschrieben bis Freitag, 16.10.2026 zurück. Ohne Rückmeldung können wir Ihr Kind leider nicht anmelden.",
                    "Mit freundlichen Grüßen",
                    "Sabine Sommer",
                    "Klassenlehrerin 3b",
                ),
                totalPages = 2,
            ),
            continuation(
                schule, 2, 2, "Grundschule Am Beispielweg · Klassenausflug Klasse 3b",
                listOf(
                    "Hinweise zum Ausflug",
                    "Bitte denken Sie an feste Schuhe und eine Trinkflasche.",
                    "Rückmeldung – Klassenausflug 24.11.2026 (Klasse 3b)",
                    "Name des Kindes: Lea Mustermann      Klasse: 3b",
                    "Mein Kind nimmt am Ausflug teil. Die 35,00 € liegen bei.",
                    "Bitte unterschrieben bis Freitag, 16.10.2026 zurückgeben.",
                    "Datum, Unterschrift eines Erziehungsberechtigten",
                ),
            ),
        ),
        DocTypes.SCHOOL,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-28", "LETTER_DATE"),
            ExpSlot(Slots.EVENT_DATE, D, "2026-11-24", "EVENT"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "35.00 EUR", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-16", "DEADLINE"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Grundschule Am Beispielweg", kind = PartyKind.AUTHORITY),
            ExpParty(PartyRole.ADDRESSEE, "Familie Mustermann", PartyRelation.HOUSEHOLD, quote = "Familie Mustermann"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Lea Mustermann", quote = "Lea Mustermann"),
        ),
        extras = listOf(ExpExtra("Klasse", "school_class", quote = "3b"), ExpExtra("Klassenlehrerin", "teacher", quote = "Frau S. Sommer")),
        subject = "Klassenausflug der Klasse 3b am 24.11.2026",
        manifest = ManifestRoles(
            "Grundschule Am Beispielweg", addressees = listOf("Familie Mustermann"),
            subjectPersons = listOf("Lea Mustermann"), household = true,
        ),
    )

    private val buero = Sender(
        letterhead = listOf("Büro-Partner Beispiel KG", "Industriestraße 40", "12345 Beispielstadt", "Tel. 01234 888 0", "vertrieb@buero-partner.example"),
        returnLine = "Büro-Partner Beispiel KG · Industriestraße 40 · 12345 Beispielstadt",
        footer = listOf(
            "Büro-Partner Beispiel KG", "Bankverbindung: Beispielbank", "Tel. 01234 888 0",
            "Persönlich haftende Gesellschafterin: BPB Verwaltungs GmbH", "IBAN DE89 3704 0044 0532 0130 00", "AG Beispielstadt HRA 000000",
        ),
    )

    val n4 = Letter(
        "N4-zhd-firma-1p",
        listOf(
            firstPage(
                buero,
                address = listOf("Mustermann Consulting GmbH", "z. Hd. Frau Erika Mustermann", "Gewerbering 4", "54321 Beispieldorf"),
                info = listOf(
                    "Ihr Zeichen:" to "EM/2026", "Unser Zeichen:" to "WV-2291", "Ansprechpartner:" to "Herr T. Muster",
                    "Telefon:" to "01234 888 44", "Datum:" to "23.09.2026",
                ),
                subject = "Wartungsvertrag WV-2291 – Verlängerung ab 01.01.2027",
                body = listOf(
                    "Sehr geehrte Frau Mustermann,",
                    "Ihr Wartungsvertrag WV-2291 für die Multifunktionsgeräte der Mustermann Consulting GmbH läuft zum 31.12.2026 aus. Wir möchten Ihnen die Verlängerung um weitere 12 Monate zu unveränderten Konditionen anbieten.",
                    "Leistung||Zeitraum||Netto p. a.",
                    "Wartung 2 Geräte inkl. Toner-Service||01.01.–31.12.2027||1.428,00 €",
                    "Bitte bestätigen Sie uns die Verlängerung bis 15.10.2026 durch Rücksendung des unterschriebenen Angebots oder per E-Mail an vertrieb@buero-partner.example. Ohne Rückmeldung endet der Vertrag zum 31.12.2026.",
                    "Mit freundlichen Grüßen",
                    "Büro-Partner Beispiel KG",
                    "T. Muster, Vertrieb",
                ),
            ),
        ),
        DocTypes.INSURANCE,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-23", "LETTER_DATE"),
            ExpSlot(Slots.NEW_AMOUNT, CandidateKind.AMOUNT, "1428.00 EUR", "OTHER"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-15", "DEADLINE"),
            ExpSlot(Slots.CONTRACT_END, D, "2026-12-31", "EFFECTIVE_FROM"),
            ExpSlot(Slots.CONTRACT_NO, CandidateKind.REFERENCE, "WV-2291"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Büro-Partner Beispiel KG", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "Mustermann Consulting GmbH", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ROUTING, "Erika Mustermann"),
        ),
        extras = listOf(ExpExtra("E-Mail", "contact_email", CandidateKind.EMAIL, "vertrieb@buero-partner.example", confidence = "HIGH")),
        subject = "Wartungsvertrag WV-2291 – Verlängerung ab 01.01.2027",
        manifest = ManifestRoles("Büro-Partner Beispiel KG", addressees = listOf("Mustermann Consulting GmbH"), routing = "Erika Mustermann"),
    )

    private val universitaet = Sender(
        letterhead = listOf("Universität Beispielstadt", "Studierendensekretariat", "Universitätsring 1, 12345 Beispielstadt", "Tel. 01234 999 30"),
        returnLine = "Universität Beispielstadt · Studierendensekretariat · Universitätsring 1 · 12345 Beispielstadt",
        right = false,
        footer = listOf(
            "Universität Beispielstadt", "Bankverbindung: Landeskasse Beispiel", "Studierendensekretariat",
            "Körperschaft des öffentlichen Rechts", "IBAN DE02 1001 0010 0006 8201 01", "Tel. 01234 999 30",
        ),
    )

    val n5 = Letter(
        "N5-co-familie-1p",
        listOf(
            firstPage(
                universitaet,
                address = listOf("Herrn", "Jonas Mustermann", "c/o Familie Beispiel", "Beispielgasse 3", "12345 Beispielstadt"),
                info = listOf(
                    "Ihr Zeichen:" to "—", "Matrikelnummer:" to "1234567", "Ansprechpartner:" to "Frau L. Test",
                    "Telefon:" to "01234 999 31", "Datum:" to "24.09.2026",
                ),
                subject = "Rückmeldung zum Wintersemester 2026/27",
                body = listOf(
                    "Sehr geehrter Herr Mustermann,",
                    "für die Rückmeldung zum Wintersemester 2026/27 (Matrikelnummer 1234567) bitten wir Sie, den Semesterbeitrag in Höhe von 187,50 € bis zum 12.10.2026 zu überweisen. Erst nach Zahlungseingang gilt die Rückmeldung als vollzogen.",
                    "Empfänger: Landeskasse Beispiel",
                    "IBAN: DE02 1001 0010 0006 8201 01",
                    "Verwendungszweck: 1234567 WS2026/27",
                    "Bitte beachten Sie: Bei Nichtzahlung bis zum genannten Termin droht die Exmatrikulation zum 31.03.2027.",
                    "Mit freundlichen Grüßen",
                    "Im Auftrag",
                    "Studierendensekretariat",
                ),
            ),
        ),
        DocTypes.BILL,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-24", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "187.50 EUR", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-12", "DUE_DATE"),
            ExpSlot(Slots.IBAN, CandidateKind.IBAN, IBAN3),
            ExpSlot(Slots.REFERENCE, CandidateKind.REFERENCE, "1234567"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Universität Beispielstadt", kind = PartyKind.AUTHORITY),
            ExpParty(PartyRole.ADDRESSEE, "Jonas Mustermann"),
            ExpParty(PartyRole.CARE_OF, "Familie Beispiel", quote = "Familie Beispiel"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Jonas Mustermann"),
        ),
        subject = "Rückmeldung zum Wintersemester 2026/27",
        manifest = ManifestRoles(
            "Universität Beispielstadt", addressees = listOf("Jonas Mustermann"), routing = "Familie Beispiel",
            subjectPersons = listOf("Jonas Mustermann"),
        ),
    )

    private val hausverwaltung = Sender(
        letterhead = listOf("Hausverwaltung Beispiel GmbH", "Verwalterstraße 9", "12345 Beispielstadt", "Tel. 01234 300 100", "info@hv-beispiel.example"),
        returnLine = "Hausverwaltung Beispiel GmbH · Verwalterstraße 9 · 12345 Beispielstadt",
        footer = listOf(
            "Hausverwaltung Beispiel GmbH", "Bankverbindung: Beispielbank", "Tel. 01234 300 100",
            "Geschäftsführer: Peter Beispiel", "IBAN DE89 3704 0044 0532 0130 00", "AG Beispielstadt HRB 000000",
        ),
    )

    val n6 = Letter(
        "N6-nebenkosten-3p",
        listOf(
            firstPage(
                hausverwaltung,
                address = listOf("Herrn", "Mohammad Mustermann", "Beispielallee 21", "3. OG links", "12345 Beispielstadt"),
                info = listOf(
                    "Ihr Zeichen:" to "—", "Unser Zeichen:" to "BK-2025-0318", "Mieteinheit:" to "WE 3.01",
                    "Ansprechpartner:" to "Frau H. Muster", "Telefon:" to "01234 300 120", "Datum:" to "22.09.2026",
                ),
                subject = "Betriebskostenabrechnung 01.01.2025 – 31.12.2025",
                body = listOf(
                    "Sehr geehrter Herr Mustermann,",
                    "anbei erhalten Sie die Betriebskostenabrechnung für Ihre Wohnung Beispielallee 21, 3. OG links (WE 3.01) für den Abrechnungszeitraum 01.01.2025 bis 31.12.2025. Ihre Wohnfläche beträgt 68,5 m² von insgesamt 548,0 m². Das Ergebnis der Abrechnung finden Sie auf Seite 3.",
                    "Kostenart||Gesamtkosten Haus €||Ihr Anteil €",
                    "Grundsteuer||2.499,20||312,40",
                    "Wasserversorgung||3.424,00||428,15",
                    "Heizung und Warmwasser||7.477,80||934,60",
                ),
                totalPages = 3,
            ),
            continuation(
                hausverwaltung, 2, 3, "Hausverwaltung Beispiel GmbH · Betriebskostenabrechnung 2025",
                listOf(
                    "Kostenart||Gesamtkosten Haus €||Ihr Anteil €",
                    "Gartenpflege||688,00||86,00",
                    "Summe Ihrer Betriebskosten||||2.826,32",
                    "Die Verteilung erfolgt nach den im Mietvertrag vereinbarten Umlageschlüsseln. Die Belege können nach Terminvereinbarung in unserem Büro eingesehen werden.",
                ),
            ),
            continuation(
                hausverwaltung, 3, 3, "Hausverwaltung Beispiel GmbH · Betriebskostenabrechnung 2025",
                listOf(
                    "2. Ergebnis der Abrechnung",
                    "Position||Betrag €",
                    "Ihre Betriebskosten 2025 (Summe)||2.826,32",
                    "Geleistete Vorauszahlungen (12 × 220,00 €)||2.640,00",
                    "Nachzahlung||186,32",
                    "Der Nachzahlungsbetrag von 186,32 € ist fällig bis 31.10.2026. Bitte überweisen Sie ihn auf das folgende Konto:",
                    "Kontoinhaber: Hausverwaltung Beispiel GmbH",
                    "IBAN: DE89 3704 0044 0532 0130 00",
                    "BIC: COBADEFFXXX",
                    "Verwendungszweck: BK 2025 WE 3.01 Mustermann",
                    "3. Anpassung der Vorauszahlung",
                    "Ab 01.11.2026 beträgt Ihre monatliche Betriebskostenvorauszahlung 245,00 € (bisher 220,00 €).",
                    "Mit freundlichen Grüßen",
                    "Hausverwaltung Beispiel GmbH",
                ),
            ),
        ),
        DocTypes.BILL,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-22", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "186.32 EUR", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-31", "DUE_DATE"),
            ExpSlot(Slots.IBAN, CandidateKind.IBAN, IBAN1),
            ExpSlot(Slots.REFERENCE, CandidateKind.REFERENCE, "BK-2025-0318"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Hausverwaltung Beispiel GmbH", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "Mohammad Mustermann"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Mohammad Mustermann"),
        ),
        extras = listOf(
            ExpExtra("Summe Ihrer Betriebskosten", "total_costs", CandidateKind.AMOUNT, "2826.32 EUR"),
            ExpExtra("Geleistete Vorauszahlungen", "advance_paid", CandidateKind.AMOUNT, "2640.00 EUR"),
            ExpExtra("monatliche Betriebskostenvorauszahlung", "new_monthly_advance", CandidateKind.AMOUNT, "245.00 EUR"),
        ),
        subject = "Betriebskostenabrechnung 01.01.2025 – 31.12.2025",
        manifest = ManifestRoles("Hausverwaltung Beispiel GmbH", addressees = listOf("Mohammad Mustermann"), subjectPersons = listOf("Mohammad Mustermann")),
    )

    private val beitragsservice = Sender(
        letterhead = listOf("Beitragsservice Beispiel", "Postfach 10 00 00", "50656 Beispielstadt", "Tel. 01806 000 000", "service@beitragsservice-beispiel.example"),
        returnLine = "Beitragsservice Beispiel · Postfach 10 00 00 · 50656 Beispielstadt",
        footer = listOf(
            "Beitragsservice Beispiel", "Bankverbindung: Beispielbank", "Servicetelefon: 01806 000 000",
            "einer Gemeinschaftseinrichtung der", "IBAN DE02 1001 0010 0006 8201 01", "www.beitragsservice-beispiel.example",
        ),
    )

    val n7 = Letter(
        "N7-beitragsservice-1p",
        listOf(
            firstPage(
                beitragsservice,
                address = listOf("Frau", "Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                info = listOf("Beitragsnummer:" to "987 654 321", "Ihr Zeichen:" to "—", "Datum:" to "26.09.2026"),
                subject = "Zahlungserinnerung – Beitragsnummer 987 654 321",
                body = listOf(
                    "Sehr geehrte Frau Mustermann,",
                    "für Ihre Wohnung Musterstraße 12, 54321 Beispieldorf, ist der Rundfunkbeitrag für das vierte Quartal 2026 (Oktober bis Dezember) fällig. Der Beitrag wird Ihnen vierteljährlich in Rechnung gestellt.",
                    "Zeitraum||Fällig am||Betrag €",
                    "01.10.2026 – 31.12.2026||15.10.2026||55,08",
                    "Bitte zahlen Sie den Betrag von 55,08 € bis zum 15.10.2026 unter Angabe Ihrer Beitragsnummer 987 654 321 auf das unten stehende Konto. Wenn Sie uns ein SEPA-Lastschriftmandat erteilen, erledigen wir das künftig automatisch.",
                    "Kontoinhaber: Beitragsservice Beispiel",
                    "IBAN: DE02 1001 0010 0006 8201 01",
                    "BIC: BELADEBEXXX",
                    "Verwendungszweck: 987 654 321",
                    "Mit freundlichen Grüßen",
                    "Ihr Beitragsservice",
                ),
            ),
        ),
        DocTypes.BILL,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-26", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "55.08 EUR", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-15", "DUE_DATE"),
            ExpSlot(Slots.IBAN, CandidateKind.IBAN, IBAN3),
            ExpSlot(Slots.REFERENCE, CandidateKind.REFERENCE, "987 654 321"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Beitragsservice Beispiel", kind = PartyKind.AUTHORITY),
            ExpParty(PartyRole.ADDRESSEE, "Erika Mustermann"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Erika Mustermann"),
        ),
        subject = "Zahlungserinnerung – Beitragsnummer 987 654 321",
        manifest = ManifestRoles("Beitragsservice Beispiel", addressees = listOf("Erika Mustermann"), subjectPersons = listOf("Erika Mustermann")),
    )

    private val bank = Sender(
        letterhead = listOf("Beispielbank eG", "Marktplatz 2", "12345 Beispielstadt", "Tel. 01234 400 0", "kundenservice@beispielbank.example"),
        returnLine = "Beispielbank eG · Marktplatz 2 · 12345 Beispielstadt",
        right = false,
        footer = listOf(
            "Beispielbank eG", "Bankverbindung: Beispielbank eG", "Tel. 01234 400 0",
            "Vorstand: A. Muster, B. Beispiel", "BIC COBADEFFXXX", "www.beispielbank.example",
        ),
    )

    val n8 = Letter(
        "N8-info-bank-noaction-1p",
        listOf(
            firstPage(
                bank,
                address = listOf("Herrn", "Max Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                info = listOf(
                    "Ihr Zeichen:" to "—", "Kundennummer:" to "7788123", "Ansprechpartner:" to "Ihr Berater-Team",
                    "Telefon:" to "01234 400 55", "Datum:" to "21.09.2026",
                ),
                subject = "Informationen zu unserem neuen Kundenportal",
                body = listOf(
                    "Sehr geehrter Herr Mustermann,",
                    "wir entwickeln unser Angebot für Sie stetig weiter. Ab dem 01.11.2026 steht Ihnen unser neues Kundenportal zur Verfügung, in dem Sie Ihre Kontoumsätze, Dokumente und Nachrichten übersichtlich an einem Ort finden.",
                    "Für Sie ändert sich dadurch nichts: Ihre Konten, Karten und Zugangsdaten für das Online-Banking bleiben unverändert. Sie müssen nichts unternehmen; dieses Schreiben dient ausschließlich Ihrer Information.",
                    "Wenn Sie mehr über das Portal erfahren möchten, finden Sie Einzelheiten auf unserer Internetseite. Unser Kundenservice ist Montag bis Freitag von 8 bis 18 Uhr unter 01234 400 55 für Sie da.",
                    "Mit freundlichen Grüßen",
                    "Ihre Beispielbank eG",
                ),
            ),
        ),
        DocTypes.INFO,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-21", "LETTER_DATE"),
            ExpSlot(Slots.EFFECTIVE_DATE, D, "2026-11-01", "EFFECTIVE_FROM"),
            ExpSlot(Slots.CUSTOMER_NO, CandidateKind.REFERENCE, "7788123"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Beispielbank eG", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "Max Mustermann"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Max Mustermann"),
        ),
        extras = listOf(ExpExtra("Kundenservice", "service_phone", CandidateKind.PHONE, "01234 400 55", confidence = "HIGH")),
        subject = "Informationen zu unserem neuen Kundenportal",
        manifest = ManifestRoles("Beispielbank eG", addressees = listOf("Max Mustermann"), subjectPersons = listOf("Max Mustermann")),
    )

    private val energie = Sender(
        letterhead = listOf("Energie Beispielstadt GmbH", "Stromweg 3", "12345 Beispielstadt", "Tel. 01234 600 0", "kundenservice@energie-beispiel.example"),
        returnLine = "Energie Beispielstadt GmbH · Stromweg 3 · 12345 Beispielstadt",
        footer = listOf(
            "Energie Beispielstadt GmbH", "Bankverbindung: Beispielbank", "Tel. 01234 600 0",
            "Geschäftsführer: S. Beispiel", "IBAN DE89 3704 0044 0532 0130 00", "AG Beispielstadt HRB 000000",
        ),
    )

    val n9 = Letter(
        "N9-fuzzy-name-1p",
        listOf(
            firstPage(
                energie,
                address = listOf("Herrn", "Mohamad Mustermann", "Beispielallee 21", "12345 Beispielstadt"),
                info = listOf(
                    "Ihr Zeichen:" to "—", "Kundennummer:" to "60012987", "Rechnungsnr.:" to "AB-2026-10-4471",
                    "Telefon:" to "01234 600 10", "Datum:" to "27.09.2026",
                ),
                subject = "Abschlagsrechnung Oktober 2026",
                body = listOf(
                    "Sehr geehrter Herr Mustermann,",
                    "für die Stromlieferung an Ihre Abnahmestelle Beispielallee 21, 12345 Beispielstadt, stellen wir Ihnen den folgenden Abschlag in Rechnung.",
                    "Leistung||Zeitraum||Betrag €",
                    "Abschlag Strom||Oktober 2026||78,00",
                    "Bitte überweisen Sie 78,00 € bis zum 12.10.2026 unter Angabe der Rechnungsnummer AB-2026-10-4471 auf das folgende Konto: DE89 3704 0044 0532 0130 00 (Energie Beispielstadt GmbH).",
                    "Mit freundlichen Grüßen",
                    "Energie Beispielstadt GmbH",
                    "Kundenservice",
                ),
            ),
        ),
        DocTypes.BILL,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-27", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "78.00 EUR", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-12", "DUE_DATE"),
            ExpSlot(Slots.IBAN, CandidateKind.IBAN, IBAN1),
            ExpSlot(Slots.INVOICE_NO, CandidateKind.REFERENCE, "AB-2026-10-4471"),
            ExpSlot(Slots.CUSTOMER_NO, CandidateKind.REFERENCE, "60012987"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Energie Beispielstadt GmbH", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "Mohamad Mustermann"),
            ExpParty(PartyRole.SUBJECT_PERSON, "Mohamad Mustermann"),
        ),
        subject = "Abschlagsrechnung Oktober 2026",
        manifest = ManifestRoles("Energie Beispielstadt GmbH", addressees = listOf("Mohamad Mustermann"), subjectPersons = listOf("Mohamad Mustermann")),
    )

    private val praxis = Sender(
        letterhead = listOf("Kinder- und Jugendarztpraxis Beispiel", "Gesundheitsweg 5", "12345 Beispielstadt", "Tel. 01234 700 20", "praxis@kinderarzt-beispiel.example"),
        returnLine = "Kinder- und Jugendarztpraxis Beispiel · Gesundheitsweg 5 · 12345 Beispielstadt",
        right = false,
        footer = listOf(
            "Kinder- und Jugendarztpraxis Beispiel", "Sprechzeiten:", "Tel. 01234 700 20",
            "Dr. med. Anna Beispiel", "Mo–Fr 8:00–12:00 Uhr", "praxis@kinderarzt-beispiel.example",
        ),
    )

    val n10 = Letter(
        "N10-kinderarzt-termin-1p",
        listOf(
            firstPage(
                praxis,
                address = listOf("Erziehungsberechtigte von", "Adam Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                info = listOf("Ihr Zeichen:" to "—", "Praxis:" to "Dr. A. Beispiel", "Telefon:" to "01234 700 20", "Datum:" to "25.09.2026"),
                subject = "Terminerinnerung für Adam Mustermann",
                body = listOf(
                    "Sehr geehrte Eltern,",
                    "wir möchten Sie an den folgenden Termin für Ihr Kind Adam Mustermann erinnern:",
                    "Donnerstag, 12.11.2026 um 09:30 Uhr",
                    "Kinder- und Jugendarztpraxis Beispiel, Gesundheitsweg 5, 12345 Beispielstadt",
                    "Bitte bringen Sie die Versichertenkarte mit. Wenn Sie den Termin nicht wahrnehmen können, sagen Sie ihn bitte mindestens 24 Stunden vorher unter 01234 700 20 ab, damit wir den Platz anderweitig vergeben können.",
                    "Mit freundlichen Grüßen",
                    "Ihr Praxisteam",
                ),
            ),
        ),
        DocTypes.HEALTH,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-25", "LETTER_DATE"),
            ExpSlot(Slots.APPOINTMENT, DT, "2026-11-12T09:30", "APPOINTMENT"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Kinder- und Jugendarztpraxis Beispiel", kind = PartyKind.COMPANY),
            ExpParty(
                PartyRole.ADDRESSEE, "Erziehungsberechtigte von Adam Mustermann", PartyRelation.GUARDIAN_OF,
                quote = "Erziehungsberechtigte von Adam Mustermann",
            ),
            ExpParty(PartyRole.SUBJECT_PERSON, "Adam Mustermann"),
        ),
        extras = listOf(ExpExtra("Telefon zum Absagen", "cancellation_phone", CandidateKind.PHONE, "01234 700 20", confidence = "HIGH")),
        subject = "Terminerinnerung für Adam Mustermann",
        manifest = ManifestRoles(
            "Kinder- und Jugendarztpraxis Beispiel", addressees = listOf("Erziehungsberechtigte von Adam Mustermann"),
            subjectPersons = listOf("Adam Mustermann"), household = true,
        ),
    )

    // ── set 1 (the six original test documents) ─────────────────────────────────

    private val musterfirma = Sender(
        letterhead = listOf("Musterfirma GmbH", "Beispielweg 7", "12345 Beispielstadt", "Tel. 01234 567890", "info@musterfirma.example"),
        returnLine = "Musterfirma GmbH · Beispielweg 7 · 12345 Beispielstadt",
        right = false,
        footer = listOf(
            "Musterfirma GmbH · Beispielweg 7 · 12345 Beispielstadt", "Amtsgericht Beispielstadt HRB 000000", "Geschäftsführer: Max Beispiel",
        ),
    )

    val invoice = Letter(
        "invoice-2p",
        listOf(
            firstPage(
                musterfirma,
                address = listOf("Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                dateLine = "Beispielstadt, 28.09.2026",
                subject = "Rechnung Nr. RE-2026-0815",
                body = listOf(
                    "Kundennummer: KD-40417      Rechnungsdatum: 28.09.2026",
                    "Leistungszeitraum: September 2026",
                    "Sehr geehrte Frau Mustermann,",
                    "vielen Dank für Ihren Auftrag. Für die im September 2026 erbrachten Leistungen erlauben wir uns, Ihnen die folgenden Positionen in Rechnung zu stellen.",
                    "Konzeption Webportal||4 h||95,00||380,00",
                    "Softwarelizenz Modul B||1||149,90||149,90",
                ),
                totalPages = 2,
            ),
            continuation(
                musterfirma, 2, 2, "Musterfirma GmbH – Rechnung RE-2026-0815",
                listOf(
                    "Zusammenfassung||Betrag €",
                    "Nettobetrag||1.079,41",
                    "zzgl. 19 % MwSt. auf 1.079,41 €||205,09",
                    "Gesamtbetrag||1.284,50 €",
                    "Zahlungsbedingungen",
                    "Der Gesamtbetrag von 1.284,50 € ist zahlbar bis 15.10.2026 ohne Abzug auf das folgende Konto:",
                    "Kontoinhaber: Musterfirma GmbH",
                    "IBAN: DE89 3704 0044 0532 0130 00",
                    "BIC: COBADEFFXXX",
                    "Verwendungszweck: RE-2026-0815",
                    "Mit freundlichen Grüßen",
                    "Musterfirma GmbH",
                    "Buchhaltung",
                ),
            ),
        ),
        DocTypes.BILL,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-28", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "1284.50 EUR", "GROSS"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-15", "DUE_DATE"),
            ExpSlot(Slots.IBAN, CandidateKind.IBAN, IBAN1),
            ExpSlot(Slots.INVOICE_NO, CandidateKind.REFERENCE, "RE-2026-0815"),
            ExpSlot(Slots.CUSTOMER_NO, CandidateKind.REFERENCE, "KD-40417"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Musterfirma GmbH", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "Erika Mustermann"),
        ),
        extras = listOf(
            ExpExtra("Nettobetrag", "net_amount", CandidateKind.AMOUNT, "1079.41 EUR"),
            ExpExtra("MwSt.", "vat", CandidateKind.AMOUNT, "205.09 EUR"),
        ),
        subject = "Rechnung Nr. RE-2026-0815",
        manifest = ManifestRoles("Musterfirma GmbH", addressees = listOf("Erika Mustermann")),
    )

    private val finanzamt = Sender(
        letterhead = listOf("Finanzamt Beispielstadt"),
        returnLine = null,
        right = false,
        footer = listOf("Finanzamt Beispielstadt · Steuerweg 1 · 12345 Beispielstadt · Postfach 00 00 00 (fiktive Behörde)"),
    )

    private fun taxFiller(page: Int) = List(20) { "Absatz $page.$it: Die Festsetzung beruht auf den Angaben in Ihrer Erklärung und den beigefügten Anlagen." }

    val tax = Letter(
        "tax-long-7p",
        listOf(
            firstPage(
                finanzamt,
                address = listOf("Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                dateLine = "Beispielstadt, 24.09.2026",
                subject = "Einkommensteuerbescheid 2025",
                body = listOf(
                    "Festsetzung der Einkommensteuer und Nachzahlung",
                    "Steuernummer: 123/456/78901      Bescheiddatum: 24.09.2026      Veranlagungszeitraum: 2025",
                    "Sehr geehrte Frau Mustermann,",
                    "auf Grund Ihrer Einkommensteuererklärung für das Jahr 2025 wurde die Einkommensteuer wie in diesem Bescheid dargestellt festgesetzt.",
                    "Ergebnis: Nachzahlung 2.317,00 €",
                    "Festgesetzte Einkommensteuer 9.817,00 € abzüglich Vorauszahlungen 7.500,00 €",
                ),
                totalPages = 7,
            ),
        ) + (2..5).map { p ->
            continuation(finanzamt, p, 7, "Finanzamt Beispielstadt      Aktenzeichen: EST-2025-0047118", taxFiller(p))
        } + listOf(
            continuation(
                finanzamt, 6, 7, "Finanzamt Beispielstadt      Aktenzeichen: EST-2025-0047118",
                listOf(
                    "6. Rechtsbehelfsbelehrung",
                    "Gegen diesen Bescheid ist der Einspruch zulässig. Der Einspruch ist schriftlich einzureichen oder zur Niederschrift zu erklären.",
                    "Einspruch innerhalb eines Monats nach Bekanntgabe des Bescheids beim Finanzamt Beispielstadt, Steuerweg 1, 12345 Beispielstadt.",
                    "Zahlungsaufforderung",
                    "Die Abschlusszahlung von 2.317,00 € ist fällig am 03.11.2026. Bitte überweisen Sie den Betrag auf das Konto der Finanzkasse Beispielstadt: IBAN DE89 3704 0044 0532 0130 00, BIC COBADEFFXXX, Verwendungszweck: Steuernummer 123/456/78901 EST 2025.",
                ),
            ),
            continuation(
                finanzamt, 7, 7, "Finanzamt Beispielstadt      Aktenzeichen: EST-2025-0047118",
                listOf("Für Rückfragen nennen Sie bitte stets das Aktenzeichen EST-2025-0047118.", "Mit freundlichen Grüßen", "Im Auftrag", "Finanzamt Beispielstadt"),
            ),
        ),
        DocTypes.TAX,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-24", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "2317.00 EUR", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "2026-11-03", "DUE_DATE"),
            ExpSlot(Slots.OBJECTION_DEADLINE, D, "innerhalb eines Monats", "DEADLINE", quote = "innerhalb eines Monats"),
            ExpSlot(Slots.IBAN, CandidateKind.IBAN, IBAN1),
            ExpSlot(Slots.CASE_NO, CandidateKind.REFERENCE, "EST-2025-0047118"),
            ExpSlot(Slots.TAX_NO, CandidateKind.REFERENCE, "123/456/78901"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Finanzamt Beispielstadt", kind = PartyKind.AUTHORITY),
            ExpParty(PartyRole.ADDRESSEE, "Erika Mustermann"),
        ),
        extras = listOf(
            ExpExtra("Festgesetzte Einkommensteuer", "tax_assessed", CandidateKind.AMOUNT, "9817.00 EUR"),
            ExpExtra("Vorauszahlungen", "advance_payments", CandidateKind.AMOUNT, "7500.00 EUR"),
        ),
        subject = "Einkommensteuerbescheid 2025",
        manifest = ManifestRoles("Finanzamt Beispielstadt", addressees = listOf("Erika Mustermann")),
    )

    private val northwind = Sender(
        letterhead = listOf("Northwind Utilities Ltd.", "1 Example Road", "Sampletown SA1 0XX", "Tel. 0000 000 0000", "help@northwind.example"),
        returnLine = "Northwind Utilities Ltd. · 1 Example Road · Sampletown SA1 0XX",
        right = false,
        footer = listOf("Northwind Utilities Ltd. · 1 Example Road · Sampletown SA1 0XX · Registered in England No. 00000000 (fictional)"),
    )

    val english = Letter(
        "english-ambiguous-3p",
        listOf(
            firstPage(
                northwind,
                address = listOf("Mr John Sample", "22 Placeholder Lane", "Exampleton EX2 3PL"),
                dateLine = "Sampletown, 26 September 2026",
                subject = "Your account NU-77120934 – overdue balance and payment plan",
                body = listOf(
                    "Reference: NU/REM/2026/03318",
                    "Dear Mr Sample,",
                    "Our records show that your gas and electricity account is currently overdue. As of 26 September 2026 the overdue balance on your account is £142.80. This amount relates to the billing periods June to August 2026 and has not yet been settled.",
                    "We ask that you reply to this letter by 10 October 2026, either by telephone, by e-mail or by returning the reply slip enclosed with this letter.",
                ),
                totalPages = 3,
            ),
            continuation(
                northwind, 2, 3, "Northwind Utilities Ltd. – Account NU-77120934",
                listOf(
                    "Terms and conditions of payment arrangements",
                    "1. Scope. These terms apply to every payment arrangement offered by Northwind Utilities Ltd. to a domestic customer.",
                    "2. Agreement. A payment arrangement becomes binding when the Customer confirms it in writing.",
                ),
            ),
            continuation(
                northwind, 3, 3, "Northwind Utilities Ltd. – Account NU-77120934",
                listOf(
                    "Our proposal: a payment plan for your account",
                    "To make repayment easier we propose that the overdue balance is added to your regular payments. Your new monthly instalment would be £57.00.",
                    "Item||Detail",
                    "Account number||NU-77120934",
                    "New monthly instalment||£57.00",
                    "Direct debit starts||1 November 2026",
                    "Yours sincerely,",
                    "Customer Accounts Team",
                    "Northwind Utilities Ltd.",
                ),
            ),
        ),
        DocTypes.REMINDER,
        "en",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-26", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "142.80 GBP", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-10", "DEADLINE"),
            ExpSlot(Slots.REFERENCE, CandidateKind.REFERENCE, "NU/REM/2026/03318"),
            ExpSlot(Slots.CUSTOMER_NO, CandidateKind.REFERENCE, "NU-77120934"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Northwind Utilities Ltd.", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "John Sample", quote = "John Sample"),
        ),
        extras = listOf(
            ExpExtra("New monthly instalment", "new_instalment", CandidateKind.AMOUNT, "57.00 GBP"),
            ExpExtra("Direct debit starts", "direct_debit_start", D, "2026-11-01"),
        ),
        subject = "Your account NU-77120934 – overdue balance and payment plan",
        manifest = ManifestRoles("Northwind Utilities Ltd.", addressees = listOf("John Sample")),
    )

    private val markt = Sender(letterhead = listOf("BEISPIELMARKT"), returnLine = null, right = false, footer = emptyList())

    val receipt = Letter(
        "receipt-noise-1p",
        listOf(
            listOf(
                Din.b("BEISPIELMARKT", 0.30f, 0.04f),
                Din.b("Musterstraße 12", 0.30f, 0.06f),
                Din.b("12345 Beispielstadt", 0.30f, 0.075f),
                Din.b("Vollmilch 1,5% 1L", 0.10f, 0.14f),
                Din.b("1,19 A", 0.70f, 0.14f),
                Din.b("Landbrot 750g", 0.10f, 0.16f),
                Din.b("2,49 A", 0.70f, 0.16f),
                Din.b("SUMME EUR", 0.10f, 0.30f),
                Din.b("23,47", 0.70f, 0.30f),
                Din.b("Kartenzahlung girocard", 0.10f, 0.33f),
                Din.b("23,47", 0.70f, 0.33f),
                Din.b("Terminal-ID: 52847196", 0.10f, 0.42f),
                Din.b("Trace-Nr.: 004217", 0.10f, 0.44f),
                Din.b("Datum: 27.09.2026  18:42", 0.10f, 0.50f),
                Din.b("Bon-Nr.: 4711   Kasse: 03   Bediener: 017", 0.10f, 0.52f),
                Din.b("TSE-Signatur:", 0.10f, 0.56f),
                Din.b("Qx9fA3kLm0PzR7vTb2WcYhJdNe5UsGi8oXaK1lZrQvB4tHnMyP6cDwE3jFgSuIoLp9AqRzTx7VbNmK0dCyWeHf2G==", 0.10f, 0.58f),
                Din.b("Transaktionsnummer: 1049233", 0.10f, 0.62f),
                Din.b("4 0 1 2 3 4 5 6 7 8 9 0 1 2", 0.10f, 0.70f),
                Din.b("Vielen Dank für Ihren Einkauf!", 0.10f, 0.76f),
            ),
        ),
        DocTypes.RECEIPT,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, DT, "2026-09-27T18:42", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "23.47 EUR", "GROSS"),
            ExpSlot(Slots.RECEIPT_NO, CandidateKind.REFERENCE, "4711"),
        ),
        parties = listOf(ExpParty(PartyRole.SENDER, "BEISPIELMARKT", kind = PartyKind.COMPANY, quote = "BEISPIELMARKT")),
        subject = null,
        manifest = ManifestRoles("Beispielmarkt"),
    ).also { require(markt.letterhead.isNotEmpty()) }

    private val stadtwerke = Sender(
        letterhead = listOf("Stadtwerke Beispielstadt", "Energieweg 3", "12345 Beispielstadt", "Tel. 01234 000111", "kundenservice@stadtwerke-beispiel.example"),
        returnLine = "Stadtwerke Beispielstadt · Energieweg 3 · 12345 Beispielstadt",
        right = false,
        footer = listOf("Stadtwerke Beispielstadt · Energieweg 3 · 12345 Beispielstadt · Gläubiger-ID DE00ZZZ00000000000"),
    )

    val degraded = Letter(
        "degraded-3p",
        listOf(
            firstPage(
                stadtwerke,
                address = listOf("Erika Mustermann", "Musterstraße 12", "54321 Beispieldorf"),
                dateLine = "Beispielstadt, 25.09.2026",
                subject = "Änderung Ihres Abschlags ab 01.12.2026",
                body = listOf(
                    "Vertragskonto: 3300 5521 08      Zählernummer: 1EMH0000123456",
                    "Sehr geehrte Frau Mustermann,",
                    "aufgrund der gestiegenen Beschaffungskosten und Ihres bisherigen Verbrauchs passen wir Ihren monatlichen Abschlag für Strom an. Ihr neuer Abschlag beträgt 89,00 € monatlich und gilt ab dem 01.12.2026. Bisher haben Sie 74,00 € monatlich gezahlt.",
                    "Mit freundlichen Grüßen",
                    "Ihre Stadtwerke Beispielstadt",
                ),
                totalPages = 3,
            ),
            continuation(
                stadtwerke, 2, 3, "Stadtwerke Beispielstadt – Vertragskonto 3300 5521 08",
                listOf(
                    "Verbrauchsübersicht und Abschlagsplan",
                    "Ihr Abschlagsplan: Erster Abschlag in neuer Höhe am 01.12.2026, danach jeweils zum 1. eines Monats. Die Einzugsbeträge werden von folgendem Konto abgebucht: IBAN DE89 3704 0044 0532 0130 00 (Kontoinhaberin: Erika Mustermann).",
                ),
            ),
            continuation(stadtwerke, 3, 3, "Stadtwerke Beispielstadt – Vertragskonto 3300 5521 08", emptyList()),
        ),
        DocTypes.INSURANCE,
        "de",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-25", "LETTER_DATE"),
            ExpSlot(Slots.NEW_AMOUNT, CandidateKind.AMOUNT, "89.00 EUR", "INSTALMENT"),
            ExpSlot(Slots.PREVIOUS_AMOUNT, CandidateKind.AMOUNT, "74.00 EUR", "PREVIOUS"),
            ExpSlot(Slots.EFFECTIVE_DATE, D, "2026-12-01", "EFFECTIVE_FROM"),
            ExpSlot(Slots.CONTRACT_NO, CandidateKind.REFERENCE, "3300 5521 08"),
            ExpSlot(Slots.IBAN, CandidateKind.IBAN, IBAN1),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Stadtwerke Beispielstadt", kind = PartyKind.COMPANY),
            ExpParty(PartyRole.ADDRESSEE, "Erika Mustermann"),
        ),
        extras = listOf(ExpExtra("Zählernummer", "meter_number", CandidateKind.REFERENCE, "1EMH0000123456")),
        subject = "Änderung Ihres Abschlags ab 01.12.2026",
        manifest = ManifestRoles("Stadtwerke Beispielstadt", addressees = listOf("Erika Mustermann")),
    )

    val arabic = Letter(
        "arabic-rtl-1p",
        listOf(
            listOf(
                Din.b("شركة المثال للخدمات المحدودة", 0.45f, 0.05f, w = 0.42f),
                Din.b("شارع النموذج 5، مدينة المثال", 0.45f, 0.07f, w = 0.42f),
                Din.b("السيدة إيريكا موستيرمان", 0.55f, 0.18f, w = 0.32f),
                Din.b("شارع الأمثلة 12", 0.55f, 0.20f, w = 0.32f),
                Din.b("54321 مدينة العينة", 0.55f, 0.22f, w = 0.32f),
                Din.b("التاريخ ٢٨.٠٩.٢٠٢٦", 0.55f, 0.28f, w = 0.32f),
                Din.b("الموضوع تذكير بسداد الفاتورة رقم ٠٤١٧ لسنة ٢٠٢٦", 0.35f, 0.34f, w = 0.52f),
                Din.b("نفيدكم بأن المبلغ المستحق على حسابكم هو ٤٥٠,٠٠ يورو.", 0.35f, 0.40f, w = 0.52f),
                Din.b("نرجو تسديد المبلغ في موعد أقصاه ٢٠.١٠.٢٠٢٦.", 0.35f, 0.44f, w = 0.52f),
                Din.b("Zusammenfassung (Deutsch): Zahlungserinnerung zu Rechnung 2026-0417.", 0.10f, 0.80f),
                Din.b("Betrag: 450,00 €, zahlbar bis 20.10.2026.", 0.10f, 0.83f),
                Din.b("Absender: Al-Mithal Services GmbH (fiktiv), Beispielstadt.", 0.10f, 0.86f),
                Din.b("Seite 1 von 1", 0.84f, 0.96f),
            ),
        ),
        DocTypes.REMINDER,
        "ar",
        slots = listOf(
            ExpSlot(Slots.LETTER_DATE, D, "2026-09-28", "LETTER_DATE"),
            ExpSlot(Slots.TOTAL, CandidateKind.AMOUNT, "450.00 EUR", "TOTAL_DUE"),
            ExpSlot(Slots.DUE_DATE, D, "2026-10-20", "DUE_DATE"),
            ExpSlot(Slots.INVOICE_NO, CandidateKind.REFERENCE, "2026-0417"),
        ),
        parties = listOf(
            ExpParty(PartyRole.SENDER, "Al-Mithal Services GmbH", kind = PartyKind.COMPANY, quote = "Al-Mithal Services GmbH"),
            ExpParty(PartyRole.ADDRESSEE, "إيريكا موستيرمان", quote = "إيريكا موستيرمان"),
        ),
        subject = "الموضوع تذكير بسداد الفاتورة رقم ٠٤١٧ لسنة ٢٠٢٦",
        manifest = ManifestRoles("Al-Mithal Services GmbH", addressees = listOf("إيريكا موستيرمان")),
    )

    val all: List<Letter> = listOf(invoice, tax, english, receipt, degraded, arabic, n1, n2, n3, n4, n5, n6, n7, n8, n9, n10)

    /** The set-2 letters, whose manifest lists roles in full. */
    val withRoles: List<Letter> = listOf(n1, n2, n3, n4, n5, n6, n7, n8, n9, n10)
}

/**
 * Shorthand for the families the test letters stand for. The names are the kinds the letters were written as (a bill, a reminder, a
 * tax letter...); each maps to the family and the topics a document of that kind has today (`LegacyTypes`).
 */
internal val Letter.topics: List<String> get() = LETTER_TOPICS[id].orEmpty()

/** The topics a model would find in each letter (what it is about, besides its family); a letter not listed is about none of them. */
private val LETTER_TOPICS = mapOf(
    "N2-kfz-verlaengerung-2p" to listOf("insurance"),
    "N3-schule-familie-2p" to listOf("school_education"),
    "N4-zhd-firma-1p" to listOf("insurance"),
    "N10-kinderarzt-termin-1p" to listOf("health"),
    "tax-long-7p" to listOf("tax", "government"),
    "degraded-3p" to listOf("insurance"),
)

internal object DocTypes {
    val BILL = ExtractionSchema.INVOICE_BILL
    val REMINDER = ExtractionSchema.INVOICE_BILL
    val TAX = ExtractionSchema.OFFICIAL_LETTER
    val HEALTH = ExtractionSchema.MEDICAL
    val INSURANCE = ExtractionSchema.CONTRACT_POLICY
    val SCHOOL = ExtractionSchema.OFFICIAL_LETTER
    val RECEIPT = ExtractionSchema.RECEIPT
    val INFO = ExtractionSchema.OFFICIAL_LETTER
}
