package com.postsaimanager.core.domain.extraction.candidates

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind.AMOUNT
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind.DATE
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind.IBAN
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind.PHONE
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind.REFERENCE
import com.postsaimanager.core.domain.extraction.candidates.LabelKind.*
import com.postsaimanager.core.domain.extraction.candidates.ReferenceSubtype.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate

/**
 * Table-driven coverage test generated from the ground-truth manifests of test set 1
 * (invoice-2p, tax-long-7p, english-ambiguous-3p, receipt-noise-1p, degraded-3p, arabic-rtl-1p)
 * and set 2 (N1..N10). Every expected date, amount, IBAN and reference is rebuilt as the OCR
 * lines that appear on the letter (phrasing taken from the generators and manifest evidence;
 * table rows are label/value cell pairs), and must come back as a candidate with the right
 * normalised value, page, kind, subtype and label.
 */
class ManifestCoverageTest {

    private data class Fact(
        val what: String,
        val kind: CandidateKind,
        val norm: String,
        val page: Int = 1,
        val label: String? = null,
        val labelKinds: Set<LabelKind>? = null,
        val subtype: ReferenceSubtype? = null,
    )

    private class DocCase(val id: String, val pages: List<TestPage>, val letter: String?, val facts: List<Fact>)

    private fun d(what: String, iso: String, page: Int = 1, label: String? = null, vararg lk: LabelKind) =
        Fact(what, DATE, iso, page, label, lk.toSet().ifEmpty { null })

    private fun a(what: String, norm: String, page: Int = 1, label: String? = null, vararg lk: LabelKind) =
        Fact(what, AMOUNT, norm, page, label, lk.toSet().ifEmpty { null })

    private fun i(iban: String, page: Int = 1) = Fact("iban", IBAN, iban, page)

    private fun r(what: String, value: String, sub: ReferenceSubtype, page: Int = 1, label: String? = null) =
        Fact(what, REFERENCE, value, page, label, null, sub)

