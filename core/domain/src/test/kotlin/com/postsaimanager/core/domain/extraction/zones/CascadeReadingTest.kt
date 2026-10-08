package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The regression after plan 15 phase 1, in the reading itself: a question scores the candidates of the zones it prefers FIRST and the other
 * zones' candidates only when none of those is taken (a cascade), what a date means may veto the slot that holds it, and every question
 * leaves one trace line for fitting the margins. A fake model says Yes to exactly the pairs of value and statement a test names.
 */
class CascadeReadingTest {

    private class Read(val result: ExtractionV2Result, val session: FakePromptSession, val trace: List<String>)

    private fun read(pages: List<List<OcrBlock>>, profile: ScoringProfile = ScoringProfile(), yes: (String) -> Boolean): Read {
        val session = FakePromptSession().apply {
            scorer = { c -> if (yes(c)) 5.0 else -5.0 }
            responder = { _, _ -> "\"text\"" }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, profile = profile, topicsInFirstStage = false)
        val result = runBlocking { ExtractionV2Pipeline().run(pages, interpreter, 4096) }
        return Read(result, session, interpreter.trace)
    }

    private fun says(value: String, statement: String): (String) -> Boolean = { c -> c.contains("«$value»") && c.contains(" $statement? Answer:") }

    private fun meaning(id: String) = ValueMeanings.DEFAULT.byId(id)!!.description

    private val sender = ScoringDescriptions.ofRole(QuestionNames.SENDER)
    private val letterDate = ScoringDescriptions.ofSlot(Slots.LETTER_DATE)
    private val dueDate = ScoringDescriptions.ofSlot(Slots.DUE_DATE)

    private fun slotNormalized(result: ExtractionV2Result, key: String) = result.slots.entries.firstOrNull { it.key.json == key }?.value?.normalized

    private fun asks(trace: List<String>, name: String) = trace.filter { it.startsWith("ask $name ") }

    // ── the cascade ──

    @Test
    fun `a name of another zone is never scored when a name of the preferred zones is taken`() {
        // The model likes the addressee's name MORE than the letterhead's for the sender question (what phase 1 let win), but the sender's
        // preferred zones hold a name it says Yes to, so the addressee's name is never even scored for it.
        val r = read(Letters.invoice.pages) { c -> says("Musterfirma GmbH", sender)(c) }
        assertThat(r.result.parties.sender?.name).isEqualTo("Musterfirma GmbH")
        val scoredForSender = r.session.scored.flatten().filter { it.contains(" $sender? Answer:") }
        assertThat(scoredForSender.any { it.contains("«Erika Mustermann»") }).isFalse()
        val line = asks(r.trace, "sender").single()
        assertThat(line).contains("tier=1")
    }

    @Test
    fun `when no name of the preferred zones is taken the other zones' names are scored and can still be the sender`() {
        // Recall is kept: the model only says Yes to a name the sender's zones do not hold.
        val r = read(Letters.invoice.pages) { c -> says("Erika Mustermann", sender)(c) }
        assertThat(r.result.parties.sender?.name).isEqualTo("Erika Mustermann")
        assertThat(r.session.scored.flatten().any { it.contains("«Erika Mustermann»") && it.contains(" $sender? Answer:") }).isTrue()
        assertThat(asks(r.trace, "sender").single()).contains("tier=2")
    }

    @Test
    fun `the cascade scores fewer candidates than offering every name of the page`() {
        val none = read(Letters.invoice.pages) { false }
        val taken = read(Letters.invoice.pages) { c -> says("Musterfirma GmbH", sender)(c) }
        fun scored(read: Read) = asks(read.trace, "sender").single().substringAfter("cands=").substringBefore(' ').toInt()
        // Nothing taken: both tiers were scored (a recall as before); a name taken in the first tier: only the first tier was.
        assertThat(scored(taken)).isLessThan(scored(none))
        val line = asks(taken.trace, "sender").single()
        assertThat(line.substringAfter("first=").substringBefore(' ')).isEqualTo(line.substringAfter("cands=").substringBefore(' '))
    }

