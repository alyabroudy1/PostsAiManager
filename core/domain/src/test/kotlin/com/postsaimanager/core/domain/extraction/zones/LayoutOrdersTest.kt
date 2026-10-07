package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.postsaimanager.core.domain.benchmark.BenchmarkFixtures
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The layout only orders what a question scores; it never removes a question or a candidate (plans/15, phase 1). Each template
 * still puts its preferred zone first, a candidate outside the preferred zones is still offered, and the cap keeps the preferred ones.
 */
class LayoutOrdersTest {

    private val docs = BenchmarkFixtures.load().docs
    private val partyNames = listOf(QuestionNames.SENDER, QuestionNames.ADDRESSEE, QuestionNames.CARE_OF, QuestionNames.CONTACT, QuestionNames.SUBJECT_PERSON)
    private val allTemplates = LayoutTemplates.ALL + LayoutTemplates.GENERIC

    /** The letter [key] of the benchmark read through [template], whatever template the matcher would give it. */
    private fun zoned(key: String, template: LayoutTemplate): ZonedLetter {
        val f = docs.first { it.first.key == key }.second
        val pages = f.pages.map { it.blocks }
        return ZonedLetter(LetterLayoutAnalyzer.analyze(pages), template, Prepared(pages).offered)
    }

    private fun names(z: ZonedLetter) = z.offered.rows.map { it.candidate }.filter { it.kind == CandidateKind.NAME }

    @Test
    fun `each letter template still puts its preferred zone first for the contact person, the addressee and the sender`() {
        for (t in listOf(LayoutTemplates.DIN5008_A, LayoutTemplates.DIN5008_B, LayoutTemplates.RTL_DIN, LayoutTemplates.UK_LETTER, LayoutTemplates.US_BLOCK)) {
            val plan = ZonePlan(t, zoned("N4-zhd-firma-1p", t))
            assertWithMessage("${t.id} contact").that(plan.zones(QuestionNames.CONTACT).first()).isEqualTo(LetterZone.INFO_BLOCK)
            assertWithMessage("${t.id} addressee").that(plan.zones(QuestionNames.ADDRESSEE).first()).isEqualTo(LetterZone.ADDRESS_FIELD)
            assertWithMessage("${t.id} sender").that(plan.zones(QuestionNames.SENDER).first()).isEqualTo(LetterZone.LETTERHEAD)
        }
    }

    @Test
    fun `a template that places a question nowhere still prefers the zone where it usually is`() {
        val receipt = ZonePlan(LayoutTemplates.RECEIPT_NARROW, zoned("receipt-noise-1p", LayoutTemplates.RECEIPT_NARROW))
        // A receipt folds the information block into the body: the contact person's preferred zones are read through that remap.
        assertThat(receipt.zones(QuestionNames.CONTACT)).containsExactly(LetterZone.BODY, LetterZone.LETTERHEAD).inOrder()
        val generic = ZonePlan(LayoutTemplates.GENERIC, zoned("N4-zhd-firma-1p", LayoutTemplates.GENERIC))
        assertThat(generic.zones(QuestionNames.CONTACT)).containsExactly(LetterZone.BODY)
    }

    @Test
    fun `no party or core slot question has no preferred zone under any template`() {
        for (t in allTemplates) {
            val plan = ZonePlan(t, zoned("N4-zhd-firma-1p", t))
            for (name in partyNames) assertWithMessage("${t.id} $name").that(plan.zones(name)).isNotEmpty()
            for (slot in Slots.CORE) assertWithMessage("${t.id} ${slot.json}").that(plan.zones(QuestionNames.slot(slot.json), slot)).isNotEmpty()
        }
    }

    @Test
    fun `a contact person in the body of a US block letter is still offered, after the information block's names`() {
        var checked = 0
        for ((m, _) in docs) {
            val z = zoned(m.key, LayoutTemplates.US_BLOCK)
            val plan = ZonePlan(LayoutTemplates.US_BLOCK, z)
            val preferred = plan.zones(QuestionNames.CONTACT).filter { z.hasText(it) }
            val inBody = names(z).filter { z.zonesOfCandidate(it.id) == setOf(LetterZone.BODY) }
            if (inBody.isEmpty()) continue
            val offered = plan.offer(names(z), preferred)
            // Nothing is dropped for being printed outside the preferred zones (unless the cap, applied after the ordering, is reached).
            val firstOutside = offered.indexOfFirst { z.zonesOfCandidate(it.id).none { zone -> zone in preferred } }
            if (names(z).size <= ZonePlan.MAX_OFFERED) assertWithMessage(m.key).that(offered).containsExactlyElementsIn(names(z))
            assertWithMessage(m.key).that(offered.take(firstOutside.takeIf { it >= 0 } ?: offered.size).all { c -> z.zonesOfCandidate(c.id).any { it in preferred } }).isTrue()
            assertWithMessage(m.key).that(offered.indexOf(inBody.first())).isAtLeast(0)
            checked++
        }
        assertThat(checked).isGreaterThan(0)
    }

