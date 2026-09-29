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
    fun `a period in words is not a candidate in any language, the model quotes it`(): List<DynamicTest> = table(
        listOf(
            "innerhalb von 14 Tagen nach Zugang dieses Schreibens auf das Konto",
            "Einspruch innerhalb eines Monats nach Bekanntgabe des Bescheids beim Amt",
            "within 14 days of the date of this letter",
            "dans un délai de 14 jours à compter de la réception",
            "خلال 30 يوما من تاريخ الاستلام",
        ),
    ) { line ->
        assertThat(one(line)).isEmpty()
    }

    @Test
    fun `no letter date is inferred from a label, so dates stay unchecked until the model chooses one`() {
        val set = run(page("Datum: 28.09.2026", "Fällig am 12.10.2026", "31.02.2026"))
        assertThat(set.letterDate).isNull()
        val dates = set.candidates.filter { it.kind == CandidateKind.DATE }
        assertThat(dates.map { it.normalized }).containsExactly("2026-09-28", "2026-10-12", "2026-02-31").inOrder()
        assertThat(dates[0].validation).isEqualTo(Validation.Unchecked)
        assertThat(dates[1].validation).isEqualTo(Validation.Unchecked)
        // A calendar impossibility is still caught, whatever the words.
        assertThat(dates[2].validation).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test
    fun `a given letter date range-checks every date the same way, whatever the label`() {
        val set = run(page("geb. 12.05.1980", "Datum: 28.09.2026", "Fällig 12.10.2026"), letterDate = java.time.LocalDate.of(2026, 9, 28))
        val byDate = set.candidates.filter { it.kind == CandidateKind.DATE }.associate { it.normalized to it.validation }
        assertThat(byDate["2026-09-28"]).isEqualTo(Validation.Valid)
        assertThat(byDate["2026-10-12"]).isEqualTo(Validation.Valid)
        assertThat(byDate["1980-05-12"]).isInstanceOf(Validation.Invalid::class.java)
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

    // ── OCR character confusion ─────────────────────────────────────────────────

    @Test
    fun `an IBAN with o read for 0 or I for 1 is repaired only when its checksum then holds`() {
        for (line in listOf("IBAN: DEo2 10o1 0010 0006 8201 01", "DE02 1OO1 0010 0006 8201 01", "Konto DE02 1001 0010 0006 8201 0I")) {
            val c = one(line).single { it.kind == CandidateKind.IBAN }
            assertThat(c.normalized).isEqualTo("DE02100100100006820101")
            assertThat(c.validation).isEqualTo(Validation.Valid)
            assertThat(c.attrs["repaired"]).isNotNull()
            assertThat(c.raw).isNotEqualTo(c.normalized)
        }
        // A misreading whose repaired form does not validate stays what it is: invalid, not repaired.
        val bad = one("IBAN: DEo2 10o1 0010 0006 8201 02").single { it.kind == CandidateKind.IBAN }
        assertThat(bad.validation).isInstanceOf(Validation.Invalid::class.java)
        assertThat(bad.attrs["repaired"]).isNull()
    }

    @Test
    fun `an IBAN that is right as printed is never touched, letters in a Dutch one included`() {
        val nl = one("NL91 ABNA 0417 1643 00").single { it.kind == CandidateKind.IBAN }
        assertThat(nl.normalized).isEqualTo("NL91ABNA0417164300")
        assertThat(nl.attrs["repaired"]).isNull()
        assertThat(nl.validation).isEqualTo(Validation.Valid)
    }

    @Test
    fun `a meter number with O for 0 keeps the raw text as evidence and normalises the repaired one`() {
        val c = one("Zählernummer 1EMHO000123456").single { it.kind == CandidateKind.REFERENCE }
        assertThat(c.raw).isEqualTo("1EMHO000123456")
        assertThat(c.normalized).isEqualTo("1EMH0000123456")
        assertThat(c.attrs["repaired"]).isNotNull()
        assertThat(c.evidence).contains("1EMHO000123456")
        // The same in a language with no label rule: the shape alone offers it, repaired.
        val shape = one("رقم 1EMHO000123456").single { it.kind == CandidateKind.REFERENCE }
        assertThat(shape.normalized).isEqualTo("1EMH0000123456")
    }

    @Test
    fun `identifiers with few digits or ordinary letters are not repaired`() {
        assertThat(one("Kundennummer: SO12345").single { it.kind == CandidateKind.REFERENCE }.normalized).isEqualTo("SO12345")
        assertThat(one("Ref AB-2026-10-4471").single { it.kind == CandidateKind.REFERENCE }.attrs["repaired"]).isNull()
        assertThat(IdentifierRepair.repair("Rechnung2026")).isNull()
    }

    @Test
    fun `a labelled BIC is found and the label is a hint`() {
        val set = one("BIC: COBADEFFXXX", "BEISPIELMARKT", "SWIFT DEUTDEFF")
        assertThat(set.filter { it.kind == CandidateKind.BIC }.map { it.normalized })
            .containsExactly("COBADEFFXXX", "DEUTDEFF")
    }

    @Test
    fun `a BIC is found by its shape when its country is the country of an IBAN on the page, with no label`() {
        val set = one("IBAN: DE89 3704 0044 0532 0130 00", "COBADEFFXXX", "Empfänger Muster GmbH  DEUTDEFF")
        val bics = set.filter { it.kind == CandidateKind.BIC }
        assertThat(bics.map { it.normalized }).containsExactly("COBADEFFXXX", "DEUTDEFF").inOrder()
        assertThat(bics.all { it.attrs["shape"] == "true" }).isTrue()
        // Any language around it, or none.
        val ar = one("IBAN DE89 3704 0044 0532 0130 00", "رمز البنك COBADEFFXXX")
        assertThat(ar.single { it.kind == CandidateKind.BIC }.normalized).isEqualTo("COBADEFFXXX")
    }

    @Test
    fun `a BIC-shaped word without an IBAN of the same country on the page is not a BIC`() {
        // No IBAN at all.
        assertThat(one("COBADEFFXXX", "BEISPIELMARKT").filter { it.kind == CandidateKind.BIC }).isEmpty()
        // An IBAN of another country.
        assertThat(one("IBAN: NL91 ABNA 0417 1643 00", "COBADEFFXXX").filter { it.kind == CandidateKind.BIC }).isEmpty()
        // The IBAN is on another page.
        val split = run(page("IBAN: DE89 3704 0044 0532 0130 00"), page("COBADEFFXXX"))
        assertThat(split.ofKind(CandidateKind.BIC)).isEmpty()
        // A word of the right length whose 5th and 6th letters are no IBAN country.
        assertThat(one("IBAN: DE89 3704 0044 0532 0130 00", "BEISPIEL").filter { it.kind == CandidateKind.BIC }).isEmpty()
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
    fun `a routing prefix is cut off by its shape and kept as the hint, kind is not decided`() {
        val set = run(page("@ADDR Mustermann Consulting GmbH", "@ADDR z. Hd. Frau Erika Mustermann", "@ADDR Gewerbering 4", "@ADDR 54321 Beispieldorf"))
        val names = set.ofKind(CandidateKind.NAME)
        assertThat(names.map { it.normalized }).containsExactly("Mustermann Consulting GmbH", "Erika Mustermann").inOrder()
        assertThat(names[1].label).contains("Hd")
        assertThat(names[1].attrs["prefix"]).isEqualTo("z. Hd.")
        // Whether this person is the routing contact is not recorded here; that is the model's `r`.
        assertThat(names[1].attrs["routing"]).isNull()
    }

    @Test
    fun `c o prefix is cut off by shape and the real addressee stays a separate candidate`() {
        val set = run(page("@ADDR Herrn", "@ADDR Jonas Mustermann", "@ADDR c/o Familie Beispiel", "@ADDR Beispielgasse 3", "@ADDR 12345 Beispielstadt"))
        val names = set.ofKind(CandidateKind.NAME)
        assertThat(names.map { it.normalized }).containsExactly("Jonas Mustermann", "Familie Beispiel").inOrder()
        assertThat(names[0].attrs["prefix"]).isNull()
        assertThat(names[1].label).isEqualTo("c/o")
    }

    @Test
    fun `routing lines are found without zone hints, from their shape`() {
        val set = run(page("Mustermann Consulting GmbH", "z. Hd. Herrn Dr. Max Beispiel", "c/o Firma Test GmbH"))
        assertThat(set.ofKind(CandidateKind.NAME).map { it.normalized })
            .containsExactly("Mustermann Consulting GmbH", "Dr. Max Beispiel", "Firma Test GmbH").inOrder()
    }

    @Test
    fun `initials and titles are not mistaken for a routing prefix`() {
        val set = run(page("@ADDR Dr. Erika Mustermann", "@ADDR J. K. Beispiel", "@ADDR T. Muster"))
        assertThat(set.ofKind(CandidateKind.NAME).map { it.normalized })
            .containsExactly("Dr. Erika Mustermann", "J. K. Beispiel", "T. Muster").inOrder()
    }

    @Test
    fun `a one-word line under a one-word line is one name, whatever the first word says`() {
        val set = run(page("@ADDR Familie", "@ADDR Mustermann", "@ADDR Musterstraße 12", "@ADDR 54321 Beispieldorf"))
        assertThat(set.ofKind(CandidateKind.NAME).map { it.normalized }).containsExactly("Familie Mustermann")
        // The same shape in another language and another script.
        val fr = run(page("@ADDR Famille", "@ADDR Exemple", "@ADDR 3 rue de l'Exemple", "@ADDR 75001 Paris"))
        assertThat(fr.ofKind(CandidateKind.NAME).map { it.normalized }).containsExactly("Famille Exemple")
        val ar = run(page("@ADDR عائلة", "@ADDR موستيرمان"))
        assertThat(ar.ofKind(CandidateKind.NAME)).hasSize(1)
        val herrn = run(page("@ADDR Herrn und Frau", "@ADDR Max und Erika Mustermann"))
        assertThat(herrn.ofKind(CandidateKind.NAME).map { it.normalized }).containsExactly("Max und Erika Mustermann")
    }

    @Test
    fun `a guardian phrase is not interpreted, the whole line is a name for the model to read`() {
        val inline = run(page("@ADDR Erziehungsberechtigte von Adam Mustermann"))
        assertThat(inline.ofKind(CandidateKind.NAME).single().normalized).isEqualTo("Erziehungsberechtigte von Adam Mustermann")
        assertThat(inline.candidates.none { it.attrs["guardianOf"] != null }).isTrue()
        val split = run(page("@ADDR Erziehungsberechtigte von", "@ADDR Adam Mustermann"))
        assertThat(split.ofKind(CandidateKind.NAME).map { it.normalized }).containsExactly("Adam Mustermann")
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
        assertThat(set.ofKind(CandidateKind.NAME).map { it.normalized }).containsExactly("Nordlicht Mobilfunk GmbH", "Nordlicht Mobilfunk GmbH")
        assertThat(set.candidates.none { it.kind.name.endsWith("_NAME") }).isTrue()
    }

    @Test
    fun `letterhead names come from the shape, in any language, with no organisation word list`() {
        val de = run(page("@HEAD Beitragsservice Beispiel", "@HEAD Postfach 10 00 00", "@HEAD 50656 Beispielstadt"))
        assertThat(de.ofKind(CandidateKind.NAME).single().normalized).isEqualTo("Beitragsservice Beispiel")
        val en = run(page("@HEAD Northwind Utilities Ltd.", "@HEAD 1 Example Road", "@HEAD Exampleton EX2 3PL", "@HEAD Phone: 0800 555 0100"))
        assertThat(en.ofKind(CandidateKind.NAME).map { it.normalized }).containsExactly("Northwind Utilities Ltd.")
        val ar = run(page("@HEAD شركة المثال للخدمات", "@HEAD هاتف: 0800 555 0100"))
        assertThat(ar.ofKind(CandidateKind.NAME)).hasSize(1)
        // One neutral id prefix for every name; the model's `k` says person, company or authority.
        assertThat(de.ofKind(CandidateKind.NAME).single().id).startsWith("M")
    }

    @Test
    fun `a name looking like a company and one looking like a person get the same kind and prefix`() {
        val set = run(page("@ADDR Mustermann Consulting GmbH", "@ADDR Erika Mustermann"))
        assertThat(set.ofKind(CandidateKind.NAME).map { it.id }).containsExactly("M1", "M2").inOrder()
    }

    @Test
    fun `names come from their shape, sentences and labels do not`() {
        val set = one(
            "Sehr geehrte Frau Mustermann,", "Erika Mustermann", "Mustermann Consulting GmbH",
            "Bitte überweisen Sie den Betrag bis morgen.", "Datum: heute", "Zahlungsziel 14 Tage",
        )
        val names = set.filter { it.kind == CandidateKind.NAME }.map { it.normalized }
        assertThat(names).containsExactly("Erika Mustermann", "Mustermann Consulting GmbH").inOrder()
        assertThat(set.filter { it.normalized == "Erika Mustermann" }.single().attrs["shape"]).isEqualTo("true")
    }

    @Test
    fun `a name is found in a script without capitals`() {
        val set = one("السيدة إيريكا موستيرمان", "شركة المثال للخدمات المحدودة")
        assertThat(set.count { it.kind == CandidateKind.NAME }).isEqualTo(2)
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
    fun `a phone number is found by its shape with no label, in any language`() {
        val set = one("Call +49 30 1234567 or", "هاتف 0800 555 0100", "Tél. 01234-888-44", "Appelez le 0800 555 0199")
        assertThat(set.filter { it.kind == CandidateKind.PHONE }.map { it.normalized })
            .containsExactly("+49 30 1234567", "0800 555 0100", "01234-888-44", "0800 555 0199").inOrder()
    }

    @Test
    fun `dates, amounts, postcodes and plain digit runs are not phone numbers`() {
        val set = one("25.09.2026", "64,98 €", "12345 Beispielstadt", "004217", "0044021", "Posten 10 00 00", "am 01.10.2026 um 09:30")
        assertThat(set.filter { it.kind == CandidateKind.PHONE }).isEmpty()
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
        // The label is only a hint; no letter date is inferred from it.
        assertThat(set.letterDate).isNull()
        assertThat(set.ofKind(CandidateKind.AMOUNT).single().labelKind).isEqualTo(LabelKind.GROSS)
    }

    @Test
    fun `with no letter date every real date stays unchecked, a birth date included`() {
        val set = one("Datum: 28.09.2026", "geb. 12.05.1980")
        val birth = set.single { it.normalized == "1980-05-12" }
        assertThat(birth.validation).isEqualTo(Validation.Unchecked)
        assertThat(set.single { it.normalized == "2026-09-28" }.validation).isEqualTo(Validation.Unchecked)
    }

    // ── amount triples by arithmetic ─────────────────────────────────────────────

    private fun triples(vararg lines: String) = one(*lines).filter { it.kind == CandidateKind.AMOUNT && it.attrs["triple"] != null }.map { it.normalized }

    @Test
    fun `a net VAT gross triple is found by arithmetic, with no label at all`() {
        assertThat(triples("100,00 €", "19,00 €", "119,00 €")).containsExactly("100.00 EUR", "19.00 EUR", "119.00 EUR")
    }

    @Test
    fun `the same triple is found in French, English and Arabic wording`() {
        assertThat(triples("Montant HT 412,00 €", "TVA 20 % 82,40 €", "Total TTC 494,40 €")).hasSize(3)
        assertThat(triples("Subtotal 250.00 EUR", "Tax 7% 17.50 EUR", "Total 267.50 EUR")).hasSize(3)
        assertThat(triples("المبلغ 100,00 يورو", "ضريبة 19,00 يورو", "المجموع 119,00 يورو")).hasSize(3)
    }

    @Test
    fun `wrong labels do not matter, only the sum and a plausible tax rate do`() {
        // Labels swapped on purpose: still a consistent triple.
        assertThat(triples("Brutto 100,00 €", "Netto 19,00 €", "MwSt 119,00 €")).hasSize(3)
        // Labelled like a triple but the sum is off.
        assertThat(triples("Netto 100,00 €", "MwSt 19,00 €", "Brutto 121,00 €")).isEmpty()
    }

    @Test
    fun `a sum with an implausible tax rate is not a triple`() {
        // 100 + 60 = 160 adds up, but 60 percent is no tax rate.
        assertThat(triples("100,00 €", "60,00 €", "160,00 €")).isEmpty()
        // 30 percent is above the ceiling.
        assertThat(triples("100,00 €", "30,00 €", "130,00 €")).isEmpty()
    }

    @Test
    fun `a triple within one cent of rounding is accepted, across pages it is not`() {
        assertThat(triples("33,33 €", "6,33 €", "39,67 €")).hasSize(3)
        val split = run(page("100,00 €", "19,00 €"), page("119,00 €")).candidates.filter { it.attrs["triple"] != null }
        assertThat(split).isEmpty()
    }
}