    @Test
    fun `every question leaves one trace line with its tier, scores, baseline and the winner's text cut to 40 characters`() {
        val r = read(Letters.invoice.pages, ScoringProfile(baselineMargins = mapOf(QuestionNames.SENDER to 0.0))) { c -> says("Musterfirma GmbH", sender)(c) }
        val line = asks(r.trace, "sender").single()
        for (field in listOf("zones=", "cands=", "first=", "tier=", "pick=", "best=", "second=", "baseline=", "floor=", "text=«Musterfirma GmbH»")) {
            assertThat(line).contains(field)
        }
        assertThat(line).doesNotContain("baseline=- ")
        // The text of a long winner is cut.
        val long = r.trace.filter { it.startsWith("ask ") && it.contains("text=«") }.map { it.substringAfter("text=«").substringBeforeLast("»") }
        assertThat(long.all { it.length <= 40 }).isTrue()
    }

    @Test
    fun `the invented letters score fewer candidates when the first tier is taken, and the counts are reported`() {
        // Per question: the candidates scored when nothing is taken (both tiers: what phase 1 always scored) and when the first tier is.
        val report = StringBuilder()
        for ((name, pages) in listOf("din-letter" to InventedLetters.din, "insurance-3-dates" to InventedLetters.insurance, "jc-1" to InventedLetters.jobcenter)) {
            val both = read(pages) { false }
            val first = read(pages) { true }
            fun counts(r: Read) = r.trace.filter { it.startsWith("ask ") }.associate {
                it.substringAfter("ask ").substringBefore(' ') to it.substringAfter("cands=").substringBefore(' ').toInt()
            }
            val all = counts(both)
            val one = counts(first)
            assertThat(one.keys).isEqualTo(all.keys)
            for (question in all.keys) assertThat(one.getValue(question)).isAtMost(all.getValue(question))
            assertThat(one.values.sum()).isLessThan(all.values.sum())
            report.appendLine("$name total both=${all.values.sum()} first=${one.values.sum()} :: " + all.keys.joinToString(" ") { "$it=${all[it]}/${one[it]}" })
        }
        System.getenv("LAYOUT_COUNTS_INVENTED_OUT")?.let { java.io.File(it).writeText(report.toString()) }
    }

    // ── dates by meaning ──

    @Test
    fun `the insurance letter's three dates keep their own slots, a meaning that contradicts a slot leaves it empty`() {
        val letterDateSlot = says("30.11.2026", letterDate) // the model picks the termination deadline as the letter date
        val dueSlot = says("01.01.2027", dueDate)
        val r = read(InventedLetters.insurance) { c ->
            letterDateSlot(c) || dueSlot(c) ||
                says("30.11.2026", meaning("DEADLINE"))(c) || says("01.01.2027", meaning("DUE_DATE"))(c) || says("12.10.2026", meaning("LETTER_DATE"))(c)
        }
        assertThat(slotNormalized(r.result, "due_date")).isEqualTo("2027-01-01")
        // The deadline means "the last day to cancel", which a letter-date slot does not hold: the slot stays empty, no rule re-assigns it.
        assertThat(slotNormalized(r.result, "letter_date")).isNull()
        assertThat(r.trace.any { it.startsWith("meaning letter_date -> DEADLINE contradicts") }).isTrue()
    }

    @Test
    fun `the din letter's billing period is no due date and its due date is not the letter date`() {
        val r = read(InventedLetters.din) { c ->
            // The model picks the period's first day as the due date and the due date as the letter date: both wrong.
            says("01.09.2025", dueDate)(c) || says("15.10.2026", letterDate)(c) ||
                says("01.09.2025", meaning("PERIOD_START"))(c) || says("15.10.2026", meaning("DUE_DATE"))(c) || says("25.09.2026", meaning("LETTER_DATE"))(c)
        }
        assertThat(slotNormalized(r.result, "due_date")).isNull()
        assertThat(slotNormalized(r.result, "letter_date")).isNull()
        assertThat(r.trace.count { it.contains("contradicts the slot") }).isEqualTo(2)
    }