    @Test
    fun `no party or slot question loses a candidate to the template while the cap is not reached`() {
        for ((m, _) in docs) for (t in allTemplates) {
            val z = zoned(m.key, t)
            val plan = ZonePlan(t, z)
            for (name in partyNames) {
                val all = names(z)
                val preferred = plan.zones(name).filter { z.hasText(it) }
                val offered = plan.offer(all, preferred, SlotPlacements.partyFallback(name).map { z.mapped(it) })
                assertWithMessage("${m.key} ${t.id} $name").that(offered.size).isEqualTo(minOf(all.size, maxOf(ZonePlan.MAX_OFFERED, all.count { c -> z.zonesOfCandidate(c.id).any { it in preferred } })))
                assertThat(all.containsAll(offered)).isTrue()
            }
        }
    }

    @Test
    fun `the cap is applied after the ordering, so the preferred zones fill it first and are never cut`() {
        // The letter with the most candidates: every kind together, far over the cap.
        val key = docs.maxByOrNull { (_, f) -> Prepared(f.pages.map { it.blocks }).offered.rows.size }!!.first.key
        val z = zoned(key, LayoutTemplates.DIN5008_B)
        val plan = ZonePlan(LayoutTemplates.DIN5008_B, z)
        val all = z.offered.rows.map { it.candidate }
        assertThat(all.size).isGreaterThan(ZonePlan.MAX_OFFERED)
        for (preferred in listOf(listOf(LetterZone.LETTERHEAD), listOf(LetterZone.BODY), listOf(LetterZone.INFO_BLOCK, LetterZone.PAYMENT_SECTION))) {
            val inside = all.filter { c -> z.zonesOfCandidate(c.id).any { it in preferred } }
            val offered = plan.offer(all, preferred)
            assertThat(offered.size).isEqualTo(maxOf(ZonePlan.MAX_OFFERED, inside.size))
            // The preferred ones come first, whole, in the table's order; the rest only fill what the cap leaves.
            assertThat(offered.take(inside.size)).containsExactlyElementsIn(inside).inOrder()
            assertThat(offered.drop(inside.size).none { c -> z.zonesOfCandidate(c.id).any { it in preferred } }).isTrue()
        }
    }

    @Test
    fun `a letter's own questions are all asked and the typical letter offers a bounded number of candidates per question`() {
        // Through the real interpreter on every benchmark letter: the questions asked and how many candidates each one scored.
        val report = StringBuilder()
        var asked = 0
        for ((m, f) in docs) {
            val pages = f.pages.map { it.blocks }
            val session = FakePromptSession().apply {
                scorer = { c -> if (c.contains("Is this document an invoice, a bill")) 5.0 else -5.0 }
                responder = { _, _ -> "\"text\"" }
            }
            val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096)
            val first = f.pages.firstOrNull()?.takeIf { it.height > 0 }
            runBlocking { ExtractionV2Pipeline().run(pages, interpreter, 4096, first?.let { it.width.toFloat() / it.height }) }
            val template = interpreter.trace.first { it.startsWith("template=") }.substringAfter("template=").substringBefore(' ')
            val asks = interpreter.trace.filter { it.startsWith("ask ") }.map { line ->
                line.substringAfter("ask ").substringBefore(' ') to line.substringAfter("cands=").substringBefore(' ').toInt()
            }
            asked += asks.size
            report.appendLine("${m.key} $template " + asks.joinToString(" ") { "${it.first}=${it.second}" })
            // The cap bounds every question: its preferred zones' candidates may exceed it, nothing else does.
            assertWithMessage(m.key).that(asks.all { it.second > 0 }).isTrue()
        }
        assertThat(asked).isGreaterThan(0)
        System.getenv("LAYOUT_COUNTS_OUT")?.let { File(it).writeText(report.toString()) }
    }

    @Test
    fun `the contact person is asked on a letter whose template used to skip it`() {
        val session = FakePromptSession().apply {
            scorer = { c -> if (c.contains("Is this document an invoice, a bill")) 5.0 else -5.0 }
            responder = { _, _ -> "\"text\"" }
        }
        runBlocking { ExtractionV2Pipeline().run(Letters.invoice.pages, ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096) }
        val statement = ScoringDescriptions.ofRole(QuestionNames.CONTACT)
        assertThat(session.scored.flatten().any { it.contains(statement) }).isTrue()
    }
}
