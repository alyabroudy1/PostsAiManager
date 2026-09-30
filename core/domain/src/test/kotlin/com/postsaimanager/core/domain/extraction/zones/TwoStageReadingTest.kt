package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.model.EnrichmentTicket
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

    private fun session() = FakePromptSession().apply {
        scorer = { c ->
            when {
                c.contains("Is this document an invoice or bill") -> 5.0
                c.contains("«28.09.2026»") && c.contains("the date of the letter itself") -> 5.0
                c.contains("«1.284,50 €»") && c.contains("the main amount") -> 5.0
                c.contains("«Musterfirma GmbH»") && c.contains("the sender") -> 5.0
                c.contains("«Erika Mustermann»") && c.contains("the addressee") -> 5.0
                c.contains(ScoringDescriptions.EXTRA) -> 2.0
                else -> -5.0
            }
        }
        responder = { q, _ ->
            when {
                q.contains("BCP-47") -> "de"
                q.contains("What does the letter call this value?") -> "\"Gegenstand\""
                q.contains("title") || q.contains("TITLE") -> "\"Rechnung Musterfirma\""
                else -> "\"text\""
            }
        }
    }

    private fun run(stages: ExtractionV2Pipeline.Stages, session: FakePromptSession, ticket: EnrichmentTicket? = null): ExtractionV2Result =
        runBlocking {
            ExtractionV2Pipeline().run(
                letter.pages, ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096), 4096, stages = stages, ticket = ticket,
            )
        }

    @Test
    fun `the first stage reads what a person needs and leaves a ticket, writing nothing`() {
        val session = session()
        val first = run(ExtractionV2Pipeline.Stages.FIRST, session)
        assertThat(first.documentType?.id).isEqualTo("bill")
        assertThat(first.slots.keys.map { it.json }).containsAtLeast("letter_date", "total")
        assertThat(first.parties.sender?.name).isEqualTo("Musterfirma GmbH")
        // Nothing is written yet: no language, no extras, no free text, and not one generated answer was asked.
        assertThat(first.language).isNull()
        assertThat(first.extras).isEmpty()
        assertThat(first.freeText.title).isNull()
        assertThat(session.asks).isEmpty()
        // The ticket says what the second stage must not offer again.
        val ticket = first.enrichment
        assertThat(ticket).isNotNull()
        assertThat(ticket!!.typeId).isEqualTo("bill")
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
        assertThat(second.extras).isNotEmpty()
        assertThat(second.freeText.title?.value).isEqualTo("Rechnung Musterfirma")
        // The first stage's slots and parties are not repeated.
        assertThat(second.slots).isEmpty()
        assertThat(second.parties.all).isEmpty()
        // Only the extras were scored (no type, no party, no slot), in the body session the first stage left (what it established is in
        // its prefix), and the text was written in the writing session that follows.
        assertThat(later.scored.flatten().all { it.contains(ScoringDescriptions.EXTRA) }).isTrue()
        assertThat(later.opens).hasSize(2)
        assertThat(later.opens.first()).contains("ESTABLISHED FROM THE HEADER OF THE LETTER")
        assertThat(later.opens.first()).contains(ticket.established)
        assertThat(ticket.established).isNotEmpty()
        // A value the first stage took is never an extra.
        val offered = com.postsaimanager.core.domain.extraction.v2.Prepared(letter.pages).offered
        val takenRaw = ticket.takenIds.mapNotNull { offered.get(it)?.raw?.replace('\n', ' ') }
        assertThat(takenRaw).isNotEmpty()
        val scoredRaw = later.scored.flatten().map { it.substringAfter("Is «").substringBefore("»") }
        assertThat(scoredRaw.intersect(takenRaw.toSet())).isEmpty()
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
        assertThat(second.freeText.title?.value).isEqualTo(all.freeText.title?.value)
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
    }
}