    private val cases: List<DocCase> = listOf(
        // ── set 1 ────────────────────────────────────────────────────────────────
        DocCase(
            "invoice-2p",
            listOf(
                page(
                    "Rechnung Nr. RE-2026-0815",
                    "Kundennummer: KD-40417      Rechnungsdatum: 28.09.2026",
                    "Leistungszeitraum: September 2026",
                    "Sehr geehrte Frau Mustermann,",
                ),
                page(
                    "Zusammenfassung||Betrag €",
                    "Nettobetrag||1.079,41",
                    "zzgl. 19 % MwSt. auf 1.079,41 €||205,09",
                    "Gesamtbetrag||1.284,50 €",
                    "Der Gesamtbetrag von 1.284,50 € ist zahlbar bis 15.10.2026 ohne Abzug auf das folgende Konto:",
                    "Kontoinhaber: Musterfirma GmbH",
                    "IBAN: DE89 3704 0044 0532 0130 00",
                    "BIC: COBADEFFXXX",
                    "Verwendungszweck: RE-2026-0815",
                ),
            ),
            "2026-09-28",
            listOf(
                r("reference_number", "RE-2026-0815", INVOICE_NO, 1, "Rechnung Nr"),
                r("customer_number", "KD-40417", CUSTOMER_NO, 1, "Kundennummer"),
                d("invoice_date", "2026-09-28", 1, "Rechnungsdatum", INVOICE_DATE),
                a("net_amount", "1079.41 EUR", 2, "Netto", NET),
                a("vat", "205.09 EUR", 2, "MwSt", VAT),
                a("amount (Gesamtbetrag)", "1284.50 EUR", 2, "Gesamtbetrag", GROSS),
                d("deadline", "2026-10-15", 2, "zahlbar bis", DUE_DATE),
                i("DE89370400440532013000", 2),
            ),
        ),
        DocCase(
            "tax-long-7p",
            listOf(
                page(
                    "Aktenzeichen: EST-2025-0047118",
                    "Beispielstadt, 24.09.2026",
                    "Einkommensteuerbescheid 2025",
                    "Steuernummer: 123/456/78901      Bescheiddatum: 24.09.2026      Veranlagungszeitraum: 2025",
                    "Ergebnis: Nachzahlung 2.317,00 €",
                ),
                page(), // page 2
                page("Festgesetzte Einkommensteuer 9.817,00 € abzüglich Vorauszahlungen 7.500,00 €"),
                page(), page(),
                page(
                    "Gegen diesen Bescheid ist der Einspruch zulässig. Der Einspruch ist schriftlich einzureichen oder zur Niederschrift zu erklären.",
                    "Einspruch innerhalb eines Monats nach Bekanntgabe des Bescheids beim Finanzamt Beispielstadt, Steuerweg 1, 12345 Beispielstadt.",
                    "Die Abschlusszahlung von 2.317,00 € ist fällig am 03.11.2026. Bitte überweisen Sie den Betrag auf das Konto der " +
                        "Finanzkasse Beispielstadt: IBAN DE89 3704 0044 0532 0130 00, BIC COBADEFFXXX, Verwendungszweck: Steuernummer 123/456/78901 EST 2025.",
                ),
            ),
            "2026-09-24",
            listOf(
                r("reference_number (Aktenzeichen)", "EST-2025-0047118", CASE_NO, 1, "Aktenzeichen"),
                r("tax_number", "123/456/78901", TAX_NO, 1, "Steuernummer"),
                a("amount (Nachzahlung)", "2317.00 EUR", 1, "Nachzahlung", TOTAL_DUE),
                d("letter_date", "2026-09-24", 1, null, LETTER_DATE),
                d("due_date (Zahlung)", "2026-11-03", 6, "fällig am", DUE_DATE),
                i("DE89370400440532013000", 6),
                a("festgesetzte Einkommensteuer", "9817.00 EUR", 3, "Einkommensteuer", TAX_ASSESSED),
                a("Vorauszahlungen", "7500.00 EUR", 3, "Vorauszahlungen", ADVANCE),
            ),
        ),
        DocCase(
            "english-ambiguous-3p",
            listOf(
                page(
                    "Sampletown, 26 September 2026",
                    "Your account NU-77120934 – overdue balance and payment plan",
                    "Reference: NU/REM/2026/03318",
                    "Our records show that your gas and electricity account is currently overdue. As of 26 September 2026 the overdue " +
                        "balance on your account is £142.80. This amount relates to the billing periods June to August 2026 and has not yet been settled.",
                    "We would like to help you resolve this without further reminders or additional charges. We ask that you reply to this " +
                        "letter by 10 October 2026, either by telephone, by e-mail or by returning the reply slip enclosed with this letter.",
                ),
                page(),
                page(
                    "To make repayment easier we propose that the overdue balance is added to your regular payments. Your new monthly " +
                        "instalment would be £57.00, which covers your estimated ongoing usage and a contribution towards the overdue balance.",
                    "Account number||NU-77120934",
                    "New monthly instalment||£57.00",
                    "Direct debit starts||1 November 2026",
                ),
            ),
            "2026-09-26",
            listOf(
                r("reference_number", "NU/REM/2026/03318", OTHER, 1, "Reference"),
                r("account_number", "NU-77120934", ACCOUNT_NO, 1, "account"),
                r("account_number (table)", "NU-77120934", ACCOUNT_NO, 3, "Account number"),
                a("amount (overdue balance)", "142.80 GBP", 1, "overdue balance", TOTAL_DUE),
                d("deadline (reply by)", "2026-10-10", 1, "by", DEADLINE),
                a("amount (new monthly instalment)", "57.00 GBP", 3, "instalment", ADVANCE),
                d("date (direct debit starts)", "2026-11-01", 3, "starts", EFFECTIVE_FROM),
                d("letter_date", "2026-09-26", 1, null, LETTER_DATE),
            ),
        ),
        DocCase(
            "receipt-noise-1p",
            listOf(
                page(
                    "BEISPIELMARKT",
                    "Kartenzahlung girocard||23,47",
                    "SUMME EUR||23,47",
                    "Datum: 27.09.2026  18:42",
                    "Bon-Nr.: 4711   Kasse: 03   Bediener: 017",
                ),
            ),
            "2026-09-27",
            listOf(
                a("amount (Summe)", "23.47 EUR", 1, "SUMME", GROSS),
                Fact("date+time", CandidateKind.DATETIME, "2026-09-27T18:42", 1, "Datum", setOf(LETTER_DATE)),
                r("receipt_number (Bon-Nr.)", "4711", RECEIPT_NO, 1, "Bon-Nr"),
            ),
        ),
        DocCase(
            "degraded-3p",
            listOf(
                page(
                    "Beispielstadt, 25.09.2026",
                    "Änderung Ihres Abschlags ab 01.12.2026",
                    "Vertragskonto: 3300 5521 08      Zählernummer: 1EMH0000123456",
                    "aufgrund der gestiegenen Beschaffungskosten und Ihres bisherigen Verbrauchs passen wir Ihren monatlichen Abschlag für Strom an. " +
                        "Ihr neuer Abschlag beträgt 89,00 € monatlich und gilt ab dem 01.12.2026. Bisher haben Sie 74,00 € monatlich gezahlt.",
                ),
                page(
                    "Ihr Abschlagsplan: Erster Abschlag in neuer Höhe am 01.12.2026, danach jeweils zum 1. eines Monats. Die Einzugsbeträge " +
                        "werden von folgendem Konto abgebucht: IBAN DE89 3704 0044 0532 0130 00 (Kontoinhaberin: Erika Mustermann).",
                ),
                page(),
            ),
            "2026-09-25",
            listOf(
                a("amount (neuer Abschlag)", "89.00 EUR", 1, "Abschlag", ADVANCE),
                d("date (gültig ab)", "2026-12-01", 1, "ab", EFFECTIVE_FROM),
                a("previous_amount", "74.00 EUR", 1, "Bisher", PREVIOUS),
                r("contract_account", "3300 5521 08", CONTRACT_NO, 1, "Vertragskonto"),
                r("meter_number", "1EMH0000123456", METER_NO, 1, "Zählernummer"),
                d("letter_date", "2026-09-25", 1, null, LETTER_DATE),
                i("DE89370400440532013000", 2),
            ),
        ),
        DocCase(
            "arabic-rtl-1p",
            listOf(
                page(
                    "التاريخ ٢٨.٠٩.٢٠٢٦",
                    "الموضوع تذكير بسداد الفاتورة رقم ٠٤١٧ لسنة ٢٠٢٦",
                    "نفيدكم بأن المبلغ المستحق على حسابكم هو 450,00 يورو.",
                    "نرجو تسديد المبلغ في موعد أقصاه 20.10.2026.",
                    "Zusammenfassung (Deutsch): Zahlungserinnerung zu Rechnung 2026-0417.",
                    "Betrag: 450,00 €, zahlbar bis 20.10.2026.",
                ),
            ),
            "2026-09-28",
            listOf(
                a("amount (Arabic line)", "450.00 EUR", 1, "المستحق", TOTAL_DUE),
                a("amount (German summary)", "450.00 EUR", 1, "Betrag", GENERIC_AMOUNT),
                d("deadline (Arabic line)", "2026-10-20", 1, null, DEADLINE),
                d("deadline (German summary)", "2026-10-20", 1, "zahlbar bis", DUE_DATE),
                r("reference_number", "2026-0417", INVOICE_NO, 1, "Rechnung"),
                d("letter_date (Arabic-Indic digits)", "2026-09-28", 1, null, LETTER_DATE),
            ),
        ),
        // ── set 2 ────────────────────────────────────────────────────────────────
        DocCase(
            "N1-mahnung-telco-qr-1p",
            listOf(
                page(
                    "@RET Nordlicht Mobilfunk GmbH · Beispielweg 7 · 12345 Beispielstadt",
                    "Ihr Zeichen:||—",
                    "Unser Zeichen:||FIB-Mahn 4402917",
                    "Kundennummer:||4402917",
                    "Telefon:||0800 555 0199",
                    "Datum:||25.09.2026",
                    "Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204",
                    "zu unserer Rechnung 2026-08-771204 vom 05.08.2026 konnten wir bis heute keinen Zahlungseingang feststellen. " +
                        "Die Rechnung war am 19.08.2026 fällig. Falls sich Ihre Zahlung mit diesem Schreiben überschnitten hat, betrachten Sie es bitte als gegenstandslos.",
                    "Mahngebühr||5,00",
                    "Offener Gesamtbetrag||64,98",
                    "Bitte überweisen Sie den offenen Betrag von 64,98 € zahlbar innerhalb von 14 Tagen nach Zugang dieses Schreibens auf das unten genannte Konto.",
                    "Kontoinhaber: Nordlicht Mobilfunk GmbH",
                    "IBAN: DE02 1203 0000 0000 2020 51",
                    "BIC: COBADEFFXXX",
                    "Verwendungszweck: RE 2026-08-771204 Kd 4402917 Mahnung",
                ),
            ),
            "2026-09-25",
            listOf(
                r("reference_number (Rechnung)", "2026-08-771204", INVOICE_NO, 1, "Rechnung"),
                r("customer_number", "4402917", CUSTOMER_NO, 1, "Kundennummer"),
                r("unser zeichen", "FIB-Mahn 4402917", OTHER, 1, "Unser Zeichen"),
                d("date", "2026-09-25", 1, "Datum", LETTER_DATE),
                d("previous_invoice_date", "2026-08-05", 1, "vom", REFERENCED_DATE),
                d("original_due_date", "2026-08-19", 1, "fällig", DUE_DATE),
                a("amount (offen inkl. Mahngebühr)", "64.98 EUR", 1, "offenen Betrag", TOTAL_DUE),
                a("amount (offener Gesamtbetrag, table)", "64.98 EUR", 1, "Offener Gesamtbetrag", TOTAL_DUE),
                a("fee", "5.00 EUR", 1, "Mahngebühr", FEE),
                i("DE02120300000000202051"),
                Fact("phone", PHONE, "0800 555 0199", 1, "Telefon"),
            ),
        ),
        DocCase(
            "N2-kfz-verlaengerung-2p",
            listOf(
                page(
                    "@ADDR Herrn und Frau",
                    "@ADDR Max und Erika Mustermann",
                    "Versicherungsnr.:||KFZ-4471-882-19",
                    "Datum:||14.09.2026",
                    "Ihre Kfz-Versicherung mit der Versicherungsnummer KFZ-4471-882-19 verlängert sich zum 01.01.2027 um ein weiteres Jahr.",
                    "Der neue Jahresbeitrag beträgt 612,40 € (bisher 579,10 €). Der Beitrag wird wie gewohnt per SEPA-Lastschrift eingezogen.",
                ),
                page(
                    "Wegen der Beitragserhöhung können Sie Ihren Vertrag außerordentlich kündigen. Ihre Kündigung muss uns bis 30.11.2026 in " +
                        "Textform (Brief, E-Mail) zugehen; der Vertrag endet dann zum 31.12.2026.",
                ),
            ),
            "2026-09-14",
            listOf(
                r("policy_number", "KFZ-4471-882-19", POLICY_NO, 1, "Versicherungsnr"),
                r("policy_number (body)", "KFZ-4471-882-19", POLICY_NO, 1, "Versicherungsnummer"),
                d("date", "2026-09-14", 1, "Datum", LETTER_DATE),
                a("old_premium", "579.10 EUR", 1, "bisher", PREVIOUS),
                a("new_premium", "612.40 EUR", 1, "Jahresbeitrag", PREMIUM),
                d("new premium from", "2027-01-01", 1),
                d("deadline (Sonderkündigung)", "2026-11-30", 2, "bis", DEADLINE),
                d("contract end", "2026-12-31", 2, "endet", CONTRACT_END),
            ),
        ),
        DocCase(
            "N3-schule-familie-2p",
            listOf(
                page(
                    "@ADDR Familie",
                    "@ADDR Mustermann",
                    "Datum:||28.09.2026",
                    "Klassenausflug der Klasse 3b am 24.11.2026",
                    "Die Kosten für Fahrt, Eintritt und Workshop betragen 35,00 € pro Kind.",
                ),
                page(
                    "Bitte unterschrieben bis Freitag, 16.10.2026 zurückgeben.",
                    "Rückmeldung – Klassenausflug 24.11.2026 (Klasse 3b)",
                ),
            ),
            "2026-09-28",
            listOf(
                d("date", "2026-09-28", 1, "Datum", LETTER_DATE),
                d("event_date", "2026-11-24", 1, "Klassenausflug", EVENT_DATE),
                a("amount", "35.00 EUR", 1, "Kosten", COST),
                d("deadline (Rückgabe Abschnitt)", "2026-10-16", 2, "bis", DEADLINE),
            ),
        ),
        DocCase(
            "N4-zhd-firma-1p",
            listOf(
                page(
                    "@ADDR Mustermann Consulting GmbH",
                    "@ADDR z. Hd. Frau Erika Mustermann",
                    "Ihr Zeichen:||EM/2026",
                    "Unser Zeichen:||WV-2291",
                    "Datum:||23.09.2026",
                    "Wartungsvertrag WV-2291 – Verlängerung ab 01.01.2027",
                    "Ihr Wartungsvertrag WV-2291 für die Multifunktionsgeräte der Mustermann Consulting GmbH läuft zum 31.12.2026 aus.",
                    "Wartung 2 Geräte inkl. Toner-Service||01.01.–31.12.2027||1.428,00 €",
                    "Bitte bestätigen Sie uns die Verlängerung bis 15.10.2026 durch Rücksendung des unterschriebenen Angebots oder per E-Mail an " +
                        "vertrieb@buero-partner.example. Ohne Rückmeldung endet der Vertrag zum 31.12.2026.",
                ),
            ),
            "2026-09-23",
            listOf(
                r("reference_number", "WV-2291", CONTRACT_NO, 1, "vertrag"),
                r("unser zeichen", "WV-2291", OTHER, 1, "Unser Zeichen"),
                d("date", "2026-09-23", 1, "Datum", LETTER_DATE),
                a("amount (netto p. a.)", "1428.00 EUR", 1),
                d("deadline", "2026-10-15", 1, "bis", DEADLINE),
                d("contract_end", "2026-12-31", 1, "läuft zum", CONTRACT_END),
                d("contract_end (Ohne Rückmeldung)", "2026-12-31", 1, "endet", CONTRACT_END),
                Fact("email", CandidateKind.EMAIL, "vertrieb@buero-partner.example"),
            ),
        ),
        DocCase(
            "N5-co-familie-1p",
            listOf(
                page(
                    "@ADDR Herrn",
                    "@ADDR Jonas Mustermann",
                    "@ADDR c/o Familie Beispiel",
                    "@ADDR Beispielgasse 3",
                    "@ADDR 12345 Beispielstadt",
                    "Matrikelnummer:||1234567",
                    "Datum:||24.09.2026",
                    "für die Rückmeldung zum Wintersemester 2026/27 (Matrikelnummer 1234567) bitten wir Sie, den Semesterbeitrag in Höhe von " +
                        "187,50 € bis zum 12.10.2026 zu überweisen.",
                    "Empfänger: Landeskasse Beispiel",
                    "IBAN: DE02 1001 0010 0006 8201 01",
                ),
            ),
            "2026-09-24",
            listOf(
                r("matriculation_number", "1234567", MATRICULATION_NO, 1, "Matrikelnummer"),
                d("date", "2026-09-24", 1, "Datum", LETTER_DATE),
                a("amount", "187.50 EUR", 1, "Semesterbeitrag", PREMIUM),
                d("deadline", "2026-10-12", 1, "bis zum", DEADLINE),
                i("DE02100100100006820101"),
            ),
        ),
        DocCase(
            "N6-nebenkosten-3p",
            listOf(
                page(
                    "Unser Zeichen:||BK-2025-0318",
                    "Datum:||22.09.2026",
                    "für den Abrechnungszeitraum 01.01.2025 bis 31.12.2025. Ihre Wohnfläche beträgt 68,5 m² von insgesamt 548,0 m².",
                ),
                page("Summe Ihrer Betriebskosten||2.826,32"),
                page(
                    "Geleistete Vorauszahlungen (12 × 220,00 €)||2.640,00",
                    "Nachzahlung||186,32",
                    "Der Nachzahlungsbetrag von 186,32 € ist fällig bis 31.10.2026. Bitte überweisen Sie ihn auf das folgende Konto:",
                    "IBAN: DE89 3704 0044 0532 0130 00",
                    "Ab 01.11.2026 beträgt Ihre monatliche Betriebskostenvorauszahlung 245,00 € (bisher 220,00 €).",
                ),
            ),
            "2026-09-22",
            listOf(
                d("date", "2026-09-22", 1, "Datum", LETTER_DATE),
                d("period start", "2025-01-01", 1, "zeitraum", PERIOD),
                d("period end", "2025-12-31", 1, null, PERIOD),
                r("reference_number", "BK-2025-0318", OTHER, 1, "Unser Zeichen"),
                a("total_costs", "2826.32 EUR", 2, null, GROSS, COST),
                a("advance_paid", "2640.00 EUR", 3, "Vorauszahlungen", ADVANCE),
                a("amount (Nachzahlung, table)", "186.32 EUR", 3, "Nachzahlung", TOTAL_DUE),
                a("amount (Nachzahlung, text)", "186.32 EUR", 3, "Nachzahlungsbetrag", TOTAL_DUE),
                d("deadline", "2026-10-31", 3, "fällig bis", DUE_DATE),
                a("new_monthly_advance", "245.00 EUR", 3, "vorauszahlung", ADVANCE),
                d("new advance from", "2026-11-01", 3, "Ab", EFFECTIVE_FROM),
                i("DE89370400440532013000", 3),
            ),
        ),
        DocCase(
            "N7-beitragsservice-1p",
            listOf(
                page(
                    "Beitragsnummer:||987 654 321",
                    "Datum:||26.09.2026",
                    "Zahlungserinnerung – Beitragsnummer 987 654 321",
                    "01.10.2026 – 31.12.2026||15.10.2026||55,08",
                    "Bitte zahlen Sie den Betrag von 55,08 € bis zum 15.10.2026 unter Angabe Ihrer Beitragsnummer 987 654 321 auf das unten stehende Konto.",
                    "IBAN: DE02 1001 0010 0006 8201 01",
                ),
            ),
            "2026-09-26",
            listOf(
                r("reference_number (Beitragsnummer, info block)", "987 654 321", BEITRAGSNUMMER, 1, "Beitragsnummer"),
                r("reference_number (Beitragsnummer, subject)", "987 654 321", BEITRAGSNUMMER, 1, "Beitragsnummer"),
                d("date", "2026-09-26", 1, "Datum", LETTER_DATE),
                d("period start", "2026-10-01", 1, null, PERIOD),
                d("period end", "2026-12-31", 1, null, PERIOD),
                a("amount", "55.08 EUR", 1, "Betrag", GENERIC_AMOUNT),
                d("deadline", "2026-10-15", 1, "bis zum", DEADLINE),
                i("DE02100100100006820101"),
            ),
        ),
        DocCase(
            "N8-info-bank-noaction-1p",
            listOf(
                page(
                    "Kundennummer:||7788123",
                    "Datum:||21.09.2026",
                    "Ab dem 01.11.2026 steht Ihnen unser neues Kundenportal zur Verfügung, in dem Sie Ihre Kontoumsätze übersichtlich finden.",
                    "Unser Kundenservice ist Montag bis Freitag von 8 bis 18 Uhr unter 01234 400 55 für Sie da.",
                ),
            ),
            "2026-09-21",
            listOf(
                r("customer_number", "7788123", CUSTOMER_NO, 1, "Kundennummer"),
                d("date", "2026-09-21", 1, "Datum", LETTER_DATE),
                d("info_date (Portal-Start)", "2026-11-01", 1, "Ab dem", EFFECTIVE_FROM),
                Fact("phone", PHONE, "01234 400 55", 1, "unter"),
            ),
        ),
        DocCase(
            "N9-fuzzy-name-1p",
            listOf(
                page(
                    "@ADDR Herrn",
                    "@ADDR Mohamad Mustermann",
                    "Kundennummer:||60012987",
                    "Rechnungsnr.:||AB-2026-10-4471",
                    "Datum:||27.09.2026",
                    "Bitte überweisen Sie 78,00 € bis zum 12.10.2026 unter Angabe der Rechnungsnummer AB-2026-10-4471 auf das folgende Konto: " +
                        "DE89 3704 0044 0532 0130 00 (Energie Beispielstadt GmbH).",
                ),
            ),
            "2026-09-27",
            listOf(
                r("customer_number", "60012987", CUSTOMER_NO, 1, "Kundennummer"),
                r("reference_number", "AB-2026-10-4471", INVOICE_NO, 1, "Rechnungsnr"),
                r("reference_number (body)", "AB-2026-10-4471", INVOICE_NO, 1, "Rechnungsnummer"),
                d("date", "2026-09-27", 1, "Datum", LETTER_DATE),
                a("amount", "78.00 EUR", 1),
                d("deadline", "2026-10-12", 1, "bis zum", DEADLINE),
                i("DE89370400440532013000"),
            ),
        ),
        DocCase(
            "N10-kinderarzt-termin-1p",
            listOf(
                page(
                    "@ADDR Erziehungsberechtigte von",
                    "@ADDR Adam Mustermann",
                    "Datum:||25.09.2026",
                    "Terminerinnerung für Adam Mustermann",
                    "Donnerstag, 12.11.2026 um 09:30 Uhr",
                    "Bitte bringen Sie die Versichertenkarte mit. Wenn Sie den Termin nicht wahrnehmen können, sagen Sie ihn bitte mindestens " +
                        "24 Stunden vorher unter 01234 700 20 ab.",
                ),
            ),
            "2026-09-25",
            listOf(
                d("date", "2026-09-25", 1, "Datum", LETTER_DATE),
                Fact("appointment", CandidateKind.DATETIME, "2026-11-12T09:30", 1, null, setOf(APPOINTMENT)),
                Fact("phone", PHONE, "01234 700 20", 1, "unter"),
            ),
        ),
    )

