package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.benchmark.BenchmarkFixtures
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.CandidateTable
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractorCandidateSource
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The two-page invoice fixture: clean rows as the model's input, and none as a real answer for the numbers an invoice does not have. */
class CleanInputOptionalSlotsTest {

    private val fixture = BenchmarkFixtures.load().docs.first { it.first.key == "invoice-2p" }.second
    private val pages = fixture.pages.map { it.blocks }
    private val layout = LetterLayoutAnalyzer.analyze(pages)
    private val offered = CandidateTable.build(ExtractorCandidateSource().find(pages, layout))
    private val aspect = fixture.pages.first().let { it.width.toFloat() / it.height }
    private val zoned = ZonedLetter(layout, TemplateMatcher().match(layout, aspect).template, offered)

    private fun candidate(raw: String) = offered.rows.first { it.candidate.raw.replace(" ", "") == raw.replace(" ", "") }.candidate

    @Test
    fun `a label and its value are one row in one zone, and the table is not split across zones`() {
        val total = candidate("1.284,50 €")
        assertThat(zoned.context(total).line).isEqualTo("Gesamtbetrag | 1.284,50€")
        val net = candidate("1.079,41 €")
        assertThat(zoned.context(net).line).isEqualTo("Nettobetrag | 1.079,41")
        // The label's line and the value's line were given different zones by the analyzer; the row has one.
        val row = layout.page(2)!!.lines.filter { it.text == "Gesamtbetrag" || it.text == "1.284,50€" }
        assertThat(row).hasSize(2)
        assertThat(zoned.zoneOf(row[0])).isEqualTo(zoned.zoneOf(row[1]))
        val table = layout.page(2)!!.lines.filter { it.text in setOf("Support-Pauschale", "39,00", "Zusatzschulung Team") }
        assertThat(table.map { zoned.zoneOf(it) }.toSet()).hasSize(1)
    }

    @Test
    fun `the neighbours of a value are the rows above and below it in the physical order of the page`() {
        val ctx = zoned.context(candidate("39,00"))
        assertThat(ctx.line).isEqualTo("5 | Support-Pauschale | 1 | 39,00 | 39,00")
        assertThat(ctx.below).isEqualTo("6 | Anfahrt (2 x 18,50 €) | 2 | 18,50 | 37,00")
        // The line above the invoice number is the line above it on the page, not the last row of the table
        assertThat(zoned.context(candidate("RE-2026-0815")).above).doesNotContain("Hosting")
    }

    @Test
    fun `a number is shown with the words printed before it`() {
        assertThat(zoned.printedLabel(candidate("RE-2026-0815"))).isEqualTo("Rechnung Nr.")
        assertThat(zoned.printedLabel(candidate("KD-40417"))).isEqualTo("Kundennummer")
        assertThat(ZonePrompt.scoringHead("RE-2026-0815", null, "Rechnung Nr.")).isEqualTo("Is «RE-2026-0815», printed after «Rechnung Nr.»")
    }

    private fun read(yes: (String) -> Boolean) = FakePromptSession().apply {
        scorer = { c -> if (yes(c)) 2.0 else -2.0 }
        responder = { _, _ -> "\"text\"" }
    }.let { session ->
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, profile = ModelProfiles.QWEN35_08B.scoring, topicsInFirstStage = false)
        runBlocking { ExtractionV2Pipeline().run(pages, interpreter, 4096, aspect, forcedFamily = "invoice_bill") } to interpreter
    }

    @Test
    fun `the invoice and customer numbers are taken, and the numbers an invoice does not have are none`() {
        val (result, interpreter) = read { c ->
            (c.contains("«RE-2026-0815»") && c.contains("the invoice number")) ||
                (c.contains("«KD-40417»") && c.contains("a customer, member or account holder")) ||
                (c.contains("«RE-2026-0815»") && c.contains("a reference the letter cites")) ||
                (c.contains("Gesamtbetrag | 1.284,50€") && c.contains("the main amount"))
        }
        val slots = result.slots.mapKeys { it.key.json }
        assertThat(slots["invoice_no"]?.normalized).isEqualTo("RE-2026-0815")
        assertThat(slots["customer_no"]?.normalized).isEqualTo("KD-40417")
        assertThat(slots["total"]?.normalized).isEqualTo("1284.50 EUR")
        for (none in listOf("contract_no", "policy_no", "case_no", "tax_no")) assertThat(slots).doesNotContainKey(none)
        // no register digits in any slot, and none was ever offered to one
        assertThat(slots.values.map { it.normalized }.filter { it.contains("000000") }).isEmpty()
        val asked = interpreter.transcript.filter { it.name.startsWith("score:slot:") }.joinToString("\n") { it.question }
        assertThat(asked).doesNotContain("«000000»")
        assertThat(asked).doesNotContain("«DE000000000»")
    }

    @Test
    fun `a reference number the invoice does not have is asked with its printed label, and only on the candidates of its zones`() {
        val (_, interpreter) = read { false }
        val contract = interpreter.transcript.first { it.name == "score:slot:contract_no" }.question
        assertThat(contract).contains("Is «RE-2026-0815», printed after «Rechnung Nr.» (context:")
        assertThat(contract).contains("Is «KD-40417», printed after «Kundennummer» (context:")
        // the invoice's own number is asked as it was: no label, it is not optional for an invoice
        assertThat(interpreter.transcript.first { it.name == "score:slot:invoice_no" }.question).doesNotContain("printed after")
    }

    @Test
    fun `an optional number is taken only when the model leans yes, and the family's own is taken whatever`() {
        val profile = ModelProfiles.QWEN35_08B.scoring
        val contract = QuestionNames.slot("contract_no")
        val invoice = QuestionNames.slot("invoice_no")
        assertThat(profile.slotThreshold(contract, own = false)).isEqualTo(0.0)
        assertThat(profile.slotThreshold(contract, own = true)).isEqualTo(profile.defaultThreshold)
        assertThat(profile.slotThreshold(invoice, own = true)).isEqualTo(profile.defaultThreshold)
        assertThat(profile.slotThreshold(invoice, own = false)).isEqualTo(0.0)
        assertThat(profile.slotThreshold(QuestionNames.slot("fee"), own = true)).isEqualTo(0.0)
        // the core slots every letter has keep taking the best candidate
        assertThat(profile.slotThreshold(QuestionNames.slot("customer_no"), own = false)).isEqualTo(profile.defaultThreshold)
        assertThat(ScoringProfile().slotThreshold(contract, own = false)).isEqualTo(0.0)
    }
}
