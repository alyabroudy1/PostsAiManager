package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.candidates.page
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

    private fun answer(type: String = "invoice_bill", parties: String = "", slots: String = "", extras: String = "") =
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
            val r = verify(invoice, answer(type = "medical", slots = slot("fee", id(invoice, CandidateKind.AMOUNT, "1284.50 EUR"))))
            assertThat(r.slots).isEmpty()
            assertThat(r.diagnostics.rejections.single()).contains("fee")
        }

        @Test
        fun `a reference number is core, so a letter filed as another family still keeps its invoice number`() {
            val ref = id(invoice, CandidateKind.REFERENCE, "RE-2026-0815")
            val r = verify(invoice, answer(type = "official_letter", slots = slot("invoice_no", ref)))
            assertThat(r.slots.getValue(Slots.INVOICE_NO).normalized).isEqualTo("RE-2026-0815")
        }

        @Test
        fun `an unknown document type falls back to the unscored family and is reported`() {
            val r = verify(invoice, answer(type = "spaceship"))
            assertThat(r.documentType).isEqualTo(ExtractionSchema.FREE_FORM)
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
        @Test
        fun `a plain number is never accepted in a money slot`() {
            val column = Prepared(listOf(page("Posten||Menge", "Strom||3,50").blocks))
            val number = column.candidates.candidates.single { it.kind == CandidateKind.NUMBER }
            // Numbers are found, but the table offers them for extras only; a money slot cannot name one.
            val r = verify(column, answer(slots = slot("total", number.id, "TOTAL_DUE")))
            assertThat(r.slots).isEmpty()
            assertThat(r.diagnostics.rejections.single()).contains("NUMBER")
        }

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
            // 64,98 is a table cell with no currency next to it; its column header names EUR, so it is an amount by geometry.
            val column = Prepared(listOf(page("Posten||Betrag in EUR", "Strom||64,98").blocks))
            val net = id(column, CandidateKind.AMOUNT, "64.98 EUR")
            val c = column.find(CandidateKind.AMOUNT, "64.98 EUR")!!
            assertThat(c.validation).isEqualTo(Validation.Unchecked)
            assertThat(c.attrs["promoted"]).isEqualTo("column")
            val v = verify(column, answer(slots = slot("total", net, "TOTAL_DUE"))).slots.getValue(Slots.TOTAL)
            assertThat(v.confidence).isAtMost(Caps.UNCHECKED)
            assertThat(v.aiConfidence).isEqualTo(0.9f)
            assertThat(v.blocked).isFalse()
        }

        @Test
        fun `a date kept as printed is treated as a quote, capped and never flagged`() {
            val p = Prepared(listOf(page("Datum: 26 Foobar 2026", "Kundennummer: KD-40417").blocks))
            val c = p.offered.rows.map { it.candidate }.single { it.attrs["unnormalized"] == "true" }
            val v = verify(p, answer(slots = slot("letter_date", c.id, "LETTER_DATE"))).slots.values.single()
            assertThat(v.value).isEqualTo("26 Foobar 2026")
            assertThat(v.aiConfidence).isEqualTo(0.9f)
            assertThat(v.confidence).isAtMost(Caps.QUOTE_EXACT)
            assertThat(v.blocked).isFalse()
            assertThat(v.notes.any { it.contains("quoted") }).isTrue()
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
            // Code found no letter date (no label decides one); the date the model chose anchors the check.
            val original = id(n1, CandidateKind.DATE, "2026-08-19")
            val letterDate = id(n1, CandidateKind.DATE, "2026-09-25")
            val v = verify(
                n1,
                answer(type = "invoice_bill", slots = slot("letter_date", letterDate, "LETTER_DATE") + "," + slot("due_date", original, "DUE_DATE")),
            )
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
        private fun party(role: String, id: String, kind: String = "PERSON", rel: String = "NONE", c: String = "HIGH", n: String? = null) =
            "{\"r\":\"$role\",\"id\":\"$id\",${n?.let { "\"n\":\"$it\"," } ?: ""}\"k\":\"$kind\",\"rel\":\"$rel\",\"c\":\"$c\"}"

        /** A page whose name lines keep their form of address, in three languages. */
        private val honorifics = Prepared(
            listOf(page("Stadtwerke Beispiel GmbH", "Herrn Max Mustermann", "Mrs Erika Beispiel", "السيد أحمد علي").blocks),
        )

        private fun nameId(p: Prepared, raw: String) = p.offered.rows.map { it.candidate }.first { it.kind == CandidateKind.NAME && it.raw == raw }.id

        @Test
        fun `a name candidate keeps its form of address and the model's own name replaces it once its words are found there`() {
            for ((printed, model) in listOf("Herrn Max Mustermann" to "Max Mustermann", "Mrs Erika Beispiel" to "Erika Beispiel", "السيد أحمد علي" to "أحمد علي")) {
                val id = nameId(honorifics, printed)
                val r = verify(honorifics, answer(parties = party("ADDRESSEE", id, n = model)))
                val p = r.parties.addressees.single()
                assertThat(p.name).isEqualTo(model)
                assertThat(p.value.candidateId).isEqualTo(id)
                assertThat(p.value.evidence).isEqualTo(printed)
                assertThat(r.diagnostics.rejections).isEmpty()
            }
        }

        @Test
        fun `without a model name, or with one that is not in the printed line, the printed line is kept`() {
            val id = nameId(honorifics, "Herrn Max Mustermann")
            val none = verify(honorifics, answer(parties = party("ADDRESSEE", id)))
            assertThat(none.parties.addressees.single().name).isEqualTo("Herrn Max Mustermann")
            val invented = verify(honorifics, answer(parties = party("ADDRESSEE", id, n = "Moritz Musterfrau")))
            assertThat(invented.parties.addressees.single().name).isEqualTo("Herrn Max Mustermann")
            assertThat(invented.diagnostics.rejections.single()).contains("Moritz Musterfrau")
        }

        @Test
        fun `a reordered model name is accepted because its words are all in the line`() {
            val id = nameId(honorifics, "Herrn Max Mustermann")
            val r = verify(honorifics, answer(parties = party("ADDRESSEE", id, n = "Mustermann Max")))
            assertThat(r.parties.addressees.single().name).isEqualTo("Mustermann Max")
        }

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
        fun `a second sender is kept, capped below the visibility threshold and noted, never dropped`() {
            val r = verify(n1, answer(parties = party("SENDER", sender, "COMPANY") + "," + party("SENDER", "Erika Mustermann")))
            val senders = r.parties.all.filter { it.role == PartyRole.SENDER }
            assertThat(senders).hasSize(2)
            // The first stays the sender.
            assertThat(r.parties.sender).isSameInstanceAs(senders.first())
            assertThat(senders.first().value.confidence).isAtLeast(ConfidenceCombiner.HIDDEN_BELOW)
            val second = senders.last().value
            assertThat(second.confidence).isAtMost(Caps.SECOND_SENDER)
            assertThat(second.confidence).isLessThan(ConfidenceCombiner.HIDDEN_BELOW)
            assertThat(second.notes.joinToString()).contains("second SENDER")
            assertThat(r.diagnostics.rejections.none { it.contains("second SENDER") }).isTrue()
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
        fun `a summary copied from the letter is not rewarded as a quote`() {
            val f = run("Zahlungserinnerung / 1. Mahnung – Rechnung 2026-08-771204", "Die Rechnung war am 19.08.2026 fällig.")
            assertThat(f.summary!!.origin).isEqualTo(SlotOrigin.MODEL_GENERATED)
            assertThat(f.summary!!.confidence).isAtMost(Caps.GENERATED)
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
            val letterDate = id(n1, CandidateKind.DATE, "2026-09-25")
            val r = verify(
                n1,
                answer(
                    type = "invoice_bill",
                    slots = slot("letter_date", letterDate, "LETTER_DATE"),
                    extras = extra("Rechnung vom", prev, key = "previous_invoice_date"),
                ),
            )
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
        fun `a label with no letter is replaced by the kind's key, numbered when it repeats`() {
            val a = id(n1, CandidateKind.DATE, "2026-08-05")
            val b = id(n1, CandidateKind.DATE, "2026-09-25")
            val r = verify(n1, answer(extras = extra("1", a, key = "date") + "," + extra("2", b, key = "date")))
            assertThat(r.extras.map { it.label }).containsExactly("x:date", "x:date_2").inOrder()
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
            val r = verify(n1, answer(type = "invoice_bill", slots = slot("total", total, "TOTAL_DUE"), extras = extra("Offener Betrag", total)))
            assertThat(r.extras).isEmpty()
            assertThat(r.diagnostics.rejections.single()).contains("already used")
        }

        @Test
        fun `a phone number, an e-mail or a BIC the model is not HIGH sure of is kept but capped below the visibility threshold`() {
            val phone = id(n1, CandidateKind.PHONE, "0800 555 0199")
            val medium = verify(n1, answer(extras = extra("Telefon", phone, c = "MEDIUM")))
            val kept = medium.extras.single().value
            assertThat(kept.confidence).isAtMost(Caps.WEAK_KIND)
            assertThat(kept.confidence).isLessThan(ConfidenceCombiner.HIDDEN_BELOW)
            assertThat(kept.notes.joinToString()).contains("HIGH sure")
            assertThat(medium.diagnostics.rejections).isEmpty()
            val high = verify(n1, answer(extras = extra("Telefon", phone, c = "HIGH")))
            assertThat(high.extras.single().value.confidence).isAtLeast(ConfidenceCombiner.HIDDEN_BELOW)
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
        private fun rule(quote: String) = """"due_date":{"rule":"$quote","r":"DUE_DATE","c":"HIGH"}"""

        @Test
        fun `a period the model quotes as a rule is verified against the letter`() {
            val ok = verify(n1, answer(type = "invoice_bill", slots = rule("innerhalb von 14 Tagen")))
            val v = ok.slots.getValue(Slots.DUE_DATE)
            assertThat(v.origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
            assertThat(v.confidence).isAtMost(Caps.QUOTE_EXACT)
            // The quote holds a number and a unit, so it is read as an ISO period.
            assertThat(v.normalized).isEqualTo("P14D")
            assertThat(v.value).isEqualTo("innerhalb von 14 Tagen")

            val bad = verify(n1, answer(type = "invoice_bill", slots = rule("within six weeks")))
            assertThat(bad.slots).doesNotContainKey(Slots.DUE_DATE)
        }

        @Test
        fun `a quoted period without digits is kept as the quote and nothing more is claimed`() {
            val tax = Prepared(Letters.tax.pages)
            val r = verify(tax, answer(type = "official_letter", slots = """"objection_deadline":{"rule":"innerhalb eines Monats","r":"DEADLINE","c":"HIGH"}"""))
            val v = r.slots.getValue(Slots.OBJECTION_DEADLINE)
            assertThat(v.origin).isEqualTo(SlotOrigin.MODEL_QUOTED)
            assertThat(v.normalized).isEqualTo("innerhalb eines Monats")
        }
    }

    @Nested
    inner class TopicsAndAlternatives {
        private val tax = Prepared(Letters.tax.pages)

        private fun verifyWith(p: Prepared, raw: RawInterpretation) = SelectionVerifier().verify(raw, null, ctx(p))

        private fun rawOf(type: String, topics: List<String>, slots: Map<String, RawSlot> = emptyMap()) =
            RawInterpretation(type = type, language = "de", parties = emptyList(), slots = slots, topics = topics)

        @Test
        fun `a topic's slot is accepted only when the topic holds, and is rejected without it`() {
            val amount = invoice.offered.rows.map { it.candidate }.first { it.kind == CandidateKind.AMOUNT }.id
            val slots = mapOf("previous_amount" to RawSlot(id = amount, confidence = "HIGH"))
            val with = verifyWith(invoice, rawOf("official_letter", listOf("insurance"), slots))
            assertThat(with.slots.keys.map { it.json }).contains("previous_amount")
            val without = verifyWith(invoice, rawOf("official_letter", emptyList(), slots))
            assertThat(without.slots).isEmpty()
            assertThat(without.diagnostics.rejections.single()).contains("previous_amount")
        }

        @Test
        fun `only the best two topics add their slots, and a topic the schema does not know is dropped`() {
            val r = verifyWith(tax, rawOf("official_letter", listOf("astrology", "tax", "government", "insurance")))
            assertThat(r.topics).containsExactly("tax", "government", "insurance").inOrder()
            val previous = RawSlot(id = invoice.offered.rows.first { it.candidate.kind == CandidateKind.AMOUNT }.candidate.id, confidence = "HIGH")
            // previous_amount belongs to insurance, the third topic: it adds no slot.
            val third = verifyWith(invoice, rawOf("official_letter", listOf("tax", "government", "insurance"), mapOf("previous_amount" to previous)))
            assertThat(third.slots).isEmpty()
        }

        @Test
        fun `the runners-up become alternatives, offered candidates only, with no repeat of the value or of each other, at most three`() {
            val amounts = invoice.offered.rows.map { it.candidate }.filter { it.kind == CandidateKind.AMOUNT }
            val chosen = id(invoice, CandidateKind.AMOUNT, "1284.50 EUR")
            val others = amounts.filter { it.id != chosen }
            val raw = rawOf(
                "invoice_bill", emptyList(),
                mapOf(
                    "total" to RawSlot(
                        id = chosen, role = "GROSS", confidence = "HIGH",
                        alternatives = listOf(RawAlternative("A99", 3.0), RawAlternative(chosen, 2.9)) +
                            others.take(4).mapIndexed { i, c -> RawAlternative(c.id, 2.0 - i) } + RawAlternative(others.first().id, 0.1),
                    ),
                ),
            )
            val v = verifyWith(invoice, raw).slots.getValue(Slots.TOTAL)
            // A99 is not offered, the chosen value is not its own alternative, the first of the others appears once.
            assertThat(others.size).isAtLeast(2)
            assertThat(v.alternatives).hasSize(minOf(SelectionVerifier.MAX_ALTERNATIVES, others.size))
            assertThat(v.alternatives.map { it.normalized }).doesNotContain("1284.50 EUR")
            assertThat(v.alternatives.map { it.normalized }.toSet()).hasSize(v.alternatives.size)
            assertThat(v.alternatives.map { it.score }).containsExactlyElementsIn(listOf(2.0f, 1.0f, 0.0f).take(v.alternatives.size)).inOrder()
            assertThat(v.alternatives.first().value).isEqualTo(others.first().raw.trim())
            assertThat(v.alternatives.first().page).isEqualTo(others.first().page)
            // They are what else the letter offered, never a claim: the confidence is the chosen value's own.
            assertThat(v.confidence).isEqualTo(verifyWith(invoice, raw.copy(slots = mapOf("total" to RawSlot(id = chosen, role = "GROSS", confidence = "HIGH")))).slots.getValue(Slots.TOTAL).confidence)
        }

        @Test
        fun `the layout template and the addresses of the reading are carried to the result`() {
            val address = com.postsaimanager.core.model.PostalAddress(lines = listOf("Erika Mustermann", "54321 Beispieldorf"))
            val raw = rawOf("invoice_bill", emptyList()).copy(
                layoutTemplate = "din5008_b", addresses = mapOf(PartyRole.ADDRESSEE to address), senderAddressAlternatives = listOf(address),
            )
            val r = verifyWith(invoice, raw)
            assertThat(r.layoutTemplate).isEqualTo("din5008_b")
            assertThat(r.addresses).containsExactly(PartyRole.ADDRESSEE, address)
            assertThat(r.senderAddressAlternatives).containsExactly(address)
        }
    }
}
