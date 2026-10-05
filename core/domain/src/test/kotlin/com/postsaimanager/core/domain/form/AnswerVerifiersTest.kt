package com.postsaimanager.core.domain.form

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormValueKind
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale

class AnswerVerifiersTest {

    private val today = LocalDate.of(2026, 10, 1)
    private val de = Locale.GERMANY
    private val enUs = Locale.US
    private val enGb = Locale.UK

    private fun accepted(v: Verification) = (v as Verification.Accepted).value
    private fun rejected(v: Verification) = (v as Verification.Rejected).reason

    // ── Choice ──

    @Test
    fun `a choice matches an option ignoring case and accents and returns it as printed`() {
        val options = listOf("Montag 16:00 Uhr", "Mittwoch 15:00 Uhr", "Samstag 10:00 Uhr")
        assertThat(accepted(AnswerVerifiers.verifyChoice("mittwoch 15:00 uhr", options))).isEqualTo("Mittwoch 15:00 Uhr")
        assertThat(accepted(AnswerVerifiers.verifyChoice("Ja", listOf("Ja", "Nein")))).isEqualTo("Ja")
        assertThat(accepted(AnswerVerifiers.verifyChoice("Café", listOf("Cafe", "Tee")))).isEqualTo("Cafe")
    }

    @Test
    fun `a choice matches Arabic spelling variants and an answer that contains the option`() {
        val options = listOf("شهرية", "سنوية")
        assertThat(accepted(AnswerVerifiers.verifyChoice("سنويه", options))).isEqualTo("سنوية")
        assertThat(accepted(AnswerVerifiers.verifyChoice("ja bitte", listOf("Ja", "Nein")))).isEqualTo("Ja")
    }

    @Test
    fun `a choice that is no option, is ambiguous or is empty is rejected`() {
        val options = listOf("Montag 16:00 Uhr", "Mittwoch 15:00 Uhr")
        assertThat(rejected(AnswerVerifiers.verifyChoice("Freitag", options))).isEqualTo(Rejection.NOT_AN_OPTION)
        assertThat(rejected(AnswerVerifiers.verifyChoice("Uhr", options))).isEqualTo(Rejection.AMBIGUOUS_OPTION)
        assertThat(rejected(AnswerVerifiers.verifyChoice("  ", options))).isEqualTo(Rejection.EMPTY)
    }

    // ── Date ──

    @Test
    fun `numeric dates are read in the order of the form's locale`() {
        assertThat(accepted(AnswerVerifiers.verifyDate("12.03.2019", de, today))).isEqualTo("2019-03-12")
        assertThat(accepted(AnswerVerifiers.verifyDate("12/03/2019", enGb, today))).isEqualTo("2019-03-12")
        assertThat(accepted(AnswerVerifiers.verifyDate("3/12/2019", enUs, today))).isEqualTo("2019-03-12")
        assertThat(accepted(AnswerVerifiers.verifyDate("2019-03-12", de, today))).isEqualTo("2019-03-12")
    }

    @Test
    fun `a number above twelve decides which one is the day`() {
        assertThat(accepted(AnswerVerifiers.verifyDate("25/12/2019", enUs, today))).isEqualTo("2019-12-25")
        assertThat(accepted(AnswerVerifiers.verifyDate("12/25/2019", enGb, today))).isEqualTo("2019-12-25")
    }

    @Test
    fun `short years and Arabic-Indic digits and written months are understood`() {
        assertThat(accepted(AnswerVerifiers.verifyDate("12.3.19", de, today))).isEqualTo("2019-03-12")
        assertThat(accepted(AnswerVerifiers.verifyDate("12.03.85", de, today))).isEqualTo("1985-03-12")
        assertThat(accepted(AnswerVerifiers.verifyDate("١٢.٠٣.٢٠١٩", de, today))).isEqualTo("2019-03-12")
        assertThat(accepted(AnswerVerifiers.verifyDate("12. März 2019", de, today))).isEqualTo("2019-03-12")
        assertThat(accepted(AnswerVerifiers.verifyDate("March 12, 2019", enUs, today))).isEqualTo("2019-03-12")
    }

    @Test
    fun `impossible dates and words are rejected`() {
        assertThat(rejected(AnswerVerifiers.verifyDate("31.04.2019", de, today))).isEqualTo(Rejection.NOT_A_DATE)
        assertThat(rejected(AnswerVerifiers.verifyDate("gestern", de, today))).isEqualTo(Rejection.NOT_A_DATE)
        assertThat(rejected(AnswerVerifiers.verifyDate("", de, today))).isEqualTo(Rejection.EMPTY)
    }

    // ── Phone, e-mail, IBAN, postcode ──

