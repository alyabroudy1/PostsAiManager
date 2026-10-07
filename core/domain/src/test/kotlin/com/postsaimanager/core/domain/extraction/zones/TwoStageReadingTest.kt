package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.actions.ActionQuestions
import com.postsaimanager.core.domain.extraction.text.KeyInfoFormat
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.TicketSlot
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * A reading in two stages: the first decides what a person needs to see (type, parties, amounts, dates) and leaves a ticket; the second,
 * later and on its own, writes the language, the extras and the free text from that ticket. Run together they give the same reading as before.
 */
class TwoStageReadingTest {

    private val letter = Letters.invoice

    /** The words every stored-slot question carries (see [ZonePrompt.keySlotQuestion]). */
    private val KEY_SLOT_ASK = "does the reader need"

    private fun session() = FakePromptSession().apply {
        scorer = { c ->
            when {
                c.contains("Is this document an invoice, a bill") -> 5.0
                c.contains("«28.09.2026»") && c.contains("the date of the letter itself") -> 5.0
                c.contains("«1.284,50 €»") && c.contains("the main amount") -> 5.0
                c.contains("«Musterfirma GmbH»") && c.contains("the sender") -> 5.0
                c.contains("«Erika Mustermann»") && c.contains("the addressee") -> 5.0
                // The stored slot values: the amount matters most to this reader, the date of the letter a little, the rest nothing.
                c.contains(KEY_SLOT_ASK) && c.contains("«Amount: 1.284,50 €»") -> 4.0
                c.contains(KEY_SLOT_ASK) && c.contains("«Document Date:") -> 1.0
                // The action kinds: the letter asks something, and what it asks is to pay; the amount is the one to pay.
                c.contains(ActionQuestions.anything()) -> 3.0
                c.contains(ActionQuestions.kind(ActionKinds.PAY)) -> 4.0
                c.contains("«Amount: 1.284,50 €» the amount the reader is asked to pay") -> 3.0
                else -> -5.0
            }
        }
        responder = { q, _ ->
            when {
                q.contains("BCP-47") -> "de"
                // The key information: one fact the letter prints and the read fields do not hold, one it never printed, one the reading holds.
                q.contains("READ FIELDS") -> "BIC: COBADEFFXXX\nLeistungszeitraum: September 2026\nKundennummer: KD-99999\nGesamt: 1.284,50 €"
                // The summary writer is given the verified facts; a model that keeps to them writes a sentence the gate accepts.
                q.contains("FACTS (verified") -> "\"Musterfirma GmbH verlangt 1.284,50 € von Erika Mustermann.\""
                else -> "\"text\""
            }
        }
    }

    private fun run(stages: ExtractionV2Pipeline.Stages, session: PromptSession, ticket: EnrichmentTicket? = null): ExtractionV2Result =
        runBlocking {
            ExtractionV2Pipeline().run(
                letter.pages, ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096, stages = stages, ticket = ticket,
            )
        }

    @Test
    fun `the first stage reads what a person needs and leaves a ticket, writing nothing`() {
        val session = session()
        val first = run(ExtractionV2Pipeline.Stages.FIRST, session)
        assertThat(first.documentType?.id).isEqualTo("invoice_bill")
        assertThat(first.slots.keys.map { it.json }).containsAtLeast("letter_date", "total")
        assertThat(first.parties.sender?.name).isEqualTo("Musterfirma GmbH")
        // Nothing is written yet: no language, no extras, no free text, no summary, and not one generated answer was asked.
        assertThat(first.language).isNull()
        assertThat(first.extras).isEmpty()
        assertThat(first.freeText.subject).isNull()
        assertThat(first.summary).isNull()
        assertThat(session.asks).isEmpty()
        // The title is composed from what the first stage knows (the family and the sender); the second stage adds the subject.
        assertThat(first.composedTitle?.args).containsExactly("invoice_bill", "Musterfirma GmbH", "").inOrder()
        // The ticket says what the second stage must not offer again, and the verified facts its summary rests on.
        val ticket = first.enrichment
        assertThat(ticket).isNotNull()
        assertThat(ticket!!.typeId).isEqualTo("invoice_bill")
        assertThat(ticket.facts).containsAtLeast("sender", "Musterfirma GmbH", "addressed_to", "Erika Mustermann")
        assertThat(ticket.facts).containsKey("amount")
        val taken = (first.slots.values.mapNotNull { it.candidateId } + first.parties.all.mapNotNull { it.value.candidateId }).toSet()
        assertThat(ticket.takenIds).containsAtLeastElementsIn(taken)
        // The adapter carries it to what the data layer stores.
        assertThat(ExtractionV2Adapter().adapt(first).enrichment).isEqualTo(ticket)
        // The extras were not scored in the first stage.
        assertThat(session.scored.flatten().none { it.contains(ScoringDescriptions.EXTRA) }).isTrue()
    }

