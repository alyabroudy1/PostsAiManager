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
    @DisplayName("a model that wrote 'name; kind: x' instead of 'name | x' still gives the bare name, and 'none; kind: person' is no recipient")
    fun `names with a kind label`() {
        val v = build("SENDER: Zahnarztpraxis Dr. Beispiel; kind: authority\nRECIPIENT: none; kind: person")

        val sender = v.parties.single()
        assertThat(sender.role).isEqualTo(PartyRole.SENDER)
        assertThat(sender.quote).isEqualTo("Zahnarztpraxis Dr. Beispiel")
        assertThat(sender.kind).isEqualTo(PartyKind.AUTHORITY)
    }

    @Test
    @DisplayName("the dentist answer as the phone gave it: 'kind: attend' on a line of its own is the attend action, not an unknown word")
    fun `a kind label in the asks`() {
        val v = build("ASKS: yes\nkind: attend; send_documents; object_cancel")

        assertThat(v.actions.map { it.kind }).containsExactly("attend", "send_documents").inOrder()
    }

    @Test
    @DisplayName("labelled nothing ('none | phone: none | e-mail: none', as the dentist answer had it) is no contact and no values")
    fun `labelled none`() {
        val v = build("SENDER: Zahnarztpraxis Dr. Beispiel | kind: company\nCONTACT: none | phone: none | e-mail: none")

        assertThat(v.parties.map { it.role }).containsExactly(PartyRole.SENDER)
        assertThat(v.references).isEmpty()
    }

    @Test
    @DisplayName("a kind that needs an object (send_documents) marked none is no action (as the Stadtwerke answer had it); the real one stays; a note says so")
    fun `an action marked none`() {
        val v = build("ASKS: yes; pay — by 15.10.2026; send_documents — none")

        assertThat(v.actions.map { it.kind }).containsExactly("pay")
        assertThat(v.notes.any { it.startsWith("action marked none by the model") }).isTrue()
    }

    @Test
    @DisplayName("a kind that stands alone with no date (pay — none) is still the action")
    fun `a payment without a date`() {
        val v = build("ASKS: yes; pay — none")

        assertThat(v.actions.map { it.kind }).containsExactly("pay")
    }

    @Test
    @DisplayName("the TYPE line comes right after the sender and recipient, marked as always required (late, a model left it out), and the summary line stays first")
    fun `the type is asked early`() {
        val q = QuestionPrompt.questions(withSummary = true)

        assertThat(q).contains("TYPE: always answer this line, never leave it out")
        assertThat(q.indexOf("SUMMARY:")).isLessThan(q.indexOf("SENDER:"))
        assertThat(q.indexOf("RECIPIENT:")).isLessThan(q.indexOf("TYPE:"))
        assertThat(q.indexOf("TYPE:")).isLessThan(q.indexOf("CONTACT:"))
    }

    @Test
    @DisplayName("an e-mail with a space around the @ (as the Stadtwerke answer had it) is still an e-mail, not a contact person")
    fun `an e-mail with spaces`() {
        val v = build("CONTACT: 0123 456-789; kundenservice @ stadtwerke-musterstadt . example")

        assertThat(v.parties).isEmpty()
        assertThat(v.references.map { it.candidate.raw.replace(" ", "") }).contains("kundenservice@stadtwerke-musterstadt.example")
    }

    @Test
    @DisplayName("an action word that is no registry id is kept as the generic other action, with a note, never dropped")
    fun `an unknown action word`() {
        val v = build("ASKS: yes | not_applicable; go to the appointment — 14.10.2026")

        assertThat(v.actions.map { it.kind }).containsExactly("other_action")
        assertThat(v.notes.any { it.startsWith("action word is not in the registry") }).isTrue()
    }

    @Test
    @DisplayName("a phone number in the contact line is no contact person: it is stored as a detail, the sender stays")
    fun `a shop line as contact`() {
        val v = build("SENDER: Markt Beispiel | company\nCONTACT: 0123 456-000")

        assertThat(v.parties.map { it.role }).containsExactly(PartyRole.SENDER)
        assertThat(v.references.map { it.candidate.raw }).contains("0123 456-000")
    }

    @Test
    @DisplayName("a contact line with a name and a phone number in any order keeps the person and the phone")
    fun `a contact in any order`() {
        val v = build("CONTACT: 0123 456-701 | Nadine Beispiel | nadine@example.example")

        assertThat(v.parties.single().role).isEqualTo(PartyRole.CONTACT)
        assertThat(v.parties.single().kind).isEqualTo(PartyKind.PERSON)
        assertThat(letter.candidate(v.parties.single().candidateId!!)!!.raw).contains("Nadine")
        assertThat(v.references.size).isEqualTo(2)
    }

    @Test
    @DisplayName("a seller alone (a receipt: no recipient named) is stored as the sender, with its full name as the model wrote it")
    fun `a sender without a recipient`() {
        val v = build("SENDER: Markt Beispiel | company\nRECIPIENT: none\nCONTACT: none\nASKS: no | not_applicable\nTYPE: receipt")

        val sender = v.parties.single()
        assertThat(sender.role).isEqualTo(PartyRole.SENDER)
        assertThat(sender.quote).isEqualTo("Markt Beispiel")
        assertThat(v.parties.none { it.role == PartyRole.ADDRESSEE }).isTrue()
    }

    @Test
    @DisplayName("appointment: the main action comes first and a conditional step second, in the order the model gave them")
    fun `an appointment's actions`() {
        val v = build(
            "SENDER: Zahnarztpraxis Dr. Beispiel | company\nRECIPIENT: none\n" +
                "ASKS: yes | not_applicable; attend — 14.10.2026; object_cancel — 13.10.2026\nTYPE: appointment",
        )

        assertThat(v.actions.map { it.kind }).containsExactly("attend", "object_cancel").inOrder()
        assertThat(v.parties.single().quote).isEqualTo("Zahnarztpraxis Dr. Beispiel")
    }

    @Test
    @DisplayName("the questions ask for the full printed name and the main purpose before a conditional step")
    fun `the prompt wording`() {
        val q = QuestionPrompt.questions(withSummary = true)

        assertThat(q).contains("never shortened")
        assertThat(q).contains("the main one first")
        assertThat(q).contains("Something to bring to an appointment is part of attending it")
        // The summary line has no word the model could echo into a summary ("asks for none").
        val summary = q.lines().first { it.startsWith("SUMMARY:") }
        assertThat(summary).doesNotContain("asks")
        assertThat(summary).doesNotContain("none")
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
        val v = build("AMOUNTS: 64,98 € — INVOICE_TOTAL; 5,00 EUR — something else; 64,98 EUR — INVOICE_TOTAL")

        assertThat(v.amounts).hasSize(2)
        val total = v.amounts.first()
        assertThat(total.meaningId).isEqualTo("INVOICE_TOTAL")
        assertThat(total.candidate.cents).isEqualTo(6498L)
        assertThat(v.amounts[1].meaningId).isNull()
        assertThat(v.amounts[1].candidate.cents).isEqualTo(500L)
    }

    @Test
    @DisplayName("the amount to pay is the TOPAY line only: the invoice total in the list never claims it, and the pay action takes it with its date")
    fun `to pay`() {
        val v = build("ASKS: yes | to_pay; pay\nAMOUNTS: 584,20 EUR — TOTAL_DUE; 480,00 EUR — other\nTOPAY: 104,20 EUR — 2026-10-15")

        val toPay = v.amounts.filter { it.meaningId == "TOTAL_DUE" }.single()
        assertThat(toPay.candidate.cents).isEqualTo(10420L)
        assertThat(v.amounts.first { it.candidate.cents == 58420L }.meaningId).isNull()
        val pay = v.actions.single()
        assertThat(pay.amountCandidateId).isEqualTo(toPay.candidate.id)
        assertThat(v.dates.map { it.candidate.id }).contains(pay.dateCandidateId)

        // With no TOPAY the pay action has no amount (the list is not guessed from).
        assertThat(build("ASKS: yes | to_pay; pay\nAMOUNTS: 584,20 EUR — TOTAL_DUE\nTOPAY: none").actions.single().amountCandidateId).isNull()
    }

    @Test
    @DisplayName("the letter's date is the LETTERDATE line, first in the dates with its meaning; none gives no date")
    fun `letter date line`() {
        val v = build("LETTERDATE: 2026-09-25\nDATES: 2026-10-15 — DUE_DATE")

        assertThat(v.dates.first().meaningId).isEqualTo("LETTER_DATE")
        assertThat(v.dates.first().candidate.normalized).startsWith("2026-09-25")
        assertThat(build("LETTERDATE: none").dates).isEmpty()
    }

    @Test
    @DisplayName("the word none is no answer for a party: no party is stored")
    fun `none words`() {
        val v = build("SENDER: Markt Beispiel | company\nRECIPIENT: none\nCONTACT: none | none | none")

        assertThat(v.parties.single().role).isEqualTo(PartyRole.SENDER)
    }

    @Test
    @DisplayName("at most two actions are kept, and the trace notes are no drops")
    fun `at most two actions`() {
        val v = build("ASKS: yes | not_applicable; attend — 2026-10-14; send_documents — 2026-10-13; object_cancel — 2026-10-13")

        assertThat(v.actions).hasSize(2)
        assertThat(v.drops).isEmpty()
        assertThat(v.notes).isNotEmpty()
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
        val v = build("ASKS: yes — pay — 2026-10-09\nTOPAY: 64,98 EUR\nDATES: none")

        assertThat(v.asksReader).isTrue()
        val action = v.actions.single()
        assertThat(action.kind).isEqualTo("pay")
        assertThat(v.dates.map { it.candidate.id }).contains(action.dateCandidateId)
        assertThat(action.amountCandidateId).isEqualTo(v.amounts.single().candidate.id)
    }

    @Test
    @DisplayName("the short format: the paid state is the second part of the ASKS line, then one 'kind — by when' item per thing asked")
    fun `paid state inside the asks line`() {
        val v = build("SUMMARY: A reminder.\nASKS: yes | to_pay; pay — 2026-10-09; reply — 2026-10-12\nTOPAY: 64,98 EUR\nEVENT: payment_reminder\nLANGUAGE: de")

        assertThat(v.paid).isEqualTo(PaidState.TO_PAY)
        assertThat(v.asksReader).isTrue()
        assertThat(v.actions.map { it.kind }).containsExactly("pay", "reply").inOrder()
        assertThat(v.language).isEqualTo("de")

        val none = build("ASKS: no | not_applicable")
        assertThat(none.paid).isEqualTo(PaidState.NOT_APPLICABLE)
        assertThat(none.asksReader).isFalse()
        assertThat(none.actions).isEmpty()
        // A separate PAID line (the long format) is still read when the ASKS line has no paid state.
        assertThat(build("ASKS: no\nPAID: already_paid").paid).isEqualTo(PaidState.ALREADY_PAID)
        // The summary line is no field of the reading.
        assertThat(build("SUMMARY: Something.").name).isNull()
    }
}