    @Test
    fun `a date whose meaning fits the slot stays and carries the meaning`() {
        val r = read(InventedLetters.din) { c ->
            says("25.09.2026", letterDate)(c) || says("15.10.2026", dueDate)(c) ||
                says("15.10.2026", meaning("DUE_DATE"))(c) || says("25.09.2026", meaning("LETTER_DATE"))(c)
        }
        assertThat(slotNormalized(r.result, "letter_date")).isEqualTo("2026-09-25")
        assertThat(slotNormalized(r.result, "due_date")).isEqualTo("2026-10-15")
        assertThat(r.result.slots.entries.first { it.key.json == "due_date" }.value.role).isEqualTo("DUE_DATE")
    }

    @Test
    fun `a value with no meaning is not vetoed, and a slot that takes any date is never bound`() {
        val r = read(InventedLetters.din) { c -> says("01.09.2025", dueDate)(c) }
        assertThat(slotNormalized(r.result, "due_date")).isEqualTo("2025-09-01")
        assertThat(MeaningVerdict.boundTo(Slots.CONTRACT_END)).isEmpty()
        assertThat(MeaningVerdict.boundTo(Slots.PROOF_DATE)).isEmpty()
        assertThat(MeaningVerdict.boundTo(Slots.LETTER_DATE)).containsExactly("LETTER_DATE")
        assertThat(MeaningVerdict.boundTo(Slots.DUE_DATE)).containsExactly("DUE_DATE", "DEADLINE")
        assertThat(MeaningVerdict.boundTo(Slots.TOTAL)).isEmpty()
        val periodStart = ValueMeanings.DEFAULT.byId("PERIOD_START")
        assertThat(MeaningVerdict.contradicts(Slots.DUE_DATE, periodStart)).isTrue()
        assertThat(MeaningVerdict.contradicts(Slots.DUE_DATE, null)).isFalse()
        assertThat(MeaningVerdict.contradicts(Slots.CONTRACT_END, periodStart)).isFalse()
        assertThat(ValueMeanings.DEFAULT.of(MeaningKind.DATE).map { it.id }).contains("PERIOD_START")
    }

    // ── candidate quality, through the reading ──

    @Test
    fun `the information block's labels are never offered to a party question`() {
        val r = read(InventedLetters.jobcenter) { false }
        val offered = r.session.scored.flatten().filter { it.contains("? Answer:") }
        for (label in listOf("Ansprechpartnerin", "BG-Nummer", "Telefon", "E-Mail", "Datum")) {
            assertThat(offered.any { it.contains("Is «$label»") }).isFalse()
        }
        // The contact person, a value of that block, is offered.
        assertThat(offered.any { it.contains("Is «Frau Nadine Beispiel»") }).isTrue()
    }

    @Test
    fun `a name that is a field label by the model's own score is dropped, a real one is not`() {
        val contact = ScoringDescriptions.ofRole(QuestionNames.CONTACT)
        val label = ScoringDescriptions.FIELD_LABEL
        val profile = ScoringProfile(fieldLabelMargin = 2.0)
        val tempted = read(InventedLetters.din, profile) { c ->
            says("Frau Ina Beispiel", contact)(c) || says("Frau Ina Beispiel", label)(c)
        }
        // The only contact the model liked is a label by its own judgement (it beats the made-up name): dropped, so there is no contact.
        assertThat(tempted.result.parties.all.none { it.role == PartyRole.CONTACT }).isTrue()
        val real = read(InventedLetters.din, profile) { c -> says("Frau Ina Beispiel", contact)(c) }
        assertThat(real.result.parties.all.any { it.role == PartyRole.CONTACT && it.name == "Frau Ina Beispiel" }).isTrue()
        // A profile with no margin never asks.
        val off = read(InventedLetters.din) { c -> says("Frau Ina Beispiel", contact)(c) || says("Frau Ina Beispiel", label)(c) }
        assertThat(off.session.scored.flatten().none { it.contains(label) }).isTrue()
    }
}