    @Test
    fun `the second stage on its own writes the language, the extras and the text, and nothing of the first`() {
        val first = run(ExtractionV2Pipeline.Stages.FIRST, session())
        val ticket = first.enrichment!!
        val later = session()
        val second = run(ExtractionV2Pipeline.Stages.SECOND, later, ticket)
        assertThat(second.language).isEqualTo("de")
        // The key information: the facts the letter prints that no read field holds, labelled as the model wrote them. The one it never
        // printed (a customer number with other digits) and the one the reading already holds (the amount) are dropped.
        assertThat(second.extras.map { it.label to it.value.value }).containsExactly("BIC" to "COBADEFFXXX", "Leistungszeitraum" to "September 2026").inOrder()
        // They are quotes of the letter, never a candidate the model could have chosen.
        assertThat(second.extras.all { it.value.candidateId == null }).isTrue()
        // The reading's own fields were shown to the model, so it does not repeat them.
        val keyInfoAsk = later.asks.single { it.question.contains("READ FIELDS") }
        assertThat(keyInfoAsk.question).contains("Amount: 1.284,50 €")
        assertThat(keyInfoAsk.grammar).isEqualTo(KeyInfoFormat.grammar())
        // The summary is the writer's: the model's sentences, accepted by the gate against the ticket's verified facts.
        assertThat(second.summary?.origin).isEqualTo(com.postsaimanager.core.model.SummarySource.MODEL)
        assertThat(second.summary?.text).contains("1.284,50")
        // The title is composed again, with the sender from the ticket.
        assertThat(second.composedTitle?.args?.take(2)).containsExactly("invoice_bill", "Musterfirma GmbH").inOrder()
        // The first stage's slots and parties are not repeated.
        assertThat(second.slots).isEmpty()
        assertThat(second.parties.all).isEmpty()
        // Only the stored slot values and the actions were scored (no type, no party, no slot, no extra), in the body session the first
        // stage left (what it established is in its prefix), and the text and the key information were written in the writing session
        // that follows: one generation for the key information, in the same session as the summary (the letter is not read again).
        assertThat(later.scored.flatten().all { it.contains(KEY_SLOT_ASK) || ActionQuestions.isActionQuestion(it) }).isTrue()
        assertThat(later.opens).hasSize(2)
        assertThat(later.opens.first()).contains("ESTABLISHED FROM THE HEADER OF THE LETTER")
        assertThat(later.opens.first()).contains(ticket.established)
        assertThat(ticket.established).isNotEmpty()
        assertThat(later.asks.count { it.question.contains("READ FIELDS") }).isEqualTo(1)
    }

    @Test
    fun `the second stage scores the extras under the family's hint and chooses the actions by score, writing no line`() {
        val first = run(ExtractionV2Pipeline.Stages.FIRST, session())
        val later = session()
        val second = run(ExtractionV2Pipeline.Stages.SECOND, later, first.enrichment)
        // The hint of the invoice family is part of the statement every extra is scored under: the key information is what it says matters.
        val hint = com.postsaimanager.core.domain.extraction.v2.ExtractionSchema.INVOICE_BILL.hint
        assertThat(hint).isNotEmpty()
        val scored = later.scored.flatten()
        assertThat(scored).isNotEmpty()
        assertThat(scored.filterNot { ActionQuestions.isActionQuestion(it) }.all { it.contains(hint) }).isTrue()
        // The actions are scored, never asked for: no ask mentions what the reader must do, and the chosen kind is a catalogue entry.
        assertThat(later.asks.none { it.question.contains("reader do") }).isTrue()
        assertThat(scored.count { it.contains(ActionQuestions.anything()) }).isEqualTo(1)
        ActionKinds.ALL.forEach { kind -> assertThat(scored.count { it.contains(ActionQuestions.kind(kind)) }).isEqualTo(1) }
        val action = second.actions!!.single()
        assertThat(action.kind).isEqualTo("pay")
        // The amount is the stored one the reading scored as the amount to pay; the payee is the stored sender.
        assertThat(action.bindings).containsAtLeast("amount", "total", "party", "sender")
        assertThat(scored.any { it.contains("Is «Amount: 1.284,50 €» the amount the reader is asked to pay") }).isTrue()
        // The adapter hands them to what the data layer stores.
        assertThat(ExtractionV2Adapter().adapt(second).actionItems).isEqualTo(second.actions)
    }

