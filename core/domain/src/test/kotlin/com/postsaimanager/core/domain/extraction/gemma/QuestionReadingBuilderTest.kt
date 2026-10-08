package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.LabelValuePairs
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.BlockZones
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.OfferedRow
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The answers of the "Questions" reader mapped onto the verified reading, on the invented Jobcenter letter as the phone's OCR read it. */
class QuestionReadingBuilderTest {

    private val blocks: List<OcrBlock> = checkNotNull(javaClass.getResourceAsStream("/ocr/jobcenter-device.txt")).bufferedReader().readLines()
        .filter { it.isNotBlank() }
        .map { row ->
            val (box, text) = row.split('|', limit = 2)
            val (l, t, r, b) = box.split(',').map { it.toFloat() }
            OcrBlock(text.replace("<NL>", "\n"), TextBounds(l, t, r, b), 0.9f)
        }
    private val pages = listOf(blocks)
    private val layout = LetterLayoutAnalyzer.analyze(pages)
    private val ordered = BlockZones.inReadingOrder(pages, layout)
    private val offered = OfferedCandidates(
        CandidateExtractor.extract(ordered, BlockZones.of(ordered, layout)).candidates.map { OfferedRow(it, emptyList(), listOf(it.page)) },
    )
    private val letter = GemmaLetterBuilder.build(layout, offered, labelPairs = LabelValuePairs.of(ordered, BlockZones.of(ordered, layout)))

    private fun build(text: String) = QuestionReadingBuilder().build(QuestionAnswerParser.parse(text), letter, offered)

    private val answer = """
        SENDER: Jobcenter Musterstadt | authority
        RECIPIENT: Maria Mustermann | person
        CONTACT: Nadine Beispiel | 0123 456-701 | nadine.beispiel@jobcenter-musterstadt.example
        ASKS: no
        DATES: 01.09.2026 — LETTER_DATE; 2026-09-30 — DEADLINE
        AMOUNTS: none
        REFERENCES: 12345BG0007777 — case_no
        TYPE: letter
        TITLE: Eingangsbestätigung Bürgergeld-Antrag
        LANGUAGE: de
        PAID: not_applicable
        EVENT: application_filed
    """.trimIndent()

    @Test
    @DisplayName("names: the sender and the contact are linked to the letter's name candidates when one holds them, with the kind the model gave")
    fun `names`() {
        val v = build(answer)

        val sender = v.parties.first { it.role == PartyRole.SENDER }
        val contact = v.parties.first { it.role == PartyRole.CONTACT }
        assertThat(sender.kind).isEqualTo(PartyKind.AUTHORITY)
        assertThat(contact.kind).isEqualTo(PartyKind.PERSON)
        assertThat(v.parties.map { it.role }).containsExactly(PartyRole.SENDER, PartyRole.ADDRESSEE, PartyRole.CONTACT)
        listOf(sender, contact).forEach { p ->
            if (p.candidateId != null) assertThat(letter.candidate(p.candidateId!!)!!.kind).isEqualTo(CandidateKind.NAME)
        }
        assertThat(contact.reference).isNotEmpty()
    }

    @Test
    @DisplayName("a name the letter does not hold is stored as the model wrote it, never dropped")
    fun `an ungrounded name`() {
        val v = build("SENDER: Fantasie Versicherung AG | company\nRECIPIENT: none")

        val sender = v.parties.single()
        assertThat(sender.role).isEqualTo(PartyRole.SENDER)
        assertThat(sender.candidateId).isNull()
        assertThat(sender.quote).isEqualTo("Fantasie Versicherung AG")
        assertThat(sender.stated).isTrue()
        assertThat(sender.kind).isEqualTo(PartyKind.COMPANY)
    }

    @Test
    @DisplayName("dates: the letter's own candidate when it has the same day, a new typed one when it has not, with the meaning the model chose")
    fun `dates`() {
        val v = build(answer)

        val letterDate = v.dates.first { it.meaningId == "LETTER_DATE" }
        assertThat(letterDate.candidate.normalized).startsWith("2026-09-01")
        assertThat(offered.get(letterDate.candidate.id)).isNotNull()
        val deadline = v.dates.first { it.meaningId == "DEADLINE" }
        assertThat(deadline.candidate.normalized).isEqualTo("2026-09-30")
        assertThat(deadline.candidate.id).startsWith("Q")
        assertThat(v.synthesized.map { it.id }).contains(deadline.candidate.id)
    }

    @Test
    @DisplayName("amounts: typed to cents and currency, one candidate per value, the meaning from the registry or none")
    fun `amounts`() {
        val v = build("AMOUNTS: 64,98 € — TOTAL_DUE; 5,00 EUR — something else; 64,98 EUR — TOTAL_DUE")

        assertThat(v.amounts).hasSize(2)
        val total = v.amounts.first()
        assertThat(total.meaningId).isEqualTo("TOTAL_DUE")
        assertThat(total.candidate.cents).isEqualTo(6498L)
        assertThat(v.amounts[1].meaningId).isNull()
        assertThat(v.amounts[1].candidate.cents).isEqualTo(500L)
    }

    @Test
    @DisplayName("references: the letter's own candidate with the same characters, the contact's phone and e-mail as values of no kind")
    fun `references`() {
        val v = build(answer)

        val phone = v.references.first { it.candidate.kind == CandidateKind.PHONE || it.candidate.raw.contains("456") }
        assertThat(phone.kind).isEqualTo(GemmaVocabulary.OTHER)
        assertThat(v.references.any { it.candidate.raw.contains("jobcenter-musterstadt", ignoreCase = true) }).isTrue()
        assertThat(v.references.any { it.kind == "case_no" }).isTrue()
    }

    @Test
    @DisplayName("the registry words: a category, an event kind, a paid state; an unknown one falls back, never an error")
    fun `registry words`() {
        val v = build(answer)

        assertThat(v.category).isEqualTo("letter")
        assertThat(v.eventKind).isEqualTo("application_filed")
        assertThat(v.paid).isEqualTo(PaidState.NOT_APPLICABLE)
        assertThat(v.language).isEqualTo("de")
        assertThat(v.name).isEqualTo("Eingangsbestätigung Bürgergeld-Antrag")
        assertThat(v.asksReader).isFalse()
        assertThat(v.actions).isEmpty()

        val odd = build("TYPE: spaceship\nEVENT: parade\nPAID: maybe\nLANGUAGE: Deutsch")
        assertThat(odd.category).isEqualTo(GemmaVocabulary.DOCUMENT_CATEGORY)
        assertThat(odd.eventKind).isNull()
        assertThat(odd.paid).isNull()
        assertThat(odd.language).isNull()
    }

    @Test
    @DisplayName("asks: the action kind from the registry with its date (added to the dates when the list lacks it) and the amount to pay")
    fun `actions`() {
        val v = build("ASKS: yes — pay — 2026-10-09\nAMOUNTS: 64,98 EUR — TOTAL_DUE\nDATES: none")

        assertThat(v.asksReader).isTrue()
        val action = v.actions.single()
        assertThat(action.kind).isEqualTo("pay")
        assertThat(v.dates.map { it.candidate.id }).contains(action.dateCandidateId)
        assertThat(action.amountCandidateId).isEqualTo(v.amounts.single().candidate.id)
    }
}
