package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SummaryGateTest {

    private val gate = SummaryGate()

    private val ocr = """
        Musterfirma GmbH
        Hauptstraße 1, 10115 Berlin
        Erika Mustermann
        Rechnung 2026-08-771204
        Der Betrag von 1.284,50 € ist bis zum 19.08.2026 zu überweisen.
        Bitte zahlen Sie rechtzeitig.
    """.trimIndent()

    private val facts = listOf("Musterfirma GmbH", "Erika Mustermann", "1.284,50 €", "19.08.2026")

    private fun reason(answer: String, text: String = ocr, values: List<String> = facts) =
        (gate.check(answer, text, values) as? SummaryGate.Verdict.Rejected)?.reason

    @Test
    fun `an answer built from the facts is accepted`() {
        val v = gate.check("Erika Mustermann soll Musterfirma GmbH 1.284,50 € zahlen, fällig am 19.08.2026.", ocr, facts)
        assertThat(v).isInstanceOf(SummaryGate.Verdict.Accepted::class.java)
    }

    @Test
    fun `an amount that is not in the letter is rejected`() {
        assertThat(reason("Erika Mustermann soll Musterfirma GmbH 1.284,51 € zahlen, fällig am 19.08.2026.")).isEqualTo(SummaryGate.Reason.UNVERIFIED_NUMBER)
    }

    @Test
    fun `a date that is not in the letter is rejected`() {
        assertThat(reason("Erika Mustermann soll bis 20.08.2026 zahlen.")).isEqualTo(SummaryGate.Reason.UNVERIFIED_NUMBER)
    }

    @Test
    fun `a bare digit run that is not in the letter is rejected`() {
        assertThat(reason("Erika Mustermann soll innerhalb von 14 Tagen zahlen.")).isEqualTo(SummaryGate.Reason.UNVERIFIED_NUMBER)
    }

    @Test
    fun `a date written with words passes when its numbers are in the letter`() {
        assertThat(reason("Erika Mustermann soll bis zum 19 August 2026 zahlen.")).isNull()
    }

    @Test
    fun `a fact that is not in the letter passes when the facts carry it`() {
        assertThat(reason("Erika Mustermann soll 99,00 € zahlen.", values = facts + "99,00 €")).isNull()
    }

    @Test
    fun `a name that is not in the letter is rejected`() {
        assertThat(reason("Die Rechnung von Hans Müller ist offen.")).isEqualTo(SummaryGate.Reason.UNVERIFIED_NAME)
    }

    @Test
    fun `a capitalised pair at the start of a sentence is not a name by itself`() {
        assertThat(reason("Die Rechnung ist offen. Bitte überweisen.")).isNull()
    }

    @Test
    fun `a german summary with capitalised nouns made of the letter's own words is accepted`() {
        // "Sie Ihre Rechnung" is capitalised German, not a name; every word is in the letter.
        val text = "Rechnung vom Musterfirma GmbH\nBitte begleichen\nSie die Summe\nIhre Zahlung\nIhre Rechnung\nfällig 19.08.2026"
        assertThat(reason("Bitte, dass Sie Ihre Rechnung bis 19.08.2026 begleichen.", text = text)).isNull()
    }

    @Test
    fun `a name that is only in the facts is accepted`() {
        assertThat(reason("Die Zahlung geht an Anna Beispiel.", values = facts + "Anna Beispiel")).isNull()
    }

    @Test
    fun `an answer that copies one line of the letter is rejected`() {
        assertThat(reason("Der Betrag von 1.284,50 € ist bis zum 19.08.2026 zu überweisen.")).isEqualTo(SummaryGate.Reason.COPIED)
    }

    @Test
    fun `an answer that mostly reuses one line with small edits is rejected`() {
        assertThat(reason("Der Betrag von 1.284,50 € ist bis 19.08.2026 zu überweisen, bitte.")).isEqualTo(SummaryGate.Reason.COPIED)
    }

    @Test
    fun `a sentence copied from the letter is rejected whether or not the scan wrapped it over several lines`() {
        val sentence = "Wir können Ihnen die Positionen in Rechnung stellen, die in der nachstehenden Tabelle enthalten sind."
        val whole = "Musterfirma GmbH\n$sentence\nRechnung RE-2026-0815"
        val wrapped = "Musterfirma GmbH\nWir können Ihnen die Positionen in Rechnung\nstellen, die in der nachstehenden Tabelle\nenthalten sind.\nRechnung RE-2026-0815"
        assertThat(reason(sentence, whole, emptyList())).isEqualTo(SummaryGate.Reason.COPIED)
        assertThat(reason(sentence, wrapped, emptyList())).isEqualTo(SummaryGate.Reason.COPIED)
    }

    @Test
    fun `an empty or runaway answer is rejected`() {
        assertThat(reason("  ")).isEqualTo(SummaryGate.Reason.EMPTY)
        assertThat(reason("wort ".repeat(60))).isEqualTo(SummaryGate.Reason.TOO_LONG)
    }

    @Test
    fun `Arabic-Indic digits in the letter match Western digits in the answer and back`() {
        val arabic = "فاتورة\nالمبلغ ١٢٨٤٫٥٠ يورو\nتاريخ الاستحقاق ١٩/٠٨/٢٠٢٦\nيرجى الدفع في الوقت المحدد"
        assertThat(reason("يجب دفع 1284,50 يورو قبل 19/08/2026", arabic, emptyList())).isNull()
        assertThat(reason("يجب دفع ١٢٨٤٫٥٠ يورو قبل ١٩/٠٨/٢٠٢٦", arabic, emptyList())).isNull()
        assertThat(reason("يجب دفع ٩٩٩ يورو", arabic, emptyList())).isEqualTo(SummaryGate.Reason.UNVERIFIED_NUMBER)
        assertThat(reason("يجب دفع 1284,50 يورو قبل 20/08/2026", arabic, emptyList())).isEqualTo(SummaryGate.Reason.UNVERIFIED_NUMBER)
    }
}