    private fun matches(c: Candidate, f: Fact): Boolean {
        val kindOk = c.kind == f.kind || (f.kind == DATE && c.kind == CandidateKind.DATETIME)
        if (!kindOk || c.normalized.filter { !it.isWhitespace() }.let { n ->
                if (f.kind == DATE) !n.startsWith(f.norm) else n != f.norm.filter { !it.isWhitespace() }
            }
        ) return false
        if (f.subtype != null && c.subtype != f.subtype) return false
        if (f.labelKinds != null && c.labelKind !in f.labelKinds) return false
        if (f.label != null && !c.label.contains(f.label, ignoreCase = true)) return false
        return true
    }

    @TestFactory
    fun `every expected date amount iban and reference is a candidate`(): List<DynamicTest> =
        cases.flatMap { doc ->
            val set = run(*doc.pages.toTypedArray())
            doc.facts.map { f ->
                dynamicTest("${doc.id}: ${f.what} = ${f.norm}") {
                    val onPage = set.candidates.filter { it.page == f.page }
                    val hit = onPage.firstOrNull { matches(it, f) }
                    assertThat(hit).isNotNull()
                    // the verdict must not contradict the manifest
                    assertThat(hit!!.validation.isInvalid).isFalse()
                }
            }
        }

    @Test
    fun `no letter date is inferred, but it is offered among the date candidates for every document`() {
        for (doc in cases) {
            val set = run(*doc.pages.toTypedArray())
            assertThat(set.letterDate).isNull()
            if (doc.letter != null) {
                assertThat(
                    set.candidates.any {
                        (it.kind == DATE || it.kind == CandidateKind.DATETIME) && it.page == 1 && it.normalized.startsWith(doc.letter)
                    },
                ).isTrue()
            }
        }
    }