    @Test
    fun `phone numbers have a phone shape`() {
        assertThat(accepted(AnswerVerifiers.verifyPhone("0151  2345678"))).isEqualTo("0151 2345678")
        assertThat(accepted(AnswerVerifiers.verifyPhone("+49 (151) 234-5678"))).isEqualTo("+49 (151) 234-5678")
        assertThat(accepted(AnswerVerifiers.verifyPhone("٠١٥١٢٣٤٥٦٧٨"))).isEqualTo("01512345678")
        assertThat(rejected(AnswerVerifiers.verifyPhone("abc"))).isEqualTo(Rejection.NOT_A_PHONE)
        assertThat(rejected(AnswerVerifiers.verifyPhone("12"))).isEqualTo(Rejection.NOT_A_PHONE)
        assertThat(rejected(AnswerVerifiers.verifyPhone("0151+2345678"))).isEqualTo(Rejection.NOT_A_PHONE)
    }

    @Test
    fun `e-mail addresses have an e-mail shape and a lower-case domain`() {
        assertThat(accepted(AnswerVerifiers.verifyEmail(" Mo.Muster@Example.ORG "))).isEqualTo("Mo.Muster@example.org")
        assertThat(rejected(AnswerVerifiers.verifyEmail("mo.muster"))).isEqualTo(Rejection.NOT_AN_EMAIL)
        assertThat(rejected(AnswerVerifiers.verifyEmail("a@b"))).isEqualTo(Rejection.NOT_AN_EMAIL)
    }

    @Test
    fun `an IBAN needs its country length and a valid checksum`() {
        assertThat(accepted(AnswerVerifiers.verifyIban("de89 3704 0044 0532 0130 00"))).isEqualTo("DE89370400440532013000")
        assertThat(rejected(AnswerVerifiers.verifyIban("DE89 3704 0044 0532 0130 01"))).isEqualTo(Rejection.BAD_IBAN_CHECKSUM)
        assertThat(rejected(AnswerVerifiers.verifyIban("DE89 3704 0044 0532 0130"))).isEqualTo(Rejection.NOT_AN_IBAN)
        assertThat(rejected(AnswerVerifiers.verifyIban("not an iban"))).isEqualTo(Rejection.NOT_AN_IBAN)
        assertThat(rejected(AnswerVerifiers.verifyIban(""))).isEqualTo(Rejection.EMPTY)
    }

    @Test
    fun `a postcode has the shape of the country's postcodes`() {
        assertThat(accepted(AnswerVerifiers.verifyPostcode("54321", "DE"))).isEqualTo("54321")
        assertThat(rejected(AnswerVerifiers.verifyPostcode("5432", "DE"))).isEqualTo(Rejection.NOT_A_POSTCODE)
        assertThat(accepted(AnswerVerifiers.verifyPostcode("X-1", "ZZ"))).isEqualTo("X-1")
    }

    @Test
    fun `verify picks the check by the kind of value and by the options`() {
        assertThat(accepted(AnswerVerifiers.verify(FormValueKind.DATE, "12.03.2019", de, today = today))).isEqualTo("2019-03-12")
        assertThat(accepted(AnswerVerifiers.verify(FormValueKind.TEXT, " Nuss ", de))).isEqualTo("Nuss")
        assertThat(rejected(AnswerVerifiers.verify(FormValueKind.TEXT, " ", de))).isEqualTo(Rejection.EMPTY)
        assertThat(accepted(AnswerVerifiers.verify(FormValueKind.BOOLEAN, "nein", de, options = listOf("Ja", "Nein")))).isEqualTo("Nein")
        assertThat(rejected(AnswerVerifiers.verify(FormValueKind.EMAIL, "x", de))).isEqualTo(Rejection.NOT_AN_EMAIL)
    }

    // ── Formatting ──

    @Test
    fun `dates are written in the form's short format with a four-digit year`() {
        val date = LocalDate.of(2019, 3, 12)
        assertThat(FormValueFormatter.date(date, de)).isEqualTo("12.03.2019")
        assertThat(FormValueFormatter.date(date, enGb)).isEqualTo("12/03/2019")
        assertThat(FormValueFormatter.date(date, enUs)).isEqualTo("3/12/2019")
        assertThat(FormValueFormatter.date(date, Locale.forLanguageTag("ar"))).containsMatch("^[0-9/.\\- ]+$")
        assertThat(FormValueFormatter.date("2019-03-12", de)).isEqualTo("12.03.2019")
        assertThat(FormValueFormatter.date("kein Datum", de)).isEqualTo("kein Datum")
    }

    @Test
    fun `an IBAN is written in groups of four`() {
        assertThat(FormValueFormatter.iban("DE89370400440532013000")).isEqualTo("DE89 3704 0044 0532 0130 00")
    }
}