    @Test
    fun `the first stage's ticket carries the stored slot values with their labels`() {
        val ticket = run(ExtractionV2Pipeline.Stages.FIRST, session()).enrichment!!
        assertThat(ticket.slots).contains(TicketSlot("total", "Amount", "1.284,50 €"))
        assertThat(ticket.slots.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `the stored slot values are scored in the same batch as the extras, under the hint, and the ones above the threshold are the key slots`() {
        val ticket = run(ExtractionV2Pipeline.Stages.FIRST, session()).enrichment!!
        val later = session()
        val second = run(ExtractionV2Pipeline.Stages.SECOND, later, ticket)

        val hint = ExtractionSchema.INVOICE_BILL.hint
        val batch = later.scored.single { qs -> qs.any { it.contains(KEY_SLOT_ASK) } }
        val slotQuestions = batch.filter { it.contains(KEY_SLOT_ASK) }
        assertThat(slotQuestions).hasSize(ticket.slots.size)
        assertThat(slotQuestions.all { it.contains(hint) }).isTrue()
        assertThat(slotQuestions.any { it.endsWith(ZonePrompt.keySlotQuestion("Amount", "1.284,50 €", hint)) }).isTrue()
        // Nothing but the slot values is in that batch: the scored extras are gone (the facts beyond the read fields are generated).
        assertThat(batch).hasSize(ticket.slots.size)
        assertThat(batch.none { it.contains(ScoringDescriptions.EXTRA) }).isTrue()
        // Only what the model scored above the threshold (0.0 by default) is picked, best first, at most MAX_KEY_INFO: the amount (4.0)
        // and the date of the letter (1.0) are above it.
        assertThat(second.keySlots!!.map { it.key }).containsExactly("total", "letter_date").inOrder()
        assertThat(second.keySlots!!.size).isAtMost(ScoringDescriptions.MAX_KEY_INFO)
        assertThat(second.keySlots!!.map { it.score }).isInOrder(Comparator.reverseOrder<Float>())
        // The adapter hands them to what the data layer stores.
        assertThat(ExtractionV2Adapter().adapt(second).keySlots).isEqualTo(second.keySlots)
    }

    @Test
    fun `the threshold of the key slots is the profile's, and a scoring above none picks none rather than leaving the stored picks`() {
        val ticket = run(ExtractionV2Pipeline.Stages.FIRST, session()).enrichment!!
        val strict = ScoringProfile(thresholds = mapOf(ScoringDescriptions.KEY_SLOTS_ASK to 3.5))
        val result = runBlocking {
            ExtractionV2Pipeline().run(
                letter.pages, ZoneScoringInterpreter(FakeAiEngine(), session(), contextTokens = 4096, profile = strict), 4096,
                stages = ExtractionV2Pipeline.Stages.SECOND, ticket = ticket,
            )
        }
        assertThat(result.keySlots!!.map { it.key }).containsExactly("total")
        val none = runBlocking {
            ExtractionV2Pipeline().run(
                letter.pages,
                ZoneScoringInterpreter(FakeAiEngine(), session(), contextTokens = 4096, profile = ScoringProfile(thresholds = mapOf(ScoringDescriptions.KEY_SLOTS_ASK to 99.0))),
                4096, stages = ExtractionV2Pipeline.Stages.SECOND, ticket = ticket,
            )
        }
        assertThat(none.keySlots).isEmpty()
    }

    @Test
    fun `at most 15 stored slot values are scored, and a ticket with none scores none and leaves the stored picks`() {
        val ticket = run(ExtractionV2Pipeline.Stages.FIRST, session()).enrichment!!
        val many = ticket.copy(slots = (1..20).map { TicketSlot("slot_$it", "Label $it", "value $it") })
        val later = session()
        run(ExtractionV2Pipeline.Stages.SECOND, later, many)
        assertThat(later.scored.flatten().count { it.contains(KEY_SLOT_ASK) }).isEqualTo(ScoringDescriptions.MAX_KEY_SLOT_SCORES)
        assertThat(later.scored.flatten().filter { it.contains(KEY_SLOT_ASK) }.last()).contains("value 15")

        val none = run(ExtractionV2Pipeline.Stages.SECOND, session(), ticket.copy(slots = emptyList()))
        assertThat(none.keySlots).isNull()
    }

    @Test
    fun `a letter that asks nothing leaves an empty list, a failed scoring leaves none so the stored actions stay`() {
        val first = run(ExtractionV2Pipeline.Stages.FIRST, session())
        val nothing = session().apply {
            val base = scorer
            scorer = { c -> if (c.contains(ActionQuestions.anything())) -3.0 else base(c) }
        }
        assertThat(run(ExtractionV2Pipeline.Stages.SECOND, nothing, first.enrichment).actions).isEmpty()
        val inner = session()
        val failing = object : PromptSession by inner {
            override suspend fun score(continuations: List<String>, yes: String, no: String, shared: String): PamResult<List<Double>> =
                if (continuations.any { ActionQuestions.isActionQuestion(it) }) PamResult.Error(PamError.InferenceError("scoring failed"))
                else inner.score(continuations, yes, no, shared)
        }
        val failed = run(ExtractionV2Pipeline.Stages.SECOND, failing, first.enrichment)
        assertThat(failed.actions).isNull()
        assertThat(ExtractionV2Adapter().adapt(failed).actionItems).isNull()
    }

    @Test
    fun `the two stages together read what one go reads`() {
        val all = run(ExtractionV2Pipeline.Stages.ALL, session())
        val first = run(ExtractionV2Pipeline.Stages.FIRST, session())
        val second = run(ExtractionV2Pipeline.Stages.SECOND, session(), first.enrichment)
        fun ExtractionV2Result.firstStage() = listOf(documentType?.id, slots.map { (k, v) -> "${k.json}=${v.normalized}" }.sorted(), parties.all.map { "${it.role}/${it.name}" }.sorted())
        assertThat(first.firstStage()).isEqualTo(all.firstStage())
        assertThat(second.language).isEqualTo(all.language)
        assertThat(second.extras.map { it.label to it.value.candidateId }).isEqualTo(all.extras.map { it.label to it.value.candidateId })
        assertThat(second.composedTitle).isEqualTo(all.composedTitle)
        assertThat(second.summary).isEqualTo(all.summary)
        assertThat(second.actions).isEqualTo(all.actions)
    }

    @Test
    fun `a reading that is not staged is read whole whatever stage is asked`() {
        val engine = FakeAiEngine()
        val interpreter = ModelDocumentInterpreter(engine, contextTokens = 4096)
        assertThat(interpreter.staged).isFalse()
        val result = runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096, stages = ExtractionV2Pipeline.Stages.FIRST) }
        assertThat(result.enrichment).isNull()
    }

