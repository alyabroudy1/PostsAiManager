package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.Caps
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** The verifier on its own: recorded answers (JSON) in, checked result out. No model. */
class SelectionVerifierTest {

    private val invoice = Prepared(Letters.invoice.pages)
    private val n1 = Prepared(Letters.n1.pages)

    private fun ctx(p: Prepared, pages: List<String> = p.pages.map { blocks -> blocks.joinToString("\n") { it.text } }) =
        VerificationContext(p.candidates, p.offered, pages, 0, 0, p.pages.size, p.pages.size)

    private fun verify(p: Prepared, structured: String, text: String? = null): ExtractionV2Result {
        val raw = (InterpretationParser.parse(structured) as InterpretationParser.Parsed.Ok).value
        val t = text?.let { (InterpretationParser.parseText(it) as InterpretationParser.Parsed.Ok).value }
        return SelectionVerifier().verify(raw, t, ctx(p))
    }

    private fun id(p: Prepared, kind: CandidateKind, norm: String): String = p.find(kind, norm)!!.id

    private fun answer(type: String = "bill", parties: String = "", slots: String = "", extras: String = "") =
        """{"type":"$type","tc":"HIGH","lang":"de","parties":[$parties],"s":{$slots},"x":[$extras]}"""

    private fun slot(key: String, id: String, role: String? = null, c: String = "HIGH") =
        "\"$key\":{\"id\":\"$id\"${role?.let { ",\"r\":\"$it\"" } ?: ""},\"c\":\"$c\"}"

    @Nested
    inner class Membership {
        @Test
        fun `an id outside the offered set is rejected, not guessed`() {
            val r = verify(invoice, answer(slots = slot("total", "A99", "TOTAL_DUE")))
            assertThat(r.slots).doesNotContainKey(Slots.TOTAL)
            assertThat(r.diagnostics.rejections.single()).contains("A99")
        }

        @Test
        fun `an id of the wrong kind is rejected`() {
            val iban = id(invoice, CandidateKind.IBAN, "DE89370400440532013000")
            val r = verify(invoice, answer(slots = slot("total", iban, "TOTAL_DUE")))
            assertThat(r.slots).doesNotContainKey(Slots.TOTAL)
            assertThat(r.diagnostics.rejections.single()).contains("IBAN")
        }

        @Test
        fun `a slot that does not belong to the chosen type is rejected`() {
            val r = verify(invoice, answer(type = "health", slots = slot("invoice_no", id(invoice, CandidateKind.REFERENCE, "RE-2026-0815"))))
            assertThat(r.slots).isEmpty()
            assertThat(r.diagnostics.rejections.single()).contains("invoice_no")
        }

        @Test
        fun `an unknown document type falls back to other and is reported`() {
            val r = verify(invoice, answer(type = "spaceship"))
            assertThat(r.documentType).isEqualTo(ExtractionSchema.OTHER)
            assertThat(r.diagnostics.rejections.single()).contains("spaceship")
        }

        @Test
        fun `a valid choice keeps the page, bbox, evidence and normalised value`() {
            val total = id(invoice, CandidateKind.AMOUNT, "1284.50 EUR")
            val v = verify(invoice, answer(slots = slot("total", total, "GROSS"))).slots.getValue(Slots.TOTAL)
            assertThat(v.candidateId).isEqualTo(total)
            assertThat(v.normalized).isEqualTo("1284.50 EUR")
            assertThat(v.page).isEqualTo(2)
            assertThat(v.bbox).isNotNull()
            assertThat(v.evidence).contains("1.284,50")
            assertThat(v.origin).isEqualTo(SlotOrigin.MODEL_CHOICE)
            assertThat(v.slot).isEqualTo(Slots.TOTAL)
        }
    }

    @Nested
    inner class Confidence {
        private val total get() = id(invoice, CandidateKind.AMOUNT, "1284.50 EUR")

        @Test
        fun `a validated value keeps exactly the model's own confidence`() {
            val iban = id(invoice, CandidateKind.IBAN, "DE89370400440532013000")
            for ((word, expected) in listOf("HIGH" to 0.9f, "MEDIUM" to 0.7f, "LOW" to 0.4f)) {
                val v = verify(invoice, answer(slots = slot("iban", iban, c = word))).slots.getValue(Slots.IBAN)
                assertThat(v.aiConfidence).isEqualTo(expected)
                assertThat(v.confidence).isEqualTo(expected)
            }
        }

        @Test
        fun `an amount with no currency is capped at 0_6 but not flagged`() {
            // 380,00 appears once, in a table cell with no currency next to it
            val net = id(invoice, CandidateKind.AMOUNT, "380.00 EUR")
            val c = invoice.find(CandidateKind.AMOUNT, "380.00 EUR")!!
            assertThat(c.validation).isEqualTo(Validation.Unchecked)
            val v = verify(invoice, answer(slots = slot("total", net, "TOTAL_DUE"))).slots.getValue(Slots.TOTAL)
            assertThat(v.confidence).isAtMost(Caps.UNCHECKED)
            assertThat(v.aiConfidence).isEqualTo(0.9f)
            assertThat(v.blocked).isFalse()
        }

        @Test
        fun `one value seen twice is offered as the occurrence that has a currency`() {
            // 64,98 is a table cell without a currency and again in a sentence with one
            val total = n1.find(CandidateKind.AMOUNT, "64.98 EUR")!!
            assertThat(total.validation).isEqualTo(Validation.Valid)
            assertThat(total.raw).contains("€")
        }

        @Test
        fun `amounts that add up as net plus VAT equals gross are valid even without a currency`() {
            val net = invoice.find(CandidateKind.AMOUNT, "1079.41 EUR")!!
            val vat = invoice.find(CandidateKind.AMOUNT, "205.09 EUR")!!
            assertThat(net.validation).isEqualTo(Validation.Valid)
            assertThat(vat.validation).isEqualTo(Validation.Valid)
        }

        @Test
        fun `a value that failed validation is capped at 0_3 and flagged`() {
            val bad = Candidate(
                "A1", CandidateKind.AMOUNT, "5,00 €", "5.00 EUR", 1, null, "5,00 €",
                validation = Validation.Invalid("made up"), attrs = mapOf("cents" to "500", "currency" to "EUR"),
            )
            val offered = OfferedCandidates(listOf(OfferedRow(bad, emptyList(), listOf(1))))
            val set = com.postsaimanager.core.domain.extraction.candidates.CandidateSet(listOf(bad), null)
            val raw = (InterpretationParser.parse(answer(slots = slot("total", "A1", "TOTAL_DUE"))) as InterpretationParser.Parsed.Ok).value
            val v = SelectionVerifier().verify(raw, null, VerificationContext(set, offered, listOf("5,00 €"), 0, 0, 1, 1))
                .slots.getValue(Slots.TOTAL)
            assertThat(v.confidence).isAtMost(Caps.INVALID)
            assertThat(v.blocked).isTrue()
            assertThat(v.needsReview).isTrue()
            assertThat(v.notes.single()).contains("made up")
        }

        @Test
        fun `a role the slot does not expect is capped at 0_4 and flagged`() {
            val v = verify(invoice, answer(slots = slot("total", total, "NET"))).slots.getValue(Slots.TOTAL)
            assertThat(v.confidence).isAtMost(Caps.ROLE_MISMATCH)
            assertThat(v.blocked).isTrue()
            assertThat(v.role).isEqualTo("NET")
        }

        @Test
        fun `choosing the net part of a net plus VAT equals gross triple is flagged, choosing the gross is not`() {
            val net = id(invoice, CandidateKind.AMOUNT, "1079.41 EUR")
            val partial = verify(invoice, answer(slots = slot("total", net, "GROSS")))
            assertThat(partial.slots.getValue(Slots.TOTAL).blocked).isTrue()
            assertThat(partial.slots.getValue(Slots.TOTAL).confidence).isAtMost(Caps.INCONSISTENT)
            assertThat(partial.diagnostics.conflicts.single()).contains("net or VAT part")

            val gross = verify(invoice, answer(slots = slot("total", total, "GROSS")))
            assertThat(gross.slots.getValue(Slots.TOTAL).blocked).isFalse()
            assertThat(gross.diagnostics.conflicts).isEmpty()
        }

        @Test
        fun `a due date before the letter date is capped at 0_5 and reported`() {
            val original = id(n1, CandidateKind.DATE, "2026-08-19")
            val v = verify(n1, answer(type = "reminder_dunning", slots = slot("due_date", original, "DUE_DATE")))
            assertThat(v.slots.getValue(Slots.DUE_DATE).confidence).isAtMost(Caps.DATE_ORDER)
            assertThat(v.diagnostics.conflicts.single()).contains("before the letter date")
        }

        @Test
        fun `the checks name their reasons`() {
            val v = verify(invoice, answer(slots = slot("total", total, "NET"))).slots.getValue(Slots.TOTAL)
            assertThat(v.notes.single()).contains("NET")
        }
    }

    @Nested
    inner class AnchorFromTheModel {
        /** Dates with no words next to them: nothing tells code which one is the letter's own date. */
        private val bare = Prepared(
            listOf(
                listOf(
                    Din.b("28.09.2026", 0.60f, 0.10f),
                    Din.b("20.10.2026", 0.117f, 0.44f),
                    Din.b("20.10.2066", 0.117f, 0.48f),
                ),
            ),
        )

        @Test
        fun `code could not find the letter date, so the dates are unchecked`() {
            assertThat(bare.candidates.letterDate).isNull()
            assertThat(bare.find(CandidateKind.DATE, "2026-10-20")!!.validation).isEqualTo(Validation.Unchecked)
        }

        @Test
        fun `the letter date the model chose anchors the others, which are then range-checked`() {
            val letter = id(bare, CandidateKind.DATE, "2026-09-28")
            val due = id(bare, CandidateKind.DATE, "2026-10-20")
            val r = verify(bare, answer(slots = slot("letter_date", letter, "LETTER_DATE") + "," + slot("due_date", due, "DUE_DATE")))
            assertThat(r.letterDate.toString()).isEqualTo("2026-09-28")
            assertThat(r.slots.getValue(Slots.LETTER_DATE).confidence).isEqualTo(0.9f)
            assertThat(r.slots.getValue(Slots.DUE_DATE).confidence).isEqualTo(0.9f)
        }

        @Test
        fun `an implausible date is caught once the model has named the letter date`() {
            val letter = id(bare, CandidateKind.DATE, "2026-09-28")
            val far = id(bare, CandidateKind.DATE, "2066-10-20")
            val r = verify(bare, answer(slots = slot("letter_date", letter, "LETTER_DATE") + "," + slot("due_date", far, "DUE_DATE")))
            assertThat(r.slots.getValue(Slots.DUE_DATE).confidence).isAtMost(Caps.INVALID)
            assertThat(r.slots.getValue(Slots.DUE_DATE).blocked).isTrue()
        }

        @Test
        fun `without the model's letter date the same dates stay capped as unchecked`() {
            val due = id(bare, CandidateKind.DATE, "2026-10-20")
            val r = verify(bare, answer(slots = slot("due_date", due, "DUE_DATE")))
            assertThat(r.slots.getValue(Slots.DUE_DATE).confidence).isAtMost(Caps.UNCHECKED)
        }
    }

    @Nested
    inner class Parties {
        private fun party(role: String, id: String, kind: String = "PERSON", rel: String = "NONE", c: String = "HIGH") =
            "{\"r\":\"$role\",\"id\":\"$id\",\"k\":\"$kind\",\"rel\":\"$rel\",\"c\":\"$c\"}"

        private val sender get() = n1.findName("Nordlicht Mobilfunk GmbH")!!.id
        private val addressee get() = n1.findName("Erika Mustermann")!!.id

        @Test
        fun `sender and addressee taken from their own zones are not flagged`() {
            val r = verify(n1, answer(parties = party("SENDER", sender, "COMPANY") + "," + party("ADDRESSEE", addressee)))
            assertThat(r.parties.sender!!.value.blocked).isFalse()
            assertThat(r.parties.addressees.single().value.blocked).isFalse()
            assertThat(r.parties.sender!!.value.confidence).isEqualTo(0.9f)
        }

        @Test
        fun `a sender taken from the address field contradicts the layout and is flagged`() {
            val r = verify(n1, answer(parties = party("SENDER", addressee)))
            assertThat(r.parties.sender!!.value.blocked).isTrue()
            assertThat(r.parties.sender!!.value.confidence).isAtMost(Caps.ZONE_MISMATCH)
            assertThat(r.diagnostics.conflicts.single()).contains("address field")
        }

        @Test
        fun `an addressee taken from the letterhead is flagged`() {
            val r = verify(n1, answer(parties = party("ADDRESSEE", sender)))
            assertThat(r.parties.addressees.single().value.blocked).isTrue()
        }

        @Test
        fun `the same party as sender and addressee is a conflict that forces review`() {
            val r = verify(n1, answer(parties = party("SENDER", sender, "COMPANY") + "," + party("ADDRESSEE", sender, "COMPANY")))
            assertThat(r.diagnostics.conflicts.any { it.contains("is also ADDRESSEE") }).isTrue()
            assertThat(r.parties.sender!!.value.blocked).isTrue()
            assertThat(r.parties.addressees.single().value.blocked).isTrue()
            assertThat(r.parties.sender!!.value.confidence).isAtMost(Caps.CONFLICT)
            assertThat(r.parties.addressees.single().value.confidence).isAtMost(Caps.CONFLICT)
            assertThat(r.needsReview).isTrue()
        }

        @Test
        fun `the conflict is found for a quoted name as well`() {
            val r = verify(n1, answer(parties = party("SENDER", "Nordlicht Mobilfunk GmbH") + "," + party("CO_ADDRESSEE", "nordlicht mobilfunk gmbh")))
            assertThat(r.diagnostics.conflicts).isNotEmpty()
        }

        @Test
        fun `a name that is not in the letter is dropped`() {
            val r = verify(n1, answer(parties = party("SENDER", "Volkswagen Financial Services")))
            assertThat(r.parties.sender).isNull()
            assertThat(r.diagnostics.rejections.single()).contains("not in the letter")
        }

        @Test
        fun `an id that is not a name is dropped, never quoted as a name`() {
            val amount = id(n1, CandidateKind.AMOUNT, "64.98 EUR")
            val notOffered = verify(n1, answer(parties = party("ADDRESSEE", "P77")))
            assertThat(notOffered.parties.addressees).isEmpty()
            val wrongKind = verify(n1, answer(parties = party("ADDRESSEE", amount)))
            assertThat(wrongKind.parties.addressees).isEmpty()
            assertThat(wrongKind.diagnostics.rejections.single()).contains("not a name")
        }

        @Test
        fun `a quoted name is capped and marked as quoted`() {
            val r = verify(n1, answer(parties = party("SUBJECT_PERSON", "Erika Mustermann")))
            val p = r.parties.subjectPersons.single()
            assertThat(p.value.candidateId).isNull()
            assertThat(p.value.origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
            assertThat(p.value.quoteMatch).isEqualTo(QuoteMatch.EXACT)
            assertThat(p.value.confidence).isAtMost(Caps.QUOTE_EXACT)
            assertThat(p.value.page).isEqualTo(1)
        }

        @Test
        fun `only one sender is kept`() {
            val r = verify(n1, answer(parties = party("SENDER", sender, "COMPANY") + "," + party("SENDER", "Erika Mustermann")))
            assertThat(r.parties.all.count { it.role == PartyRole.SENDER }).isEqualTo(1)
        }

        @Test
        fun `an unknown role is rejected`() {
            val r = verify(n1, answer(parties = party("BOSS", sender)))
            assertThat(r.parties.all).isEmpty()
        }

        @Test
        fun `a guardian addressee makes the household true`() {
            val r = verify(n1, answer(parties = party("ADDRESSEE", "Erika Mustermann", rel = "GUARDIAN_OF")))
            assertThat(r.parties.household).isTrue()
        }
    }

    @Nested
    inner class FreeText {
        private val text = """{"other":"","title":"Nordlicht: Mahnung","subject":"%s","summary":"%s","qs":["a?","b?","c?","d?"]}"""

        private fun run(subject: String, summary: String) =
            verify(n1, answer(), text.format(subject, summary)).freeText

        @Test
        fun `a subject found in the letter is kept as a quote`() {
            val f = run("Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204", "x")
            assertThat(f.subject!!.origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
            assertThat(f.subject!!.quoteMatch).isEqualTo(QuoteMatch.EXACT)
            assertThat(f.subject!!.confidence).isAtMost(Caps.QUOTE_EXACT)
        }

        @Test
        fun `a subject that is not in the letter is dropped`() {
            val r = verify(n1, answer(), text.format("Ihre Gewinnbenachrichtigung", "x"))
            assertThat(r.freeText.subject).isNull()
            assertThat(r.diagnostics.rejections.any { it.startsWith("subject") }).isTrue()
        }

        @Test
        fun `a summary whose sentences are all in the letter is a quote`() {
            val f = run("Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204", "Die Rechnung war am 19.08.2026 fällig.")
            assertThat(f.summary!!.origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
        }

        @Test
        fun `a summary that is not in the letter is kept but marked as AI-written`() {
            val f = run("Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204", "Der Kunde soll dringend zahlen und sich entschuldigen.")
            assertThat(f.summary!!.origin).isEqualTo(SlotOrigin.MODEL_GENERATED)
            assertThat(f.summary!!.notes.single()).contains("AI summary")
            assertThat(f.summary!!.confidence).isAtMost(Caps.GENERATED)
        }

        @Test
        fun `a summary with one unverifiable sentence is AI-written`() {
            val f = run("Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204", "Die Rechnung war am 19.08.2026 fällig. Wir schenken Ihnen den Betrag.")
            assertThat(f.summary!!.origin).isEqualTo(SlotOrigin.MODEL_GENERATED)
        }

        @Test
        fun `the title is generated, never a fact, and at most three questions are kept`() {
            val f = run("Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204", "x")
            assertThat(f.title!!.origin).isEqualTo(SlotOrigin.MODEL_GENERATED)
            assertThat(f.title!!.confidence).isAtMost(Caps.GENERATED)
            assertThat(f.suggestedQuestions).hasSize(3)
        }

        @Test
        fun `without call 2 there is no free text and the structured reading stands`() {
            val r = verify(n1, answer(slots = slot("iban", id(n1, CandidateKind.IBAN, "DE02120300000000202051"))))
            assertThat(r.freeText.subject).isNull()
            assertThat(r.freeText.title).isNull()
            assertThat(r.slots).containsKey(Slots.IBAN)
        }
    }

    @Nested
    inner class Extras {
        private fun extra(label: String, id: String, v: String = "", c: String = "MEDIUM", key: String = "some_key") =
            "{\"lb\":\"$label\",\"k\":\"$key\",\"id\":\"$id\",\"v\":\"$v\",\"c\":\"$c\"}"

        @Test
        fun `an extra that points at a candidate is kept, with its page and validation`() {
            val prev = id(n1, CandidateKind.DATE, "2026-08-05")
            val r = verify(n1, answer(extras = extra("Rechnung vom", prev, key = "previous_invoice_date")))
            val x = r.extras.single()
            assertThat(x.label).isEqualTo("Rechnung vom")
            assertThat(x.key).isEqualTo("previous_invoice_date")
            assertThat(x.value.candidateId).isEqualTo(prev)
            assertThat(x.value.page).isEqualTo(1)
            assertThat(x.value.confidence).isEqualTo(0.7f)
        }

        @Test
        fun `an extra with a verbatim quote is kept and capped`() {
            val r = verify(n1, answer(extras = extra("Zeichen", "NONE", v = "FIB-Mahn 4402917")))
            val x = r.extras.single()
            assertThat(x.value.origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
            assertThat(x.value.confidence).isAtMost(Caps.QUOTE_EXACT)
        }

        @Test
        fun `a hallucinated extra is dropped`() {
            val r = verify(n1, answer(extras = extra("Mitgliedsnummer", "NONE", v = "MB-99-77-1234")))
            assertThat(r.extras).isEmpty()
            assertThat(r.diagnostics.rejections.single()).contains("not in the letter")
        }

        @Test
        fun `an extra with no candidate and no quote is dropped`() {
            val r = verify(n1, answer(extras = extra("Nichts", "NONE", v = "")))
            assertThat(r.extras).isEmpty()
        }

        @Test
        fun `an extra with an id that is not offered is dropped`() {
            val r = verify(n1, answer(extras = extra("Betrag", "A99")))
            assertThat(r.extras).isEmpty()
        }

        @Test
        fun `an extra may not repeat a value a slot or a party already holds`() {
            val total = id(n1, CandidateKind.AMOUNT, "64.98 EUR")
            val r = verify(n1, answer(type = "reminder_dunning", slots = slot("total", total, "TOTAL_DUE"), extras = extra("Offener Betrag", total)))
            assertThat(r.extras).isEmpty()
            assertThat(r.diagnostics.rejections.single()).contains("already used")
        }

        @Test
        fun `a phone number, an e-mail or a BIC is only kept when the model is HIGH sure`() {
            val phone = id(n1, CandidateKind.PHONE, "0800 555 0199")
            val medium = verify(n1, answer(extras = extra("Telefon", phone, c = "MEDIUM")))
            assertThat(medium.extras).isEmpty()
            val high = verify(n1, answer(extras = extra("Telefon", phone, c = "HIGH")))
            assertThat(high.extras).hasSize(1)
        }

        @Test
        fun `at most six extras are kept`() {
            val dates = n1.offered.rows.map { it.candidate }.filter { it.kind == CandidateKind.REFERENCE || it.kind == CandidateKind.DATE || it.kind == CandidateKind.AMOUNT }
            assertThat(dates.size).isAtLeast(7)
            val many = dates.take(9).mapIndexed { i, c -> extra("Detail $i", c.id) }.joinToString(",")
            val r = verify(n1, answer(extras = many))
            assertThat(r.extras.size).isAtMost(StructuredGrammar.MAX_EXTRAS)
        }

        @Test
        fun `the identity is the printed label, not the model's key`() {
            val a = ExtraValue("Zählernummer", "meter_number", dummyValue())
            val b = ExtraValue("Zählernummer", "meter_no", dummyValue())
            val c = ExtraValue("  zählernummer ", "meter_number", dummyValue())
            assertThat(a.identity).isEqualTo("x:zahlernummer")
            assertThat(b.identity).isEqualTo(a.identity)
            assertThat(c.identity).isEqualTo(a.identity)
            assertThat(ExtraValue("Vertrags-Nr.", "x", dummyValue()).identity).isEqualTo("x:vertrags_nr")
        }

        @Test
        fun `two extras with the same label are one`() {
            val a = id(n1, CandidateKind.DATE, "2026-08-05")
            val b = id(n1, CandidateKind.DATE, "2026-08-19")
            val r = verify(n1, answer(extras = extra("Datum", a, key = "one") + "," + extra("Datum", b, key = "two")))
            assertThat(r.extras).hasSize(1)
        }

        @Test
        fun `a key that is not snake_case is replaced by other, the label still identifies it`() {
            val a = id(n1, CandidateKind.DATE, "2026-08-05")
            val r = verify(n1, answer(extras = extra("Datum", a, key = "!!")))
            assertThat(r.extras.single().key).isEqualTo("other")
        }

        private fun dummyValue() = SlotValue(
            null, null, "v", "v", 1, null, "", SlotOrigin.MODEL_QUOTED, 0.7f, 0.6f, Validation.Unchecked,
        )
    }

    @Nested
    inner class OutgoingAndProof {
        @Test
        fun `an outgoing letter answers with an action and the references it cites`() {
            val inv = id(n1, CandidateKind.REFERENCE, "2026-08-771204")
            val cust = id(n1, CandidateKind.REFERENCE, "4402917")
            val json = """{"type":"outgoing_letter","tc":"MEDIUM","lang":"de","parties":[],"s":{
                "action_kind":{"id":"objection","c":"HIGH"},
                "cited_references":{"ids":["$inv","$cust"],"c":"HIGH"}},"x":[]}"""
            val r = verify(n1, json)
            assertThat(r.documentType).isEqualTo(ExtractionSchema.OUTGOING_LETTER)
            assertThat(r.slots.getValue(Slots.ACTION_KIND).value).isEqualTo("objection")
            assertThat(r.slotLists.getValue(Slots.CITED_REFERENCES).map { it.normalized }).containsExactly("2026-08-771204", "4402917")
        }

        @Test
        fun `an action the schema does not know is rejected`() {
            val r = verify(n1, """{"type":"outgoing_letter","tc":"HIGH","lang":"de","parties":[],"s":{"action_kind":{"id":"threaten","c":"HIGH"}},"x":[]}""")
            assertThat(r.slots).doesNotContainKey(Slots.ACTION_KIND)
            assertThat(r.diagnostics.rejections.single()).contains("threaten")
        }

        @Test
        fun `a payment proof takes an amount, a date and a reference`() {
            val amount = id(n1, CandidateKind.AMOUNT, "64.98 EUR")
            val date = id(n1, CandidateKind.DATE, "2026-09-25")
            val ref = id(n1, CandidateKind.REFERENCE, "2026-08-771204")
            val json = """{"type":"payment_proof","tc":"HIGH","lang":"de","parties":[],"s":{
                "proof_amount":{"id":"$amount","r":"OTHER","c":"HIGH"},
                "proof_date":{"id":"$date","r":"LETTER_DATE","c":"HIGH"},
                "proof_reference":{"id":"$ref","c":"HIGH"}},"x":[]}"""
            val r = verify(n1, json)
            assertThat(r.documentType).isEqualTo(ExtractionSchema.PAYMENT_PROOF)
            assertThat(r.slots.getValue(Slots.PROOF_AMOUNT).normalized).isEqualTo("64.98 EUR")
            assertThat(r.slots.getValue(Slots.PROOF_DATE).normalized).startsWith("2026-09-25")
            assertThat(r.slots.getValue(Slots.PROOF_REFERENCE).normalized).isEqualTo("2026-08-771204")
        }
    }

    @Nested
    inner class Deadlines {
        @Test
        fun `a relative deadline the code found can be chosen by id`() {
            val rel = id(n1, CandidateKind.RELATIVE_DEADLINE, "P14D")
            val r = verify(n1, answer(type = "reminder_dunning", slots = slot("due_date", rel, "DUE_DATE")))
            assertThat(r.slots.getValue(Slots.DUE_DATE).normalized).isEqualTo("P14D")
        }

        @Test
        fun `a period the model quotes as a rule is verified against the letter`() {
            val ok = verify(n1, answer(type = "reminder_dunning", slots = """"due_date":{"rule":"innerhalb von 14 Tagen","r":"DUE_DATE","c":"HIGH"}"""))
            assertThat(ok.slots.getValue(Slots.DUE_DATE).origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
            assertThat(ok.slots.getValue(Slots.DUE_DATE).confidence).isAtMost(Caps.QUOTE_EXACT)

            val bad = verify(n1, answer(type = "reminder_dunning", slots = """"due_date":{"rule":"within six weeks","r":"DUE_DATE","c":"HIGH"}"""))
            assertThat(bad.slots).doesNotContainKey(Slots.DUE_DATE)
        }
    }
}
