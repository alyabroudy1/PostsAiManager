package com.postsaimanager.core.domain.extraction.candidates

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate

class ValidatorsTest {

    // ── IBAN ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `valid IBANs pass`(): List<DynamicTest> = table(
        listOf(
            "DE89 3704 0044 0532 0130 00",
            "DE02 1203 0000 0000 2020 51",
            "DE02 1001 0010 0006 8201 01",
            "GB82 WEST 1234 5698 7654 32",
            "NL91 ABNA 0417 1643 00",
            "FR14 2004 1010 0505 0001 3M02 606",
            "CH93 0076 2011 6238 5295 7",
            "AT61 1904 3002 3457 3201",
            "ES91 2100 0418 4502 0005 1332",
        ),
    ) { iban -> assertThat(IbanValidator.validate(iban)).isEqualTo(Validation.Valid) }

    @Test
    fun `a corrupted digit fails the checksum`() {
        val v = IbanValidator.validate("DE89 3704 0044 0532 0130 01")
        assertThat(v).isInstanceOf(Validation.Invalid::class.java)
        assertThat((v as Validation.Invalid).reason).contains("checksum")
    }

    @Test
    fun `a transposed pair fails the checksum`() {
        assertThat(IbanValidator.validate("DE89 3704 0044 0532 0310 00")).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test
    fun `wrong length for the country fails before the checksum`() {
        val v = IbanValidator.validate("DE89 3704 0044 0532 0130")
        assertThat((v as Validation.Invalid).reason).contains("22")
    }

    @Test
    fun `unknown country fails`() {
        assertThat(IbanValidator.validate("XX89 3704 0044 0532 0130 00")).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test
    fun `legacy checksum-only check keeps its 15 to 34 character window`() {
        assertThat(IbanValidator.hasValidChecksum("DE89370400440532013000")).isTrue()
        assertThat(IbanValidator.hasValidChecksum("DE8937")).isFalse()
    }

    // ── dates ─────────────────────────────────────────────────────────────────

    private val letter = LocalDate.of(2026, 9, 28)

    @Test
    fun `31 February is not a calendar date`() {
        val v = DateValidator.validate(2026, 2, 31, letter)
        assertThat((v as Validation.Invalid).reason).contains("calendar")
    }

    @Test
    fun `a digit-swapped year 2066 is rejected with the 2026 hint`() {
        val v = DateValidator.validate(2066, 10, 15, letter)
        assertThat(v).isInstanceOf(Validation.Invalid::class.java)
        assertThat((v as Validation.Invalid).reason).contains("2026")
    }

    @Test
    fun `transposed digits 2062 also get the hint`() {
        val v = DateValidator.validate(2062, 10, 15, letter)
        assertThat((v as Validation.Invalid).reason).contains("2026")
    }

    @TestFactory
    fun `dates from a year before to three years after the letter are valid`(): List<DynamicTest> =
        table(listOf(Triple(2026, 10, 15), Triple(2027, 9, 28), Triple(2025, 10, 1), Triple(2029, 9, 28))) { (y, m, d) ->
            assertThat(DateValidator.validate(y, m, d, letter)).isEqualTo(Validation.Valid)
        }

    @TestFactory
    fun `dates outside the window are invalid`(): List<DynamicTest> =
        table(listOf(Triple(2025, 9, 1), Triple(2029, 10, 1), Triple(2010, 1, 1))) { (y, m, d) ->
            assertThat(DateValidator.validate(y, m, d, letter)).isInstanceOf(Validation.Invalid::class.java)
        }

    @Test
    fun `without a letter date a real date is only unchecked`() {
        assertThat(DateValidator.validate(2066, 10, 15, null)).isEqualTo(Validation.Unchecked)
    }

    @Test
    fun `relaxed past window accepts a billing period from last year`() {
        assertThat(DateValidator.validate(2025, 1, 1, letter, pastYears = 10)).isEqualTo(Validation.Valid)
    }

    // ── amounts ───────────────────────────────────────────────────────────────

    @TestFactory
    fun `amounts parse to cents with an explicit currency`(): List<DynamicTest> = table(
        listOf(
            listOf("1.284,50", "€", "128450", "EUR"),
            listOf("1284,50", "EUR", "128450", "EUR"),
            listOf("142.80", "£", "14280", "GBP"),
            listOf("1,284.50", "USD", "128450", "USD"),
            listOf("5,00", "€", "500", "EUR"),
            listOf("35", "€", "3500", "EUR"),
            listOf("1.284", "€", "128400", "EUR"),
            listOf("2.317,00", "Euro", "231700", "EUR"),
        ),
    ) { (num, cur, cents, code) ->
        val m = AmountParser.parse(num, cur)!!
        assertThat(m.cents).isEqualTo(cents.toLong())
        assertThat(m.currency).isEqualTo(code)
        assertThat(m.currencyExplicit).isTrue()
    }

    @Test
    fun `negative amount and assumed currency`() {
        val m = AmountParser.parse("5,00", null, negative = true)!!
        assertThat(m.cents).isEqualTo(-500)
        assertThat(m.currencyExplicit).isFalse()
        assertThat(m.canonical()).isEqualTo("-5.00 EUR")
    }

    @Test
    fun `malformed groupings are rejected`() {
        assertThat(AmountParser.parse("1.28,50", "€")).isNull()
        assertThat(AmountParser.parse("12,3", "€")).isNull()
    }

    @Test
    fun `net plus VAT equals gross within one cent`() {
        assertThat(AmountConsistency.netPlusVatEqualsGross(107941, 20509, 128450)).isTrue()
        assertThat(AmountConsistency.netPlusVatEqualsGross(107941, 20509, 128451)).isTrue()
        assertThat(AmountConsistency.netPlusVatEqualsGross(107941, 20509, 128500)).isFalse()
    }

    // ── references ────────────────────────────────────────────────────────────

    @Test
    fun `Steuer-ID checksum`() {
        assertThat(ReferenceValidator.validate(ReferenceSubtype.TAX_ID, "65 929 970 489")).isEqualTo(Validation.Valid)
        assertThat(ReferenceValidator.validate(ReferenceSubtype.TAX_ID, "65929970480")).isInstanceOf(Validation.Invalid::class.java)
        assertThat(ReferenceValidator.validate(ReferenceSubtype.TAX_ID, "0123456789")).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test
    fun `KVNR letter plus nine digits with checksum`() {
        // A=01: digits 0,1,1,2,3,4,5,6,7,8 weighted 1,2,...: computed in the test to document the rule
        val body = "A12345678"
        val seq = "01" + body.substring(1)
        var sum = 0
        seq.forEachIndexed { idx, c ->
            val p = (c - '0') * (if (idx % 2 == 0) 1 else 2)
            sum += p / 10 + p % 10
        }
        val good = body + (sum % 10)
        val bad = body + ((sum + 1) % 10)
        assertThat(ReferenceValidator.validate(ReferenceSubtype.INSURANCE_NO, good)).isEqualTo(Validation.Valid)
        assertThat(ReferenceValidator.validate(ReferenceSubtype.INSURANCE_NO, bad)).isInstanceOf(Validation.Invalid::class.java)
        assertThat(ReferenceValidator.validate(ReferenceSubtype.INSURANCE_NO, "ABC-123")).isEqualTo(Validation.Unchecked)
    }

    @Test
    fun `Steuernummer and Beitragsnummer shapes`() {
        assertThat(ReferenceValidator.validate(ReferenceSubtype.TAX_NO, "123/456/78901")).isEqualTo(Validation.Valid)
        assertThat(ReferenceValidator.validate(ReferenceSubtype.TAX_NO, "12/34")).isInstanceOf(Validation.Invalid::class.java)
        assertThat(ReferenceValidator.validate(ReferenceSubtype.BEITRAGSNUMMER, "987 654 321")).isEqualTo(Validation.Valid)
        assertThat(ReferenceValidator.validate(ReferenceSubtype.BEITRAGSNUMMER, "987 654")).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test
    fun `subtypes without a known shape stay unchecked`() {
        assertThat(ReferenceValidator.validate(ReferenceSubtype.INVOICE_NO, "RE-2026-0815")).isEqualTo(Validation.Unchecked)
    }

    // ── noise ─────────────────────────────────────────────────────────────────

    @Test
    fun `signature blobs serials and barcode runs are noise`() {
        assertThat(NoiseFilter.isNoiseLine("kJ3f9Zl0Qw2Rt8Yh5Ub1Nm4Vc7Xz6AaS+/")).isTrue()
        assertThat(NoiseFilter.isNoiseLine("3f9a0c71b2d84e55a6f7019c2b3d4e5f")).isTrue()
        assertThat(NoiseFilter.isNoiseLine("40063813339312026092718")).isTrue()
        assertThat(NoiseFilter.isNoiseLine("Terminal-ID: 52847196")).isTrue()
        assertThat(NoiseFilter.isNoiseLine("TSE-Start: 2026-09-27T18:41:12")).isTrue()
    }

    @Test
    fun `ordinary lines and IBANs are not noise`() {
        assertThat(NoiseFilter.isNoiseLine("Bon-Nr.: 4711   Kasse: 03   Bediener: 017")).isFalse()
        assertThat(NoiseFilter.isNoiseLine("IBAN: DE02120300000000202051")).isFalse()
        assertThat(NoiseFilter.isNoiseLine("Steueridentifikationsnummer")).isFalse()
        assertThat(NoiseFilter.isNoiseLine("Betriebskostenvorauszahlung 245,00 €")).isFalse()
    }
}