    @Test
    fun `a second stage the model could not write leaves an empty result and no failure`() {
        val first = run(ExtractionV2Pipeline.Stages.FIRST, session())
        val failing = session().apply { responder = { _, _ -> null } }
        val second = run(ExtractionV2Pipeline.Stages.SECOND, failing, first.enrichment)
        assertThat(second.language).isNull()
        assertThat(second.extras).isEmpty()
        assertThat(second.freeText.summary).isNull()
        // Every ask failed, but a summary always exists: the template renders it from the verified facts of the ticket.
        assertThat(second.summary?.origin).isEqualTo(com.postsaimanager.core.model.SummarySource.TEMPLATE)
        assertThat(second.summary?.code).isEqualTo("template")
        assertThat(second.summary?.args?.take(3)).containsExactly("invoice_bill", "Musterfirma GmbH", "Erika Mustermann").inOrder()
        // The title is still composed, from the ticket.
        assertThat(second.composedTitle?.args?.take(2)).containsExactly("invoice_bill", "Musterfirma GmbH").inOrder()
    }

    @Test
    fun `a second stage that cannot open its writing session leaves the summary pending`() {
        val first = run(ExtractionV2Pipeline.Stages.FIRST, session())
        val noWriting = session().apply { failOpensAfter = 1 }
        val second = run(ExtractionV2Pipeline.Stages.SECOND, noWriting, first.enrichment)
        assertThat(second.summary).isNull()
    }
}
