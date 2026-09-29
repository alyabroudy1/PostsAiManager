package com.postsaimanager.core.domain.extraction.candidates

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class CandidateExtractorTest {

    private fun one(vararg lines: String) = run(page(*lines)).candidates

    // ── date formats ────────────────────────────────────────────────────────────

    @TestFactory
    fun `date formats`(): List<DynamicTest> = table(
        listOf(
            "Datum: 28.09.2026" to "2026-09-28",
            "Datum: 01.10.26" to "2026-10-01",
            "Datum: 1. Oktober 2026" to "2026-10-01",
            "am 1 Oktober 2026 um" to "2026-10-01",
            "Date: 10 October 2026" to "2026-10-10",
            "Date: October 10, 2026" to "2026-10-10",
            "Date: 3rd March 2027" to "2027-03-03",
            "Ausgestellt 2026-09-28" to "2026-09-28",
            "Datum: 5.3.2026" to "2026-03-05",
            "Datum: 05. 03. 2026" to "2026-03-05",
        ),
        { it.first },
    ) { (line, iso) ->
        val dates = one(line).filter { it.kind == CandidateKind.DATE }
        assertThat(dates.map { it.normalized }).contains(iso)
    }

    @Test
    fun `datetime forms`() {
        val a = one("Termin: 12.11.2026 um 09:30").single { it.kind == CandidateKind.DATETIME }
        assertThat(a.normalized).isEqualTo("2026-11-12T09:30")
        val b = one("12.11.2026 09:30 Uhr").single { it.kind == CandidateKind.DATETIME }
        assertThat(b.normalized).isEqualTo("2026-11-12T09:30")
        val c = one("Beginn 9.30 Uhr im Raum 4").single { it.kind == CandidateKind.DATETIME }
        assertThat(c.normalized).isEqualTo("T09:30")
        assertThat(c.attrs["timeOnly"]).isEqualTo("true")
    }

    @Test
    fun `a bare decimal-dot number after a date is not a time`() {
        val kinds = one("Datum 12.11.2026 15.10 Stück").map { it.kind }
        assertThat(kinds).doesNotContain(CandidateKind.DATETIME)
    }

    @Test
    fun `impossible date is kept as invalid rather than dropped`() {
        val c = one("fällig am 31.02.2026").single { it.kind == CandidateKind.DATE }
        assertThat(c.validation).isInstanceOf(Validation.Invalid::class.java)
    }

    // ── relative deadlines ──────────────────────────────────────────────────────

    @TestFactory
    fun `relative deadlines keep number unit and anchor`(): List<DynamicTest> = table(
        listOf(
            listOf("innerhalb von 14 Tagen nach Zugang dieses Schreibens auf das Konto", "P14D", "Zugang dieses Schreibens"),
            listOf("Einspruch innerhalb eines Monats nach Bekanntgabe des Bescheids beim Amt", "P1M", "Bekanntgabe des Bescheids"),
            listOf("Bitte antworten Sie binnen 2 Wochen nach Erhalt.", "P2W", "Erhalt"),
            listOf("within 14 days of the date of this letter", "P14D", "the date of this letter"),
            listOf("innerhalb einer Woche ab Rechnungsdatum bitte zahlen", "P1W", "Rechnungsdatum"),
        ),
        { it[0] },
    ) { (line, iso, anchor) ->
        val c = one(line).single { it.kind == CandidateKind.RELATIVE_DEADLINE }
        assertThat(c.normalized).isEqualTo(iso)
        assertThat(c.attrs["anchor"]).isEqualTo(anchor)
        assertThat(c.raw).contains(anchor)
    }

    // ── amounts ─────────────────────────────────────────────────────────────────

    @TestFactory
    fun `amount formats`(): List<DynamicTest> = table(
        listOf(
            "Betrag: 1.284,50 €" to "1284.50 EUR",
            "Betrag EUR 1.284,50" to "1284.50 EUR",
            "Summe 1284,50" to "1284.50 EUR",
            "Balance £142.80 overdue" to "142.80 GBP",
            "Total \$1,284.50" to "1284.50 USD",
            "Guthaben -5,00 €" to "-5.00 EUR",
            "Kosten 35,- €" to "35.00 EUR",
            "Gebühr 12,50 Euro" to "12.50 EUR",
            "Rate 1.234 €" to "1234.00 EUR",
        ),
        { it.first },
    ) { (line, normalized) ->
        assertThat(one(line).filter { it.kind == CandidateKind.AMOUNT }.map { it.normalized }).contains(normalized)
    }

    @Test
    fun `percentages units and per-unit prices are not amounts`() {
        val amounts = one("MwSt. 19,00 % auf 3,15 kWh und 1,79 EUR/kg").filter { it.kind == CandidateKind.AMOUNT }
        assertThat(amounts).isEmpty()
    }

    @Test
    fun `dates are not amounts`() {
        val amounts = one("Datum 12.10.2026 und 15.10.26").filter { it.kind == CandidateKind.AMOUNT }
        assertThat(amounts).isEmpty()
    }

    @Test
    fun `amount label kinds`() {
        val c = one(
            "Nettobetrag 100,00 €",
            "MwSt. 19,00 € hm",
            "Brutto 119,00 €",
            "Nachzahlung 20,00 €",
            "Guthaben 21,00 €",
            "Abschlag 22,00 €",
            "Vorauszahlung 23,00 €",
            "Beitrag 24,00 €",
            "zu zahlen 25,00 €",
        ).filter { it.kind == CandidateKind.AMOUNT }.associate { it.normalized to it.labelKind }
        assertThat(c["100.00 EUR"]).isEqualTo(LabelKind.NET)
        assertThat(c["19.00 EUR"]).isEqualTo(LabelKind.VAT)
        assertThat(c["119.00 EUR"]).isEqualTo(LabelKind.GROSS)
        assertThat(c["20.00 EUR"]).isEqualTo(LabelKind.TOTAL_DUE)
        assertThat(c["21.00 EUR"]).isEqualTo(LabelKind.CREDIT)
        assertThat(c["22.00 EUR"]).isEqualTo(LabelKind.ADVANCE)
        assertThat(c["23.00 EUR"]).isEqualTo(LabelKind.ADVANCE)
        assertThat(c["24.00 EUR"]).isEqualTo(LabelKind.PREMIUM)
        assertThat(c["25.00 EUR"]).isEqualTo(LabelKind.TOTAL_DUE)
    }

    @Test
    fun `inconsistent triple is not marked`() {
        val amounts = one("Netto 100,00 €", "MwSt 19,00 €", "Brutto 120,00 €").filter { it.kind == CandidateKind.AMOUNT }
        assertThat(amounts.none { it.attrs["triple"] != null }).isTrue()
    }

    // ── IBAN / BIC ──────────────────────────────────────────────────────────────

    @Test
    fun `IBAN with spaces compact and split across a line break`() {
        val spaced = one("IBAN: DE89 3704 0044 0532 0130 00").single { it.kind == CandidateKind.IBAN }
        assertThat(spaced.normalized).isEqualTo("DE89370400440532013000")
        assertThat(spaced.validation).isEqualTo(Validation.Valid)
        val compact = one("IBAN DE89370400440532013000.").single { it.kind == CandidateKind.IBAN }
        assertThat(compact.normalized).isEqualTo("DE89370400440532013000")
        val split = one("IBAN: DE89 3704 0044 0532", "0130 00").single { it.kind == CandidateKind.IBAN }
        assertThat(split.normalized).isEqualTo("DE89370400440532013000")
        assertThat(split.validation).isEqualTo(Validation.Valid)
        assertThat(split.evidence).contains("0130 00")
    }

    @Test
    fun `IBAN split across two blocks`() {
        val set = run(page("Konto: DE89 3704 0044 0532", "0130 00 (Musterfirma)"))
        assertThat(set.ofKind(CandidateKind.IBAN).single().normalized).isEqualTo("DE89370400440532013000")
    }

    @Test
    fun `corrupted IBAN is a candidate marked invalid`() {
        val c = one("IBAN: DE89 3704 0044 0532 0130 01").single { it.kind == CandidateKind.IBAN }
        assertThat(c.validation).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test
    fun `creditor id and other DE00 lookalikes are not IBANs`() {
        val set = one("Gläubiger-ID DE00ZZZ00000000000", "Ref DE12 3456")
        assertThat(set.filter { it.kind == CandidateKind.IBAN }).isEmpty()
    }

    @Test
    fun `BIC needs its label`() {
        val set = one("BIC: COBADEFFXXX", "BEISPIELMARKT", "SWIFT DEUTDEFF")
        assertThat(set.filter { it.kind == CandidateKind.BIC }.map { it.normalized })
            .containsExactly("COBADEFFXXX", "DEUTDEFF")
    }

    // ── references ──────────────────────────────────────────────────────────────

    @Test
    fun `reference label variants`() {
        val refs = one(
            "Rechnungs-Nr. RE-77",
            "Kundennr.: 998877",
            "Vertragsnummer VN-1234-56",
            "Versicherungsnummer POL-88-123",
            "Aktenzeichen: AZ-2026/118 Sehr geehrte Damen",
            "Steuer-ID: 65 929 970 489",
            "Steuernummer 21/815/08150",
            "Versichertennummer: A123456780",
        ).filter { it.kind == CandidateKind.REFERENCE }.associate { it.subtype to it.normalized }
        assertThat(refs[ReferenceSubtype.INVOICE_NO]).isEqualTo("RE-77")
        assertThat(refs[ReferenceSubtype.CUSTOMER_NO]).isEqualTo("998877")
        assertThat(refs[ReferenceSubtype.CONTRACT_NO]).isEqualTo("VN-1234-56")
        assertThat(refs[ReferenceSubtype.POLICY_NO]).isEqualTo("POL-88-123")
        assertThat(refs[ReferenceSubtype.CASE_NO]).isEqualTo("AZ-2026/118")
        assertThat(refs[ReferenceSubtype.TAX_ID]).isEqualTo("65 929 970 489")
        assertThat(refs[ReferenceSubtype.TAX_NO]).isEqualTo("21/815/08150")
        assertThat(refs[ReferenceSubtype.INSURANCE_NO]).isEqualTo("A123456780")
    }

    @Test
    fun `Steuer-ID candidate carries its checksum verdict`() {
        val good = one("Steuer-ID: 65 929 970 489").single { it.subtype == ReferenceSubtype.TAX_ID }
        assertThat(good.validation).isEqualTo(Validation.Valid)
        val bad = one("Steuer-ID: 65 929 970 480").single { it.subtype == ReferenceSubtype.TAX_ID }
        assertThat(bad.validation).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test
    fun `a label followed by a date is not a reference`() {
        assertThat(one("Rechnung vom 05.08.2026 liegt bei").filter { it.kind == CandidateKind.REFERENCE }).isEmpty()
    }

    // ── names ───────────────────────────────────────────────────────────────────

    @Test
    fun `z Hd line gives the routing person with the salutation stripped`() {
        val set = run(page("@ADDR Mustermann Consulting GmbH", "@ADDR z. Hd. Frau Erika Mustermann", "@ADDR Gewerbering 4", "@ADDR 54321 Beispieldorf"))
        val org = set.ofKind(CandidateKind.ORG_NAME).single()
        assertThat(org.normalized).isEqualTo("Mustermann Consulting GmbH")
        val person = set.ofKind(CandidateKind.PERSON_NAME).single()
        assertThat(person.normalized).isEqualTo("Erika Mustermann")
        assertThat(person.label).contains("Hd")
        assertThat(person.attrs["routing"]).isEqualTo("true")
    }

    @Test
    fun `c o line is a routing name and the real addressee stays a separate candidate`() {
        val set = run(page("@ADDR Herrn", "@ADDR Jonas Mustermann", "@ADDR c/o Familie Beispiel", "@ADDR Beispielgasse 3", "@ADDR 12345 Beispielstadt"))
        val names = set.ofKind(CandidateKind.PERSON_NAME)
        assertThat(names.map { it.normalized }).containsExactly("Jonas Mustermann", "Familie Beispiel").inOrder()
        assertThat(names[0].attrs["routing"]).isNull()
        assertThat(names[1].label).isEqualTo("c/o")
        assertThat(names[1].attrs["routing"]).isEqualTo("true")
    }

    @Test
    fun `routing lines are found even without zone hints`() {
        val set = CandidateExtractor.extractFromText("Mustermann Consulting GmbH\nz. Hd. Herrn Dr. Max Beispiel\nc/o Firma Test GmbH")
        assertThat(set.ofKind(CandidateKind.PERSON_NAME).map { it.normalized }).containsExactly("Dr. Max Beispiel")
        assertThat(set.ofKind(CandidateKind.ORG_NAME).map { it.normalized }).containsExactly("Firma Test GmbH")
    }

    @Test
    fun `salutation only lines are not names and family combines with the surname line`() {
        val set = run(page("@ADDR Familie", "@ADDR Mustermann", "@ADDR Musterstraße 12", "@ADDR 54321 Beispieldorf"))
        assertThat(set.ofKind(CandidateKind.PERSON_NAME).map { it.normalized }).containsExactly("Familie Mustermann")
        val herrn = run(page("@ADDR Herrn und Frau", "@ADDR Max und Erika Mustermann"))
        assertThat(herrn.ofKind(CandidateKind.PERSON_NAME).map { it.normalized }).containsExactly("Max und Erika Mustermann")
    }

    @Test
    fun `guardian addressee`() {
        val inline = run(page("@ADDR Erziehungsberechtigte von Adam Mustermann"))
        assertThat(inline.ofKind(CandidateKind.PERSON_NAME).single().normalized).isEqualTo("Adam Mustermann")
        val split = run(page("@ADDR Erziehungsberechtigte von", "@ADDR Adam Mustermann"))
        val p = split.ofKind(CandidateKind.PERSON_NAME).single()
        assertThat(p.normalized).isEqualTo("Adam Mustermann")
        assertThat(p.label).contains("Erziehungsberechtigte")
    }

    @Test
    fun `letterhead and return address give the sender organisation`() {
        val set = run(
            page(
                "@HEAD Nordlicht Mobilfunk GmbH",
                "@HEAD Beispielweg 7",
                "@HEAD 12345 Beispielstadt",
                "@HEAD Tel. 0800 555 0100",
                "@RET Nordlicht Mobilfunk GmbH · Beispielweg 7 · 12345 Beispielstadt",
            ),
        )
        assertThat(set.ofKind(CandidateKind.ORG_NAME).map { it.normalized }).containsExactly("Nordlicht Mobilfunk GmbH", "Nordlicht Mobilfunk GmbH")
        assertThat(set.ofKind(CandidateKind.PERSON_NAME)).isEmpty()
    }

    @Test
    fun `letterhead first line without a legal form is a guessed organisation`() {
        val set = run(page("@HEAD Beitragsservice Beispiel", "@HEAD Postfach 10 00 00", "@HEAD 50656 Beispielstadt"))
        val org = set.ofKind(CandidateKind.ORG_NAME).single()
        assertThat(org.normalized).isEqualTo("Beitragsservice Beispiel")
    }

    @Test
    fun `names come from their shape, sentences and labels do not`() {
        val set = one(
            "Sehr geehrte Frau Mustermann,", "Erika Mustermann", "Mustermann Consulting GmbH",
            "Bitte überweisen Sie den Betrag bis morgen.", "Datum: heute", "Zahlungsziel 14 Tage",
        )
        val names = set.filter { it.kind == CandidateKind.PERSON_NAME || it.kind == CandidateKind.ORG_NAME }.map { it.normalized }
        assertThat(names).containsExactly("Erika Mustermann", "Mustermann Consulting GmbH").inOrder()
        assertThat(set.filter { it.normalized == "Erika Mustermann" }.single().attrs["shape"]).isEqualTo("true")
    }

    @Test
    fun `a name is found in a script without capitals`() {
        val set = one("السيدة إيريكا موستيرمان", "شركة المثال للخدمات المحدودة")
        assertThat(set.count { it.kind == CandidateKind.PERSON_NAME || it.kind == CandidateKind.ORG_NAME }).isEqualTo(2)
    }

    @Test
    fun `an identifier is offered whatever the words next to it say`() {
        val set = one("Vorgang 9981-2277 läuft", "reference AB-2026-10-4471", "المرجع X7Q-4432-19")
        val ids = set.filter { it.kind == CandidateKind.REFERENCE }.map { it.normalized }
        assertThat(ids).containsAtLeast("9981-2277", "AB-2026-10-4471", "X7Q-4432-19")
        assertThat(set.first { it.normalized == "9981-2277" }.attrs["shape"]).isEqualTo("true")
        assertThat(set.first { it.normalized == "9981-2277" }.label).isEqualTo("Vorgang")
    }

    @Test
    fun `postcodes, years, times and decimals are not identifiers`() {
        val ids = one("12345 Beispielstadt", "im Jahr 2026", "um 18:42", "Fläche 68,5 m²", "Nr. 42").filter { it.kind == CandidateKind.REFERENCE }
        assertThat(ids).isEmpty()
    }

    // ── not_facts of receipt-noise-1p ───────────────────────────────────────────

    @Test
    fun `receipt noise never becomes a candidate`() {
        val signature = "kJ3f9Zl0Qw2Rt8Yh5Ub1Nm4Vc7Xz6AaSdFgHjKl0P" // base64-ish TSE signature line
        val serial = "3f9a0c71b2d84e55a6f7019c2b3d4e5f"
        val noise = listOf(
            "TSE-Signatur:",
            signature,
            signature.reversed(),
            "Seriennummer:",
            serial,
            "Transaktionsnummer: 1049233",
            "Terminal-ID: 52847196",
            "Trace-Nr.: 004217",
            "Genehmigungs-Nr.: 918274",
            "TSE-Start: 2026-09-27T18:41:12",
            "TSE-Stop: 2026-09-27T18:42:07",
            "Signaturzähler: 88214",
            "Sig.-Alg.: ecdsa-plain-SHA256",
            "PAN: ************4821",
            "Beleg-Nr.: 7731",
            "40063813339312026092718", // QR / barcode digit run
        )
        assertThat(one(*noise.toTypedArray())).isEmpty()
    }

    @Test
    fun `receipt facts survive next to the noise`() {
        val set = run(
            page(
                "Datum: 27.09.2026  Zeit: 18:42:07",
                "Bon-Nr.: 4711   Kasse: 03   Bediener: 017",
                "TSE-Signatur:",
                "kJ3f9Zl0Qw2Rt8Yh5Ub1Nm4Vc7Xz6AaSdFgHjKl0P",
                "Transaktionsnummer: 1049233",
                "SUMME EUR 23,47",
            ),
        )
        assertThat(set.ofKind(CandidateKind.REFERENCE).map { it.normalized }).containsExactly("4711")
        assertThat(set.ofKind(CandidateKind.AMOUNT).map { it.normalized }).containsExactly("23.47 EUR")
        assertThat(set.ofKind(CandidateKind.IBAN)).isEmpty()
    }

    // ── misc ────────────────────────────────────────────────────────────────────

    @Test
    fun `phone and email`() {
        val set = one("Tel. 0800 555 0100", "service@nordlicht-mobil.example.", "Fax: +49 30 1234567")
        assertThat(set.filter { it.kind == CandidateKind.PHONE }.map { it.normalized }).containsExactly("0800 555 0100", "+49 30 1234567")
        assertThat(set.single { it.kind == CandidateKind.EMAIL }.normalized).isEqualTo("service@nordlicht-mobil.example")
    }

    @Test
    fun `candidates carry page bbox and evidence`() {
        val set = run(page("Betrag 1,00 €"), page("nichts", "IBAN: DE89 3704 0044 0532 0130 00"))
        val iban = set.ofKind(CandidateKind.IBAN).single()
        assertThat(iban.page).isEqualTo(2)
        assertThat(iban.bbox).isNotNull()
        assertThat(iban.evidence).isEqualTo("IBAN: DE89 3704 0044 0532 0130 00")
        assertThat(iban.label).isEqualTo("IBAN")
        assertThat(set.ofKind(CandidateKind.AMOUNT).single().page).isEqualTo(1)
    }

    @Test
    fun `plain text entry point works without bounds`() {
        val set = CandidateExtractor.extractFromText("Datum: 28.09.2026\nGesamtbetrag 1.284,50 €\nIBAN DE89 3704 0044 0532 0130 00")
        assertThat(set.candidates.all { it.bbox == null }).isTrue()
        assertThat(set.letterDate.toString()).isEqualTo("2026-09-28")
        assertThat(set.ofKind(CandidateKind.AMOUNT).single().labelKind).isEqualTo(LabelKind.GROSS)
    }

    @Test
    fun `birth dates are not range checked`() {
        val set = one("Datum: 28.09.2026", "geb. 12.05.1980")
        val birth = set.single { it.normalized == "1980-05-12" }
        assertThat(birth.labelKind).isEqualTo(LabelKind.BIRTH_DATE)
        assertThat(birth.validation).isEqualTo(Validation.Unchecked)
    }
}
