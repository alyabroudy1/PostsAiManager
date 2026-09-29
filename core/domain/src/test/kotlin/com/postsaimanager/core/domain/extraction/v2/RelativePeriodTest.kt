package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * A period in words is quoted by the model and verified against the letter; code reads a number and
 * a unit only when the quote has digits. No phrase gates whether the rule is accepted.
 */
class RelativePeriodTest {

    @TestFactory
    fun `the number is the first run of digits, the unit a hint, in any language`(): List<DynamicTest> = listOf(
        "innerhalb von 14 Tagen nach Zugang" to "P14D",
        "within 14 days of the date of this letter" to "P14D",
        "binnen 2 Wochen" to "P2W",
        "within 3 months" to "P3M",
        "dans un délai de 8 jours" to "P8D",
        "خلال 30 يوما" to "P30D",
        "خلال ٣٠ يوما" to "P30D",
        "within 2 years" to "P2Y",
    ).map { (quote, iso) ->
        DynamicTest.dynamicTest(quote) { assertThat(RelativePeriod.parse(quote)?.iso).isEqualTo(iso) }
    }

    @Test
    fun `no digits or no known unit means no claim`() {
        assertThat(RelativePeriod.parse("innerhalb eines Monats")).isNull()
        assertThat(RelativePeriod.parse("within one month")).isNull()
        assertThat(RelativePeriod.parse("bis 14 Uhr")).isNull()
        assertThat(RelativePeriod.parse("14 Gurken")).isNull()
    }

    @Test
    fun `a period beyond two years is not plausible`() {
        assertThat(RelativePeriod.parse("within 900 days")!!.plausible).isFalse()
        assertThat(RelativePeriod.parse("within 30 days")!!.plausible).isTrue()
    }

    private fun verifyQuote(text: String, quote: String): SlotValue? {
        val page = listOf(OcrBlock(text, TextBounds(0.1f, 0.5f, 0.9f, 0.52f), 0.9f))
        val prepared = Prepared(listOf(page))
        // No candidate stands for a period, in any language.
        assertThat(prepared.candidates.candidates.map { it.kind }).doesNotContain(CandidateKind.NAME)
        val json = """{"type":"bill","tc":"HIGH","lang":"xx","parties":[],"s":{"due_date":{"rule":"$quote","r":"DUE_DATE","c":"HIGH"}},"x":[]}"""
        val raw = (InterpretationParser.parse(json) as InterpretationParser.Parsed.Ok).value
        val ctx = VerificationContext(prepared.candidates, prepared.offered, listOf(text), 0, 0, 1, 1)
        return SelectionVerifier().verify(raw, null, ctx).slots[Slots.DUE_DATE]
    }

    @Test
    fun `an English, a French and an Arabic quote are verified the same way as a German one`() {
        val en = verifyQuote("Please pay within 14 days of receipt of this letter.", "within 14 days")!!
        assertThat(en.origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
        assertThat(en.normalized).isEqualTo("P14D")
        val fr = verifyQuote("Veuillez payer dans un délai de 8 jours.", "dans un délai de 8 jours")!!
        assertThat(fr.normalized).isEqualTo("P8D")
        val ar = verifyQuote("يرجى الدفع خلال 30 يوما من تاريخ الاستلام.", "خلال 30 يوما")!!
        assertThat(ar.normalized).isEqualTo("P30D")
    }

    @Test
    fun `a rule that is not in the letter is dropped, whatever language it is in`() {
        // Dropped, or at worst kept as a fuzzy quote that is flagged for review; never accepted cleanly.
        for ((text, quote) in listOf(
            "Please pay within 14 days of receipt." to "within 30 days",
            "Bitte zahlen Sie sofort." to "innerhalb von 14 Tagen",
        )) {
            val v = verifyQuote(text, quote)
            assertThat(v == null || (v.blocked && v.quoteMatch == QuoteMatch.FUZZY)).isTrue()
        }
    }

    @Test
    fun `a quote with no unit hint is kept as the quote, not refused`() {
        val v = verifyQuote("Bitte bis 14 Uhr melden.", "bis 14 Uhr")!!
        assertThat(v.normalized).isEqualTo("bis 14 Uhr")
        assertThat(v.origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
    }
}
