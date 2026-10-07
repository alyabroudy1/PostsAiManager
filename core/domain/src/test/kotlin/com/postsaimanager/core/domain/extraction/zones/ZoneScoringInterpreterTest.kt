package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.v2.QuestionGrammars
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The scoring interpreter through the real pipeline, with a fake session whose "model" scores by a rule the test sets. */
class ZoneScoringInterpreterTest {

    private val letter = Letters.invoice

    /** Says Yes (+5) to the pairs of value and statement in [yes], No (-5) to everything else. */
    private fun run(profile: ScoringProfile = ScoringProfile(), yes: (String) -> Boolean): Pair<com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result, FakePromptSession> {
        val session = FakePromptSession().apply {
            scorer = { c -> if (yes(c)) 5.0 else -5.0 }
            responder = { _, _ -> "\"text\"" }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, profile = profile)
        return runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096) } to session
    }

    private fun says(value: String, statement: String): (String) -> Boolean = { c -> c.contains("«$value»") && c.contains(statement) }

    @Test
    fun `the candidate the model says yes to fills the slot and nothing else does`() {
        val (result, session) = run { c ->
            says("28.09.2026", "the date of the letter itself")(c) ||
                says("1.284,50 €", "the main amount")(c) ||
                says("Musterfirma GmbH", "the sender")(c) ||
                says("Erika Mustermann", "the addressee")(c) ||
                c.contains("Is this document an invoice, a bill")
        }
        assertThat(result.documentType?.id).isEqualTo("invoice_bill")
        assertThat(result.slots.entries.first { it.key.json == "letter_date" }.value.normalized).isEqualTo("2026-09-28")
        assertThat(result.slots.entries.first { it.key.json == "total" }.value.normalized).isEqualTo("1284.50 EUR")
        assertThat(result.parties.sender?.name).isEqualTo("Musterfirma GmbH")
        assertThat(result.parties.all.first { it.role == PartyRole.ADDRESSEE }.name).isEqualTo("Erika Mustermann")
        // Nothing was said yes to for the IBAN or the deadline: the answer is none, not the first candidate.
        assertThat(result.slots.keys.map { it.json }).doesNotContain("iban")
        assertThat(result.slots.keys.map { it.json }).doesNotContain("due_date")
        // The header session, the body session that scores, and the session that writes.
        assertThat(session.opens.size).isEqualTo(3)
    }

    @Test
    fun `a body that does not fit the interpreter's own window makes the reading partial`() {
        val session = FakePromptSession().apply { scorer = { -5.0 }; responder = { _, _ -> "\"text\"" } }
        // The interpreter's window is tiny, the pipeline's is not: the pipeline's layout text is complete, the zone render is cut.
        val small = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 1100)
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, small, 4096) }
        assertThat(small.unread).isNotNull()
        assertThat(result.diagnostics.unreadLines).isGreaterThan(0)
        assertThat(result.diagnostics.layoutComplete).isFalse()
        assertThat(ExtractionV2Adapter().adapt(result).inputTruncation).isNotNull()

        val roomy = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 100_000)
        val whole = runBlocking { ExtractionV2Pipeline().run(letter.pages, roomy, 100_000) }
        assertThat(roomy.unread).isNull()
        assertThat(whole.diagnostics.unreadLines).isEqualTo(0)
    }

    @Test
    fun `what is written is asked in a session whose instruction is not yes or no`() {
        val (_, session) = run { false }
        val yesNo = "single word Yes or No"
        // The sessions that score carry the yes/no instruction in their system prompt; the one that writes (the last) does not, because a
        // small model obeys a yes/no instruction over any question: measured, every written answer was "Yes".
        assertThat(session.opens.dropLast(1).all { it.contains(yesNo) }).isTrue()
        assertThat(session.opens.last()).doesNotContain(yesNo)
        assertThat(session.opens.last()).contains("LETTER")
        // Nothing is scored after the writing session opens, and every generated answer is asked in it.
        assertThat(session.asks).isNotEmpty()
    }

    @Test
    fun `the questions about one value share their zone block as a level of the prefix tree, and the text is read whole`() {
        // A yes to the sender makes the party's kind be scored: three statements about the same value.
        val (_, session) = run(ScoringProfile(prefixTree = true)) { c -> c.contains("the sender") }
        // A batch of several candidates decodes the shared block once; a single question has nothing to share.
        assertThat(session.sharedLevels.any { it.isNotEmpty() }).isTrue()
        val tree = session.scored.indices.filter { session.sharedLevels[it].isNotEmpty() }
        assertThat(tree).isNotEmpty()
        // Questions that share their zone block and candidates (the reference slots, the date slots) are scored as one grid.
        assertThat(session.grids).isGreaterThan(0)
        // The kinds of one party are asked about the same value: the block and the value's head are shared, each statement is its own.
        val kinds = session.scored.indices.firstOrNull { session.scored[it].size == ScoringDescriptions.KINDS.size && session.sharedLevels[it].contains("Is «") }
        assertThat(kinds).isNotNull()
        // The scores are those of the whole text: the session hands the scorer shared + continuation.
        assertThat(session.scored[kinds!!].all { it.startsWith("\n\n") && it.contains("Is «") && it.contains("? Answer:") }).isTrue()
        assertThat(session.scored[kinds].map { it.substringAfter("? Answer:").length }.distinct()).hasSize(1)
    }

    @Test
    fun `without the prefix tree every question is read whole, as recorded`() {
        val (_, session) = run { c -> c.contains("the sender") }
        assertThat(session.sharedLevels.all { it.isEmpty() }).isTrue()
        assertThat(session.grids).isEqualTo(0)
        // The same questions, asked one batch per question name, each whole text.
        assertThat(session.scored.flatten().all { it.startsWith("\n\n") }).isTrue()
    }

    @Test
    fun `the prefix tree asks the same questions in the same words as reading them whole`() {
        val whole = run { c -> c.contains("the sender") }.second.scored.flatten().sorted()
        val tree = run(ScoringProfile(prefixTree = true)) { c -> c.contains("the sender") }.second.scored.flatten().sorted()
        assertThat(tree).isEqualTo(whole)
    }

    @Test
    fun `no option label or candidate id is ever shown`() {
        val (_, session) = run { false }
        val idPattern = Regex("\\b[A-Z]{1,2}\\d{1,3}:")
        assertThat(session.scored).isNotEmpty()
        for (c in session.scored.flatten()) assertThat(idPattern.containsMatchIn(c)).isFalse()
        // Neither the prefixes nor the questions carry a candidate table; a question carries its zone and the zone's hint.
        // (The last prefix is the writing session, under the zone readers' instruction; it scores nothing.)
        for (prefix in session.opens.dropLast(1)) assertThat(prefix).doesNotContain("CANDIDATES")
        for (c in session.scored.flatten()) assertThat(c).doesNotContain("CANDIDATES")
        assertThat(session.scored.flatten().any { it.contains("ZONE address-field. HINT:") }).isTrue()
    }

    @Test
    fun `the neighbour glimpse is context only and adds no candidate`() {
        val plain = run { false }.second.scored.flatten()
        val ctx = FakePromptSession().apply { scorer = { -5.0 }; responder = { _, _ -> "\"text\"" } }.also { s ->
            runBlocking {
                ExtractionV2Pipeline().run(letter.pages, ZoneScoringInterpreter(FakeAiEngine(), s, contextTokens = 4096, neighbourContext = true), 4096)
            }
        }.scored.flatten()
        assertThat(plain.none { it.contains("CONTEXT ONLY") }).isTrue()
        val withGlimpse = ctx.filter { it.contains("ZONE address-field.") }
        assertThat(withGlimpse).isNotEmpty()
        assertThat(withGlimpse.all { it.contains("CONTEXT ONLY, the zone just above (") }).isTrue()
        // The same candidates are scored either way (the glimpse selects nothing).
        assertThat(ctx.size).isEqualTo(plain.size)
    }

    @Test
    fun `the abstain threshold decides between a value and none`() {
        // A bill: the general "Document" (no type detected) asks no letter question, so a type is detected here.
        val yesLetterDate: (String) -> Boolean = { c -> says("28.09.2026", "the date of the letter itself")(c) || c.contains("Is this document an invoice, a bill") }
        val strict = ScoringProfile(thresholds = mapOf("slot:letter_date" to 8.0))
        val (result, _) = run(strict, yesLetterDate)
        assertThat(result.slots.keys.map { it.json }).doesNotContain("letter_date")
        val (loose, _) = run(ScoringProfile(thresholds = mapOf("slot:letter_date" to 2.0)), yesLetterDate)
        assertThat(loose.slots.keys.map { it.json }).contains("letter_date")
    }

    @Test
    fun `the sender is never scored as the addressee`() {
        // A model that says yes to every name for every role still cannot make the sender the addressee.
        val (result, _) = run { c -> c.contains("the sender") || c.contains("the addressee") }
        val sender = result.parties.sender
        val addressee = result.parties.all.firstOrNull { it.role == PartyRole.ADDRESSEE }
        if (sender != null && addressee != null) assertThat(addressee.name).isNotEqualTo(sender.name)
    }

    @Test
    fun `the language is asked on its own and the extras are decided by score then named by a short ask`() {
        var named = 0
        val session = FakePromptSession().apply {
            scorer = { c -> if (c.contains(ScoringDescriptions.EXTRA) || c.contains("Is this document an invoice, a bill")) 5.0 else -5.0 }
            responder = { q, _ ->
                when {
                    q.contains("BCP-47") -> "de"
                    q.contains("What does the letter call this value?") -> "\"Gegenstand ${++named}\""
                    else -> "\"text\""
                }
            }
        }
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096) }
        assertThat(result.language).isEqualTo("de")
        assertThat(named).isGreaterThan(0)
        assertThat(named).isAtMost(StructuredGrammar.MAX_EXTRAS)
        assertThat(result.extras.map { it.label }).containsExactlyElementsIn((1..named).map { "Gegenstand $it" })
        // The value is the candidate itself, the naming only labels it.
        assertThat(result.extras.all { it.value.candidateId != null }).isTrue()
        // Each ask has its own small grammar, in the same open body session as the scoring.
        assertThat(session.asks.first { it.question.contains("BCP-47") }.grammar).isEqualTo(QuestionGrammars.language())
        assertThat(session.asks.first { it.question.contains("What does the letter call this value?") }.grammar).isEqualTo(QuestionGrammars.line())
        // The key is the value's own kind.
        val kinds = com.postsaimanager.core.domain.extraction.candidates.CandidateKind.entries.map { it.name.lowercase() }
        assertThat(result.extras.all { it.key in kinds }).isTrue()
        assertThat(session.opens).hasSize(3)
    }

    @Test
    fun `a value the model says no to is not an extra and is not named`() {
        val session = FakePromptSession().apply {
            scorer = { c -> if (c.contains("Is this document an invoice, a bill")) 5.0 else -5.0 }
            responder = { q, _ -> if (q.contains("BCP-47")) "de" else "\"text\"" }
        }
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096) }
        assertThat(result.extras).isEmpty()
        assertThat(session.asks.none { it.question.contains("What does the letter call this value?") }).isTrue()
    }

    @Test
    fun `a failed language or naming ask leaves those out and does not fail the reading`() {
        val session = FakePromptSession().apply {
            scorer = { c -> if (c.contains(ScoringDescriptions.EXTRA) || c.contains("Is this document an invoice, a bill")) 5.0 else -5.0 }
            responder = { q, _ -> if (q.contains("BCP-47") || q.contains("What does the letter call this value?")) null else "\"text\"" }
        }
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096) }
        assertThat(result.language).isNull()
        assertThat(result.extras).isEmpty()
        assertThat(result.documentType?.id).isEqualTo("invoice_bill")
    }

    @Test
    fun `the extras are scored only among the candidates no slot or party took, and never the names`() {
        val yes = { c: String ->
            c.contains("the main amount") || c.contains("the date by which") || c.contains("the sender") || c.contains("the addressee") ||
                c.contains("Is this document an invoice, a bill")
        }
        val session = FakePromptSession().apply {
            scorer = { c -> if (yes(c)) 5.0 else -5.0 }
            responder = { _, _ -> "\"text\"" }
        }
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096) }
        val offered = Prepared(letter.pages).offered
        val takenRaw = (result.slots.values.mapNotNull { it.candidateId } + result.parties.all.mapNotNull { it.value.candidateId })
            .mapNotNull { offered.get(it)?.raw?.replace('\n', ' ') }
        assertThat(takenRaw).isNotEmpty()
        val batch = session.scored.firstOrNull { b -> b.all { it.contains(ScoringDescriptions.EXTRA) } }
        // Nothing in the extras batch is a value that was taken; none of its candidates is a name.
        val scoredRaw = batch.orEmpty().map { it.substringAfter("Is «").substringBefore("»") }
        assertThat(scoredRaw.intersect(takenRaw.toSet())).isEmpty()
        val names = offered.rows.filter { it.candidate.kind == com.postsaimanager.core.domain.extraction.candidates.CandidateKind.NAME }.map { it.candidate.raw.replace('\n', ' ') }
        assertThat(scoredRaw.intersect(names.toSet())).isEmpty()
    }

    @Test
    fun `the extras threshold decides which scored values become extras`() {
        fun extras(threshold: Double): Int {
            val session = FakePromptSession().apply {
                scorer = { c -> if (c.contains(ScoringDescriptions.EXTRA)) 2.0 else if (c.contains("Is this document an invoice, a bill")) 5.0 else -5.0 }
                responder = { q, _ -> if (q.contains("BCP-47")) "de" else if (q.contains("What does the letter call this value?")) "\"Gegenstand\"" else "\"text\"" }
            }
            return session.let { s ->
                runBlocking {
                    ExtractionV2Pipeline().run(
                        letter.pages,
                        ZoneScoringInterpreter(FakeAiEngine(), s, contextTokens = 4096, profile = ScoringProfile(thresholds = mapOf(ScoringDescriptions.EXTRAS_ASK to threshold))),
                        4096,
                    )
                }
                s.asks.count { it.question.contains("What does the letter call this value?") }
            }
        }
        assertThat(extras(1.0)).isGreaterThan(0)
        assertThat(extras(3.0)).isEqualTo(0)
    }

    @Test
    fun `confidence follows the margin and the winner's score through the cuts in the profile`() {
        val yesDate: (String) -> Boolean = { c -> says("28.09.2026", "the date of the letter itself")(c) || c.contains("Is this document an invoice, a bill") }
        // The one date the model likes is +5 against the others' -5: a margin of 10 over the runner-up.
        val sure = ScoringProfile(cuts = ScoreCuts(mediumMargin = 1.0, highMargin = 5.0))
        val (high, _) = run(sure, yesDate)
        val date = high.slots.entries.first { it.key.json == "letter_date" }.value
        assertThat(date.aiConfidence).isEqualTo(com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.HIGH)
        assertThat(date.notes.any { it.startsWith("score margin +") }).isTrue()
        // The same scores under cuts that want a bigger margin give a lower word.
        val strict = ScoringProfile(cuts = ScoreCuts(mediumMargin = 1.0, highMargin = 50.0))
        val (medium, _) = run(strict, yesDate)
        assertThat(medium.slots.entries.first { it.key.json == "letter_date" }.value.aiConfidence)
            .isLessThan(com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.HIGH)
        // A winner that the model itself answers No to (a low absolute score) is LOW, whatever its margin.
        val cautious = ScoringProfile(cuts = ScoreCuts(mediumMargin = 0.0, mediumBest = 100.0, highMargin = 5.0, highBest = 100.0))
        val (low, _) = run(cautious, yesDate)
        assertThat(low.slots.entries.first { it.key.json == "letter_date" }.value.aiConfidence)
            .isAtMost(com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.UNKNOWN)
    }

    @Test
    fun `the general Document, when no type is detected, asks no party and no letter slot`() {
        // Even a model that says Yes to every party and slot gets none of them stored: the type decides which questions are asked.
        val (result, _) = run { c -> !c.contains("Is this document") }
        assertThat(result.documentType?.id).isEqualTo("free_form")
        assertThat(result.parties.all).isEmpty()
        assertThat(result.slots).isEmpty()
    }

    @Test
    fun `none of these is an answer for a party, a name must beat the made-up name by the margin`() {
        fun yes(baseline: Double): (String) -> Boolean = { c ->
            says("Musterfirma GmbH", "the sender")(c) || c.contains("Is this document an invoice, a bill") || (baseline > 0 && c.contains("«Zoltan Quillfeather»"))
        }
        val margin = ScoringProfile(partyBaselineMargins = mapOf("sender" to 1.0))
        // The made-up name is scored No (-5): the real sender (+5) beats it by far and is kept.
        assertThat(run(margin, yes(baseline = 0.0)).first.parties.sender?.name).isEqualTo("Musterfirma GmbH")
        // The model leans Yes on the made-up name as well (+5): the sender no longer beats it by the margin, so the field stays empty.
        assertThat(run(margin, yes(baseline = 1.0)).first.parties.sender).isNull()
        // No margin set for the question: taken as before.
        assertThat(run(ScoringProfile(), yes(baseline = 1.0)).first.parties.sender?.name).isEqualTo("Musterfirma GmbH")
    }

    @Test
    fun `a message has no addressee, only the sender is asked among the parties`() {
        val (result, _) = run { c -> c.contains("Is this document a short message") || says("Musterfirma GmbH", "the sender")(c) || says("Erika Mustermann", "the addressee")(c) }
        assertThat(result.documentType?.id).isEqualTo("message_note")
        assertThat(result.parties.sender?.name).isEqualTo("Musterfirma GmbH")
        assertThat(result.parties.all.none { it.role == PartyRole.ADDRESSEE }).isTrue()
    }

    @Test
    fun `the cuts put a score in a bucket`() {
        val cuts = ScoreCuts(mediumMargin = 0.1, mediumBest = -0.25, highMargin = 0.2, highBest = 0.0)
        assertThat(cuts.word(0.5, 0.3)).isEqualTo("HIGH")
        assertThat(cuts.word(0.5, -0.1)).isEqualTo("MEDIUM") // a big margin, but the winner itself is under the HIGH line
        assertThat(cuts.word(0.15, 0.3)).isEqualTo("MEDIUM")
        assertThat(cuts.word(0.05, 0.3)).isEqualTo("LOW")
        assertThat(cuts.word(0.5, -0.4)).isEqualTo("LOW")
    }

    @Test
    fun `extras are looked for on every zone that holds facts while the generating reader's extras question stays on the body`() {
        fun zones(t: LayoutTemplate, name: String) = t.zones.filter { name in it.asks }.map { it.zone }
        val din = LayoutTemplates.DIN5008_B
        val tags = zones(din, QuestionNames.EXTRAS_SCORED).map { it.tag }
        assertThat(tags).containsExactly("info-block", "body", "payment").inOrder()
        assertThat(zones(din, QuestionNames.EXTRAS).map { it.tag }).containsExactly("body")
        // Wherever a template asks the generating reader's extras, it asks the scoring reader's there too.
        for (t in LayoutTemplates.ALL + LayoutTemplates.GENERIC) {
            assertThat(zones(t, QuestionNames.EXTRAS_SCORED)).containsAtLeastElementsIn(zones(t, QuestionNames.EXTRAS))
        }
    }

    @Test
    fun `the sender falls back to the footer and no other party has a fallback`() {
        assertThat(SlotPlacements.partyFallback(QuestionNames.SENDER).map { it.tag }).containsExactly("footer")
        assertThat(SlotPlacements.partyFallback(QuestionNames.ADDRESSEE)).isEmpty()
    }

    @Test
    fun `every template that has an information block asks for the contact person there`() {
        for (t in listOf(LayoutTemplates.DIN5008_A, LayoutTemplates.DIN5008_B, LayoutTemplates.RTL_DIN, LayoutTemplates.UK_LETTER, LayoutTemplates.US_BLOCK)) {
            val info = t.zones.single { it.zone == LetterZone.INFO_BLOCK }
            assertThat(info.asks).contains(QuestionNames.CONTACT)
        }
    }

    @Test
    fun `the schema's core slots all have a statement of their own`() {
        // The five reference numbers keep the plain statement the device recordings hold word for word (see ScoringDescriptions.ofSlot).
        val recordedPlain = listOf(Slots.INVOICE_NO, Slots.CONTRACT_NO, Slots.POLICY_NO, Slots.CASE_NO, Slots.TAX_NO)
        for (slot in Slots.CORE - recordedPlain.toSet()) assertThat(ScoringDescriptions.ofSlot(slot)).doesNotContain("the ${slot.label.lowercase()}")
        for (slot in recordedPlain) assertThat(ScoringDescriptions.ofSlot(slot)).isEqualTo("the ${slot.label.lowercase()}")
        assertThat(ExtractionSchema.DEFAULT.families.all { it.description.isNotBlank() }).isTrue()
    }
}