    @Test
    fun `manifest coverage summary`() {
        var found = 0
        var total = 0
        val missing = ArrayList<String>()
        for (doc in cases) {
            val set = run(*doc.pages.toTypedArray())
            for (f in doc.facts) {
                total++
                if (set.candidates.any { it.page == f.page && matches(it, f) }) found++ else missing.add("${doc.id}: ${f.what}")
            }
        }
        println("MANIFEST COVERAGE: $found / $total expected facts found as candidates; missing=$missing")
        assertThat(missing).isEmpty()
    }

    @Test
    fun `invoice net plus VAT equals gross is marked as one consistent triple`() {
        val doc = cases.first { it.id == "invoice-2p" }
        val set = run(*doc.pages.toTypedArray())
        val triple = set.ofKind(AMOUNT).filter { it.attrs["triple"] != null }
        assertThat(triple.map { it.normalized }).containsAtLeast("1079.41 EUR", "205.09 EUR", "1284.50 EUR")
        assertThat(triple.all { it.validation.isValid }).isTrue()
    }

    @Test
    fun `candidate ids are stable across runs and unique`() {
        val doc = cases.first { it.id == "invoice-2p" }
        val first = run(*doc.pages.toTypedArray()).candidates.map { it.id }
        val second = run(*doc.pages.toTypedArray()).candidates.map { it.id }
        assertThat(first).isEqualTo(second)
        assertThat(first.toSet()).hasSize(first.size)
        assertThat(first).contains("A1")
        assertThat(first).contains("I1")
    }

    @Test
    fun `passing the letter date explicitly range checks the dates`() {
        val set = run(page("Datum: 28.09.2026", "fällig bis 15.10.2066"), letterDate = LocalDate.of(2026, 9, 28))
        val bad = set.candidates.first { it.normalized == "2066-10-15" }
        assertThat(bad.validation).isInstanceOf(Validation.Invalid::class.java)
        assertThat((bad.validation as Validation.Invalid).reason).contains("2026")
    }
}
