package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.gemma.DeviceLetters
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

    // ── a short letter's faithful summary is no copy (the early summary was lost to this) ──

    private fun verdictOf(answer: String, text: String) = gate.check(answer, text, emptyList())

    @Test
    fun `a faithful summary of the Jobcenter letter is accepted though it shares most of its words with the letter`() {
        val answer = "Das Jobcenter Musterstadt bestätigt, dass der Antrag auf Bürgergeld am 01.09.2026 eingegangen ist."

        assertThat(verdictOf(answer, DeviceLetters.jobcenterText)).isInstanceOf(SummaryGate.Verdict.Accepted::class.java)
        assertThat(gate.copiedShare(answer, DeviceLetters.jobcenterText)).isLessThan(SummaryGate.COPY_SHARE)
    }

    @Test
    fun `a faithful summary of the short Zahnarzt message and of the Markt receipt is accepted`() {
        assertThat(verdictOf("Die Zahnarztpraxis erinnert an Ihren Termin am 14.10.2026 um 10:30 Uhr und bittet, die Versichertenkarte mitzubringen.", DeviceLetters.zahnarztText))
            .isInstanceOf(SummaryGate.Verdict.Accepted::class.java)
        assertThat(verdictOf("Kartenzahlung bei Markt Beispiel über 14,18 EUR, der Einkauf ist bezahlt.", DeviceLetters.marktText))
            .isInstanceOf(SummaryGate.Verdict.Accepted::class.java)
    }

    @Test
    fun `copying is long verbatim runs - the letter's own sentences repeated are rejected for each of the three letters`() {
        val zahnarzt = "Terminerinnerung: Ihr Termin ist am 14.10.2026 um 10:30 Uhr. Bitte Versichertenkarte mitbringen."
        val jobcenter = "wir bestätigen, dass Ihr Antrag auf Bürgergeld am 01.09.2026 bei uns eingegangen ist."
        val markt = "Brot 2,50 EUR Milch 1,20 EUR Käse 10,48 EUR Summe 14,18 EUR"

        assertThat(reason(zahnarzt, DeviceLetters.zahnarztText, emptyList())).isEqualTo(SummaryGate.Reason.COPIED)
        assertThat(reason(jobcenter, DeviceLetters.jobcenterText, emptyList())).isEqualTo(SummaryGate.Reason.COPIED)
        assertThat(reason(markt, DeviceLetters.marktText, emptyList())).isEqualTo(SummaryGate.Reason.COPIED)
    }

    @Test
    fun `phrases of the letter inside a summary of its own words are not a copy`() {
        val answer = "Die Praxis schreibt: Bitte Versichertenkarte mitbringen. Absage bis 24 h vorher ist nötig, sonst kann der Termin am 14.10.2026 " +
            "um 10:30 Uhr verfallen und es entstehen leider unnötige Kosten."
        assertThat(answer.length).isAtMost(SummaryLimits.MAX_CHARS)

        assertThat(gate.copiedShare(answer, DeviceLetters.zahnarztText)).isLessThan(SummaryGate.COPY_SHARE)
        assertThat(verdictOf(answer, DeviceLetters.zahnarztText)).isInstanceOf(SummaryGate.Verdict.Accepted::class.java)
    }

    @Test
    fun `an invented number is still rejected for the receipt`() {
        assertThat(reason("Kartenzahlung bei Markt Beispiel über 99,00 EUR.", DeviceLetters.marktText, emptyList())).isEqualTo(SummaryGate.Reason.UNVERIFIED_NUMBER)
    }

    @Test
    fun `an empty answer is rejected`() {
        assertThat(reason("  ")).isEqualTo(SummaryGate.Reason.EMPTY)
    }

    @Test
    fun `a summary a little over the limit that ends at a sentence boundary is trimmed to its last whole sentence and accepted`() {
        val first = "Das Jobcenter Musterstadt bestätigt, dass der Antrag auf Bürgergeld am 01.09.2026 eingegangen ist."
        val second = "Es wird gebeten, bis zum 01.09.2026 keine weiteren Unterlagen nachzureichen, solange kein Schreiben des Jobcenters dazu auffordert."
        val answer = "$first $second"
        assertThat(answer.length).isGreaterThan(SummaryLimits.MAX_CHARS)

        val verdict = verdictOf(answer, DeviceLetters.jobcenterText)

        assertThat(verdict).isEqualTo(SummaryGate.Verdict.Accepted(first))
    }

    @Test
    fun `a summary of four sentences well over the limit, as the model writes it when asked for one, is cut to the first`() {
        val first = "Das Jobcenter Musterstadt bestätigt, dass der Antrag auf Bürgergeld am 01.09.2026 eingegangen ist."
        val more = " Es wird gebeten, bis zum 01.09.2026 keine weiteren Unterlagen nachzureichen, solange kein Schreiben des Jobcenters dazu auffordert."
        val answer = first + more.repeat(4)
        assertThat(answer.length).isGreaterThan(SummaryLimits.MAX_CHARS * 3)

        assertThat(verdictOf(answer, DeviceLetters.jobcenterText)).isEqualTo(SummaryGate.Verdict.Accepted(first))
    }

    @Test
    fun `a long first sentence is cut at the last clause boundary with an ellipsis, never rejected for its length`() {
        val answer = "Das Jobcenter Musterstadt bestätigt, dass der Antrag auf Bürgergeld am 01.09.2026 eingegangen ist, und bittet darum, " +
            "bei allen Rückfragen die BG-Nummer 12345BG0007777 anzugeben, weil sonst keine Zuordnung möglich ist, und meldet sich später mit der Entscheidung über den Antrag."
        assertThat(answer.length).isGreaterThan(SummaryLimits.MAX_CHARS)

        val verdict = verdictOf(answer, DeviceLetters.jobcenterText) as SummaryGate.Verdict.Accepted

        assertThat(verdict.text.length).isAtMost(SummaryLimits.MAX_CHARS)
        assertThat(verdict.text).endsWith("…")
        assertThat(verdict.text).doesNotContain(",…")
    }

    @Test
    fun `an 1100-character German summary is accepted trimmed, not rejected as too long`() {
        val sentence = "Das Jobcenter Musterstadt bestätigt, dass der Antrag auf Bürgergeld am 01.09.2026 eingegangen ist und geprüft wird. "
        val answer = (sentence.repeat(10)).trim().take(1100)
        assertThat(answer.length).isAtLeast(1000)

        val verdict = verdictOf(answer, DeviceLetters.jobcenterText)

        assertThat(verdict).isInstanceOf(SummaryGate.Verdict.Accepted::class.java)
        assertThat((verdict as SummaryGate.Verdict.Accepted).text.length).isAtMost(SummaryLimits.MAX_CHARS)
    }

    @Test
    fun `the prompt's ask, the schema and the gate share one length limit`() {
        assertThat(SummaryLimits.MAX_CHARS).isIn(160..200)
        val atLimit = "Das Jobcenter bestätigt den Eingang des Antrags auf Bürgergeld am 01.09.2026. ".repeat(3).trim().take(SummaryLimits.MAX_CHARS)
        assertThat(atLimit.length).isAtMost(SummaryLimits.MAX_CHARS)
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
