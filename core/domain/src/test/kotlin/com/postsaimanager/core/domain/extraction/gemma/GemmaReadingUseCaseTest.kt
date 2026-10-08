package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.testing.FakeActiveModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The trial end to end with a fake reader: a real letter's layout and candidates, the model's answer scripted, and everything after it
 * (the checks, the pipeline's verifier, the adapter) real. What comes out is the [com.postsaimanager.core.model.DocumentUnderstanding] the
 * rest of the app already stores.
 */
class GemmaReadingUseCaseTest {

    private val provider = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM, supportsImages = true)
    private val pages = Letters.n1.pages

    private fun useCase(reader: GemmaDocumentReader, entities: EntityAnnotator = EntityAnnotator.NONE) = GemmaReadingUseCase(reader, entities, provider)

    private fun read(reader: GemmaDocumentReader, entities: EntityAnnotator = EntityAnnotator.NONE, images: List<String> = listOf("/p1.png"), forced: String? = null) =
        runBlocking { useCase(reader, entities)(pages, images, pageAspect = 0.707f, forcedFamily = forced) }

    /** What a good reading of the dunning letter answers, with every id taken from the letter the reader is shown. */
    private fun goodAnswer(l: GemmaLetter): String {
        val total = l.idOf(CandidateKind.AMOUNT, "64,98")
        return answer(
            mapOf(
                "sender" to party(l.idOf(CandidateKind.NAME, "Nordlicht Mobilfunk GmbH"), "company"),
                "addressee" to party(l.idOf(CandidateKind.NAME, "Erika Mustermann")),
                "dates" to arr(
                    value(l.idOf(CandidateKind.DATE, "25.09.2026"), "LETTER_DATE"),
                    // The original due date lies before the letter: code drops it.
                    value(l.idOf(CandidateKind.DATE, "19.08.2026"), "DUE_DATE"),
                ),
                "amounts" to arr(value(total, "TOTAL_DUE"), value(l.idOf(CandidateKind.AMOUNT, "5,00"), "FEE")),
                "references" to arr(
                    obj("candidateId" to str(l.idOf(CandidateKind.IBAN, "DE02")), "kind" to str("iban")),
                    obj("candidateId" to str(l.idOf(CandidateKind.REFERENCE, "2026-08-771204")), "kind" to str("invoice_no")),
                ),
                "actions" to arr(obj("kind" to str("pay"), "dateId" to str("none"), "amountId" to str(total))),
                "category" to str("bill"),
                "name" to str("Mahnung Mobilfunkrechnung"),
                "summary" to str("Der Anbieter fordert 64,98 € und verlangt die Zahlung innerhalb von 14 Tagen."),
                "keyInfo" to arr(obj("label" to str("Kundennummer"), "value" to str("4402917"))),
            ),
        )
    }

    private fun readGood() = read(ScriptedReader.answering(::goodAnswer)) as GemmaReadingOutcome.Read

    @Test
    @DisplayName("a reading comes out as the understanding the app already stores: family, parties, slots with their meanings")
    fun `the same output types`() {
        val u = readGood().understanding

        assertThat(u.documentType).isEqualTo("invoice_bill")
        assertThat(u.modelUsed).isTrue()
        assertThat(u.language).isEqualTo("de")
        assertThat(u.entities.first { it.role == EntityRole.SENDER }.name).contains("Nordlicht Mobilfunk GmbH")
        assertThat(u.entities.first { it.role == EntityRole.RECIPIENT }.name).contains("Erika Mustermann")

        val amount = u.facts.first { it.label == "Amount" }
        assertThat(amount.provenance?.role).isEqualTo("meaning:TOTAL_DUE")
        assertThat(u.facts.first { it.label == "Document Date" }.value).contains("25.09.2026")
        assertThat(u.facts.first { it.label == "IBAN" }).isNotNull()
        assertThat(u.facts.first { it.label == "Invoice Number" }.value).contains("2026-08-771204")
        // The fee is a slot of the family, the key fact an open value under the model's own label.
        assertThat(u.facts.any { it.label == "Fee" }).isTrue()
        assertThat(u.facts.first { it.label == "Kundennummer" }.value).isEqualTo("4402917")
    }

    @Test
    @DisplayName("a due date before the letter's date is dropped, its field stays empty and the drop is in the trace")
    fun `dropped fields stay empty`() {
        val u = readGood().understanding

        assertThat(u.facts.none { it.label == "Deadline" }).isTrue()
        assertThat(u.readingTrace.any { it.startsWith("gemma dropped:") && it.contains("before the letter's date") }).isTrue()
    }

    @Test
    @DisplayName("the actions are bound to the stored fields the pipeline kept; the name, the summary and the title come with the reading")
    fun `actions name summary title`() {
        val u = readGood().understanding

        val pay = u.actionItems!!.single()
        assertThat(pay).isEqualTo(
            ActionItem("pay", mapOf("amount" to "total", "party" to "sender", "reference" to "invoice_no", "iban" to "iban")),
        )
        assertThat(u.summarySource).isEqualTo(SummarySource.MODEL)
        assertThat(u.summary).contains("64,98")
        assertThat(u.title).contains("Mahnung Mobilfunkrechnung")
        assertThat(u.title).contains("Nordlicht Mobilfunk GmbH")
        // A one-go reading has no second stage to wait for.
        assertThat(u.enrichment).isNull()
    }

    @Test
    @DisplayName("the reader is shown the letter's lines and candidates with ids, the page pictures and the category a person gave")
    fun `what the reader is given`() {
        val reader = ScriptedReader.answering(::goodAnswer)

        read(reader, images = listOf("/p1.png", "/p2.png"), forced = "receipt")

        val request = reader.requests.single()
        assertThat(request.letter.lines.first().id).isEqualTo("L1")
        assertThat(request.letter.candidates.map { it.kind }).containsAtLeast(CandidateKind.NAME, CandidateKind.DATE, CandidateKind.AMOUNT, CandidateKind.IBAN)
        assertThat(request.imagePaths).containsExactly("/p1.png", "/p2.png").inOrder()
        assertThat(request.forcedCategory).isEqualTo("a receipt")
    }

    @Test
    @DisplayName("ML Kit's spans are merged with the shape candidates: a new date becomes a candidate of its own, a known one adds nothing")
    fun `entities are merged`() {
        var seenLines = emptyList<String>()
        val entities = object : EntityAnnotator {
            override suspend fun annotate(lines: List<String>): List<EntitySpan> {
                seenLines = lines
                val dated = lines.indexOfFirst { it.contains("25.09.2026") }
                return listOf(
                    EntitySpan(EntityType.DATE_TIME, dated, "Heiligabend", date = LocalDate.of(2026, 12, 24)),
                    EntitySpan(EntityType.DATE_TIME, dated, "25.09.2026", date = LocalDate.of(2026, 9, 25)),
                )
            }
        }
        val reader = ScriptedReader.answering(::goodAnswer)

        val outcome = read(reader, entities) as GemmaReadingOutcome.Read

        assertThat(seenLines).isNotEmpty()
        val ids = reader.requests.single().letter.candidates.map { it.id }
        assertThat(ids).contains("KD1")
        assertThat(ids).doesNotContain("KD2")
        assertThat(outcome.understanding.readingTrace.first { it.startsWith("entities=") }).contains("1 candidates added")
    }

    @Test
    @DisplayName("ML Kit not available yet (null) is not an error: the reading goes on with the shape candidates")
    fun `entities not yet available`() {
        val reader = ScriptedReader.answering(::goodAnswer)

        val outcome = read(reader, EntityAnnotator.NONE) as GemmaReadingOutcome.Read

        assertThat(reader.requests.single().letter.candidates.none { it.id.startsWith("K") }).isTrue()
        assertThat(outcome.understanding.readingTrace.first { it.startsWith("entities=") }).contains("not available yet")
    }

    // ── the fallback ──

    @Test
    @DisplayName("a reader that cannot run (no model, busy, failed, timed out) is unavailable, so the old reading runs")
    fun `unavailable falls back`() {
        val outcome = read(ScriptedReader.unavailable("no answer (the model is busy)"))

        assertThat(outcome).isInstanceOf(GemmaReadingOutcome.Unavailable::class.java)
        assertThat((outcome as GemmaReadingOutcome.Unavailable).reason).contains("busy")
    }

    @Test
    @DisplayName("an answer that is no JSON is unavailable too, never a half reading")
    fun `unusable answer falls back`() {
        val outcome = read(ScriptedReader { GemmaReaderOutcome.Answered("I cannot read this.", "{}", "", 5L, false) })

        assertThat(outcome).isInstanceOf(GemmaReadingOutcome.Unavailable::class.java)
    }

    @Test
    @DisplayName("a reader that throws is unavailable, so the old reading runs")
    fun `a throwing reader`() {
        val outcome = read(ScriptedReader { error("boom") })

        assertThat(outcome).isInstanceOf(GemmaReadingOutcome.Unavailable::class.java)
    }

    // ── a page the OCR could not read ──

    @Test
    @DisplayName("a page with no text is read from its picture alone: every value is marked to check, what code can refute is dropped")
    fun `picture only`() {
        val reader = ScriptedReader.answering {
            answer(
                mapOf(
                    "sender" to obj("name" to str("شركة الكهرباء"), "kind" to str("company")),
                    "addressee" to obj("name" to str("محمد"), "kind" to str("person")),
                    "dates" to arr(
                        obj("value" to str("2026-10-01"), "meaning" to str("DUE_DATE")),
                        obj("value" to str("بعد غد"), "meaning" to str("DEADLINE")),
                    ),
                    "amounts" to arr(obj("value" to str("120.50 EUR"), "meaning" to str("TOTAL_DUE"))),
                    "references" to arr(
                        obj("value" to str("DE02 1203 0000 0000 2020 51"), "kind" to str("iban")),
                        obj("value" to str("DE02 1203 0000 0000 2020 52"), "kind" to str("iban")),
                    ),
                    "category" to str("bill"),
                    "language" to str("ar"),
                    "name" to str("فاتورة الكهرباء"),
                ),
            )
        }

        val outcome = runBlocking { useCase(reader)(listOf(emptyList()), listOf("/p1.png")) } as GemmaReadingOutcome.Read
        val u = outcome.understanding

        assertThat(reader.requests.single().letter.isImageOnly).isTrue()
        assertThat(u.language).isEqualTo("ar")
        assertThat(u.documentType).isEqualTo("invoice_bill")
        assertThat(u.facts.first { it.label == "Amount" }.value).isEqualTo("120.50 EUR")
        assertThat(u.facts.first { it.label == "Deadline" }.value).isEqualTo("2026-10-01")
        // The unparseable date and the account with a wrong checksum are dropped; nothing is left that code could refute.
        assertThat(u.facts.count { it.label == "IBAN" }).isEqualTo(1)
        assertThat(u.facts.size).isEqualTo(3)
        // Everything the picture gave is to check: below the review line, with a note saying why.
        assertThat(u.facts.all { it.confidence < 0.75f }).isTrue()
        assertThat(u.entities).isNotEmpty()
        assertThat(u.entities.all { it.confidence < 0.75f }).isTrue()
    }

    @Test
    @DisplayName("a page with no text and no picture cannot be read by the trial")
    fun `nothing to read`() {
        val outcome = runBlocking { useCase(ScriptedReader.unavailable())(listOf(emptyList()), emptyList()) }

        assertThat(outcome).isInstanceOf(GemmaReadingOutcome.Unavailable::class.java)
    }
}
