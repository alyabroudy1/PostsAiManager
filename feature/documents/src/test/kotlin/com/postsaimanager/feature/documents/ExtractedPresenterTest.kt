package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.SectionKind
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExtractedPresenterTest {

    private var n = 0

    private fun field(
        name: String,
        value: String = "v",
        slotKey: String? = null,
        type: ExtractedFieldType = ExtractedFieldType.OTHER,
        confidence: Float = 0.9f,
        source: ValueSource = ValueSource.MACHINE,
        confirmed: Boolean = false,
        deleted: Boolean = false,
    ) = ExtractedData(
        id = "f${n++}", documentId = "d", fieldName = name, fieldValue = value, fieldType = type,
        confidence = confidence, slotKey = slotKey, source = source, isConfirmed = confirmed, deletedByUser = deleted,
    )

    private fun extra(label: String, confidence: Float = 0.9f, source: ValueSource = ValueSource.MACHINE, confirmed: Boolean = false) =
        field(label, "x", slotKey = "x:" + label.lowercase().replace(' ', '_'), confidence = confidence, source = source, confirmed = confirmed)

    /** A stored address part: `addressee.street`, `sender.city`, ... */
    private fun part(prefix: String, key: String, value: String, confidence: Float = 0.9f, confirmed: Boolean = false, deleted: Boolean = false) =
        field("$prefix $key", value, slotKey = "$prefix.$key", type = ExtractedFieldType.ADDRESS, confidence = confidence, confirmed = confirmed, deleted = deleted)

    private fun doc(
        type: String? = "bill",
        summary: String? = null,
        titleCode: String? = null,
        titleArgs: List<String> = emptyList(),
        summarySource: SummarySource? = null,
        summaryCode: String? = null,
        summaryArgs: List<String> = emptyList(),
        topics: List<String> = emptyList(),
        typeConfidence: Float? = null,
        familySource: FamilySource = FamilySource.MODEL,
    ) = Document(
        id = "d", title = "T", sourceType = SourceType.CAMERA, createdAt = 0, modifiedAt = 0,
        extractionType = type, summary = summary, titleCode = titleCode, titleArgs = titleArgs, summarySource = summarySource,
        summaryCode = summaryCode, summaryArgs = summaryArgs, topics = topics, extractionTypeConfidence = typeConfidence,
        familySource = familySource,
    )

    private fun present(fields: List<ExtractedData>, type: String? = "bill", summary: String? = null, showAll: Boolean = false) =
        ExtractedPresenter.present(doc(type, summary), fields, showAll)

    private fun ExtractedPresentation.kinds() = sections.map { it.kind }

    private fun ExtractedPresentation.slotsIn(kind: SectionKind): List<String?> =
        sections.single { it.kind == kind }.items.flatMap { it.rows }.map { it.slotKey }

    @Nested
    @DisplayName("Summary coming")
    inner class SummaryComing {

        @Test
        fun `the card says the summary is coming while the second stage is pending and there is none yet`() {
            val pending = ExtractedPresenter.present(doc(summary = null), listOf(field("Amount", "64,98 €", "total")), summaryComing = true)
            assertThat(pending.summary.summaryComing).isTrue()
            assertThat(pending.summary.hasSummary).isFalse()
        }

        @Test
        fun `a summary that has landed is shown and nothing is coming any more`() {
            val landed = ExtractedPresenter.present(doc(summary = "Pay 64,98 €."), listOf(field("Amount", "64,98 €", "total")), summaryComing = true)
            assertThat(landed.summary.summaryComing).isFalse()
            assertThat(landed.summary.summaryText).isEqualTo("Pay 64,98 €.")
        }

        @Test
        fun `a template summary that has landed also ends the wait`() {
            val d = doc(summary = null, summaryCode = "template", summaryArgs = listOf("invoice_bill", "", "", "", "", ""), summarySource = SummarySource.TEMPLATE)
            assertThat(ExtractedPresenter.present(d, emptyList(), summaryComing = true).summary.summaryComing).isFalse()
        }

        @Test
        fun `nothing is coming when no second stage is pending`() {
            val none = ExtractedPresenter.present(doc(summary = null), listOf(field("Amount", "64,98 €", "total")))
            assertThat(none.summary.summaryComing).isFalse()
        }
    }

    @Nested
    @DisplayName("Summary card")
    inner class Card {

        @Test
        fun `from, for, type, amount, due and the summary are read from the slots`() {
            val p = present(
                listOf(
                    field("Sender Organization", "Nordlicht Mobilfunk GmbH", "sender"),
                    field("Receiver Name", "Erika Mustermann", "addressee"),
                    field("Amount", "64,98 €", "total"),
                    field("Deadline", "15.10.2026", "due_date"),
                ),
                type = "reminder_dunning",
                summary = "Pay 64,98 € within 14 days.",
            )

            assertThat(p.summary.from!!.value).isEqualTo("Nordlicht Mobilfunk GmbH")
            assertThat(p.summary.forWhom!!.value).isEqualTo("Erika Mustermann")
            assertThat(p.summary.typeId).isEqualTo("reminder_dunning")
            assertThat(p.summary.amount!!.value).isEqualTo("64,98 €")
            assertThat(p.summary.due!!.value).isEqualTo("15.10.2026")
            assertThat(p.summary.summaryText).isEqualTo("Pay 64,98 € within 14 days.")
        }

        @Test
        fun `a line the checks flagged is marked worth checking, a confident one is not`() {
            val p = present(
                listOf(
                    field("Amount", "64,98 €", "total", confidence = 0.4f),
                    field("Deadline", "15.10.2026", "due_date", confidence = 0.9f),
                ),
            )

            assertThat(p.summary.amount!!.worthChecking).isTrue()
            assertThat(p.summary.due!!.worthChecking).isFalse()
        }

        @Test
        fun `the main amount is the first present of the type's amount slots, the objection deadline stands in for a due date`() {
            val p = present(
                listOf(
                    field("New Amount", "612,40 €", "new_amount"),
                    field("Amount Paid", "1,00 €", "proof_amount"),
                    field("Objection Deadline", "01.11.2026", "objection_deadline"),
                ),
                type = "contract_policy",
            )

            assertThat(p.summary.amount!!.value).isEqualTo("612,40 €")
            assertThat(p.summary.due!!.value).isEqualTo("01.11.2026")
        }

        @Test
        fun `rows stored before slot keys still fill the card by their old names`() {
            val p = present(
                listOf(
                    field("Sender Name", "Jobcenter"),
                    field("Receiver Name", "Erika"),
                    field("Amount", "10 €"),
                    field("Deadline", "01.01.2027"),
                ),
                type = null,
            )

            assertThat(p.summary.from!!.value).isEqualTo("Jobcenter")
            assertThat(p.summary.forWhom!!.value).isEqualTo("Erika")
            assertThat(p.summary.amount!!.value).isEqualTo("10 €")
            assertThat(p.summary.due!!.value).isEqualTo("01.01.2027")
        }

        @Test
        fun `a user's edit is what the card shows, and an ignored field is not shown`() {
            val p = present(
                listOf(
                    field("Amount", "70,00 €", "total", source = ValueSource.USER, confirmed = true),
                    field("Deadline", "15.10.2026", "due_date", deleted = true),
                ),
            )

            assertThat(p.summary.amount!!.value).isEqualTo("70,00 €")
            assertThat(p.summary.due).isNull()
        }

        @Test
        fun `a document nothing was read for has an empty card`() {
            assertThat(present(emptyList(), type = null).summary.isEmpty).isTrue()
        }

        @Test
        fun `a composed title hands its args to the card, any other title does not`() {
            val composed = ExtractedPresenter.present(doc(titleCode = "composed", titleArgs = listOf("invoice_bill", "Telekom", "Rechnung")), emptyList())
            assertThat(composed.summary.titleArgs).containsExactly("invoice_bill", "Telekom", "Rechnung").inOrder()
            assertThat(ExtractedPresenter.present(doc(titleCode = "scanned_pages", titleArgs = listOf("2")), emptyList()).summary.titleArgs).isNull()
            assertThat(ExtractedPresenter.present(doc(), emptyList()).summary.titleArgs).isNull()
        }

        @Test
        fun `the badge follows the summary source, and a stored summary with no source reads as the model's`() {
            fun source(d: Document) = ExtractedPresenter.present(d, emptyList()).summary.summarySource
            assertThat(source(doc(summary = "S", summarySource = SummarySource.MODEL))).isEqualTo(SummarySource.MODEL)
            assertThat(source(doc(summary = "S", summarySource = SummarySource.USER))).isEqualTo(SummarySource.USER)
            assertThat(source(doc(summary = "S"))).isEqualTo(SummarySource.MODEL)
            assertThat(source(doc())).isNull()
        }

        @Test
        fun `a template summary hands its args to the card and is badged as from the fields`() {
            val args = listOf("invoice_bill", "Telekom", "", "64,98 €", "", "")
            val card = ExtractedPresenter.present(doc(summaryCode = "template", summaryArgs = args), emptyList()).summary
            assertThat(card.summaryText).isNull()
            assertThat(card.templateArgs).containsExactlyElementsIn(args).inOrder()
            assertThat(card.summarySource).isEqualTo(SummarySource.TEMPLATE)
        }

        @Test
        fun `the person's own summary wins over a template one left in the args`() {
            val d = doc(summary = "Mine", summarySource = SummarySource.USER, summaryCode = "template", summaryArgs = listOf("invoice_bill"))
            val card = ExtractedPresenter.present(d, emptyList()).summary
            assertThat(card.summaryText).isEqualTo("Mine")
            assertThat(card.templateArgs).isNull()
        }
    }

    @Nested
    @DisplayName("Header")
    inner class HeaderTests {

        @Test
        fun `the header carries the stored type, the topics and a confidence level`() {
            val h = { c: Float? -> ExtractedPresenter.present(doc("invoice_bill", topics = listOf("telecom", "shopping"), typeConfidence = c), emptyList()).header }
            assertThat(h(0.9f).typeId).isEqualTo("invoice_bill")
            assertThat(h(0.9f).topics).containsExactly("telecom", "shopping").inOrder()
            assertThat(h(0.9f).confidence).isEqualTo(Confidence.HIGH)
            assertThat(h(0.6f).confidence).isEqualTo(Confidence.MEDIUM)
            assertThat(h(0.2f).confidence).isEqualTo(Confidence.LOW)
            assertThat(h(null).confidence).isNull()
        }

        @Test
        fun `a type the person chose has no confidence dot`() {
            val h = ExtractedPresenter.present(doc("receipt", typeConfidence = 0.9f, familySource = FamilySource.USER), emptyList()).header
            assertThat(h.confidence).isNull()
        }
    }

    @Nested
    @DisplayName("Sections per family")
    inner class Families {

        private val letterFields = {
            listOf(
                field("Receiver Name", "Erika Mustermann", "addressee"),
                field("Sender Name", "Nordlicht", "sender"),
                field("Subject", "Zahlungserinnerung", "subject"),
                field("Deadline", "15.10.2026", "due_date"),
                field("Amount", "64,98 €", "total"),
                field("IBAN", "DE89", "iban"),
                field("Invoice Number", "R-1", "invoice_no"),
                field("Customer Number", "K-9", "customer_no"),
                field("Document Date", "01.10.2026", "letter_date"),
                field("Original Due Date", "01.09.2026", "original_due_date"),
            )
        }

        @Test
        fun `a letter-like family reads recipient, sender, subject, action, references, dates`() {
            for (family in listOf("official_letter", "invoice_bill", "statement", "contract_policy", "medical")) {
                val p = present(letterFields(), type = family)
                assertThat(p.kinds()).containsExactly(
                    SectionKind.RECIPIENT_BLOCK, SectionKind.SENDER_BLOCK, SectionKind.TEXT, SectionKind.ACTION,
                    SectionKind.REFERENCES, SectionKind.DATES,
                ).inOrder()
            }
        }

        @Test
        fun `within a letter's sections the spec's slot order is kept`() {
            val p = present(letterFields(), type = "invoice_bill")
            assertThat(p.slotsIn(SectionKind.ACTION)).containsExactly("due_date", "total", "iban").inOrder()
            assertThat(p.slotsIn(SectionKind.REFERENCES)).containsExactly("invoice_no", "customer_no").inOrder()
            assertThat(p.slotsIn(SectionKind.DATES)).containsExactly("letter_date", "original_due_date").inOrder()
        }

        @Test
        fun `a legacy type id is presented as the family it stands for`() {
            val legacy = present(letterFields(), type = "reminder_dunning")
            assertThat(legacy.kinds()).isEqualTo(present(letterFields(), type = "invoice_bill").kinds())
            assertThat(present(letterFields(), type = "authority_tax").kinds().first()).isEqualTo(SectionKind.RECIPIENT_BLOCK)
        }

        @Test
        fun `a topic slot the spec does not name is placed by its kind`() {
            val p = present(
                listOf(field("Payment Reference", "ZV-1", "proof_reference"), field("Sent Date", "02.11.2026", "sent_date"), field("Amount Paid", "1 €", "proof_amount")),
                type = "official_letter",
            )
            assertThat(p.slotsIn(SectionKind.REFERENCES)).containsExactly("proof_reference")
            assertThat(p.slotsIn(SectionKind.DATES)).containsExactly("sent_date")
            assertThat(p.slotsIn(SectionKind.ACTION)).containsExactly("proof_amount")
        }

        @Test
        fun `a receipt reads merchant, total and payment, dates, references`() {
            val p = present(
                listOf(
                    field("Receipt Number", "B-77", "receipt_no"),
                    field("Document Date", "01.10.2026", "letter_date"),
                    field("Amount", "12,50 €", "total"),
                    field("IBAN", "DE89", "iban"),
                    field("Sender Name", "Bäckerei Korn", "sender"),
                ),
                type = "receipt",
            )

            assertThat(p.kinds()).containsExactly(SectionKind.MERCHANT, SectionKind.PAYMENT, SectionKind.DATES, SectionKind.REFERENCES).inOrder()
            assertThat(p.slotsIn(SectionKind.PAYMENT)).containsExactly("total", "iban").inOrder()
        }

        @Test
        fun `free form keeps people, text and the grouping of before`() {
            val p = present(
                listOf(
                    field("Phone", "0123", type = ExtractedFieldType.PHONE),
                    field("Date", "1.1.2026", type = ExtractedFieldType.DATE),
                    field("Reference Number", "42", type = ExtractedFieldType.REFERENCE_NUMBER),
                    field("Note", "call back", type = ExtractedFieldType.TEXT),
                    field("Amount", "1 €", "total"),
                ),
                type = "free_form",
            )

            assertThat(p.kinds()).containsExactly(
                SectionKind.PARTIES, SectionKind.TEXT, SectionKind.ACTION, SectionKind.DATES, SectionKind.REFERENCES,
            ).inOrder()
            assertThat(p.sections.single { it.kind == SectionKind.TEXT }.items.single().rows.single().fieldName).isEqualTo("Note")
        }

        @Test
        fun `an unknown or missing family is presented as free form, and a family with no address block draws no block`() {
            val rows = listOf(field("Sender Name", "N", "sender"), part("sender", "street", "Hauptstr."))
            assertThat(present(rows, type = "mystery").sections.none { it.items.any { i -> i is AddressBlock } }).isTrue()
            assertThat(present(rows, type = null).sections.none { it.items.any { i -> i is AddressBlock } }).isTrue()
            assertThat(present(rows, type = "invoice_bill").sections.any { it.items.any { i -> i is AddressBlock } }).isTrue()
        }

        @Test
        fun `empty sections are left out`() {
            val p = present(listOf(field("Amount", "1 €", "total")), type = "invoice_bill")
            assertThat(p.kinds()).containsExactly(SectionKind.ACTION)
        }

        @Test
        fun `changing the family re-presents the same rows under the new layout`() {
            val rows = listOf(field("Sender Name", "Korn", "sender"), field("Amount", "1 €", "total"))
            assertThat(present(rows, type = "invoice_bill").kinds()).containsExactly(SectionKind.SENDER_BLOCK, SectionKind.ACTION).inOrder()
            assertThat(present(rows, type = "receipt").kinds()).containsExactly(SectionKind.MERCHANT, SectionKind.PAYMENT).inOrder()
        }
    }

    @Nested
    @DisplayName("Address blocks")
    inner class Blocks {

        private fun block(p: ExtractedPresentation, role: PartyRole): AddressBlock =
            (p.check + p.sections.flatMap { it.items }).filterIsInstance<AddressBlock>().single { it.role == role }

        private fun AddressBlock.lineValues() = lines.map { l -> l.parts.map { it.fieldValue } }

        @Test
        fun `the block is the name row then the parts in print order, street with number and postcode with city on one line`() {
            val p = present(
                listOf(
                    field("Receiver Name", "Erika Mustermann", "addressee"),
                    part("addressee", "country", "DE"),
                    part("addressee", "city", "Berlin"),
                    part("addressee", "postcode", "10115"),
                    part("addressee", "house_number", "12"),
                    part("addressee", "street", "Hauptstraße"),
                    part("addressee", "organisation", "Mustermann GmbH"),
                    part("addressee", "raw", "Erika Mustermann\nHauptstraße 12\n10115 Berlin"),
                ),
                type = "invoice_bill",
            )

            val b = block(p, PartyRole.ADDRESSEE)
            assertThat(b.nameRow!!.fieldValue).isEqualTo("Erika Mustermann")
            assertThat(b.lineValues()).containsExactly(
                listOf("Mustermann GmbH"), listOf("Hauptstraße", "12"), listOf("10115", "Berlin"), listOf("DE"),
            ).inOrder()
            // The raw lines are kept for the actions but not drawn while parts exist.
            assertThat(b.shownRows.map { it.slotKey }).doesNotContain("addressee.raw")
            assertThat(b.rows.map { it.slotKey }).contains("addressee.raw")
        }

        @Test
        fun `a packstation address has a locker line in place of street and number`() {
            val p = present(
                listOf(
                    field("Receiver Name", "Erika", "addressee"),
                    part("addressee", "packstation", "Packstation 123"),
                    part("addressee", "postcode", "10115"),
                    part("addressee", "city", "Berlin"),
                ),
                type = "official_letter",
            )

            assertThat(block(p, PartyRole.ADDRESSEE).lineValues()).containsExactly(listOf("Packstation 123"), listOf("10115", "Berlin")).inOrder()
        }

        @Test
        fun `a post office box address has its own line`() {
            val p = present(
                listOf(
                    part("sender", "organisation", "Finanzamt"),
                    part("sender", "po_box", "Postfach 12 34"),
                    part("sender", "postcode", "10115"),
                    part("sender", "city", "Berlin"),
                ),
                type = "official_letter",
            )

            val b = block(p, PartyRole.SENDER)
            assertThat(b.nameRow).isNull()
            assertThat(b.lineValues()).containsExactly(listOf("Finanzamt"), listOf("Postfach 12 34"), listOf("10115", "Berlin")).inOrder()
        }

        @Test
        fun `a recipient name that repeats the name row is not drawn twice`() {
            val p = present(
                listOf(field("Receiver Name", "Erika Mustermann", "addressee"), part("addressee", "name", "erika mustermann"), part("addressee", "city", "Berlin")),
                type = "invoice_bill",
            )
            assertThat(block(p, PartyRole.ADDRESSEE).lineValues()).containsExactly(listOf("Berlin"))
        }

        @Test
        fun `a block with no part read draws the printed lines instead`() {
            val p = present(
                listOf(field("Sender Name", "Korn", "sender"), part("sender", "raw", "Korn\nIrgendwo")),
                type = "invoice_bill",
            )
            val b = block(p, PartyRole.SENDER)
            assertThat(b.lines).isEmpty()
            assertThat(b.shownRows.map { it.slotKey }).containsExactly("sender", "sender.raw").inOrder()
        }

        @Test
        fun `an uncertain part moves the whole block under Check these and is counted per part`() {
            val p = present(
                listOf(
                    field("Receiver Name", "Erika", "addressee"),
                    part("addressee", "street", "Hauptstr.", confidence = 0.4f),
                    part("addressee", "city", "Berlin", confidence = 0.5f),
                    part("addressee", "country", "DE"),
                ),
                type = "invoice_bill",
            )

            assertThat(p.check.single()).isInstanceOf(AddressBlock::class.java)
            assertThat(p.checkCount).isEqualTo(2)
            assertThat(p.sections).isEmpty()
        }

        @Test
        fun `a block with every part confirmed is settled and one line`() {
            val p = present(
                listOf(
                    field("Receiver Name", "Erika", "addressee", confirmed = true),
                    part("addressee", "city", "Berlin", confirmed = true),
                ),
                type = "invoice_bill",
            )
            assertThat(block(p, PartyRole.ADDRESSEE).isSettled).isTrue()
        }

        @Test
        fun `an ignored part leaves the block and goes to the footer`() {
            val p = present(
                listOf(field("Receiver Name", "Erika", "addressee"), part("addressee", "street", "Falsch", deleted = true), part("addressee", "city", "Berlin")),
                type = "invoice_bill",
            )
            assertThat(block(p, PartyRole.ADDRESSEE).lineValues()).containsExactly(listOf("Berlin"))
            assertThat(p.ignored.map { it.slotKey }).containsExactly("addressee.street")
        }
    }

    @Nested
    @DisplayName("Check first")
    inner class CheckFirst {

        @Test
        fun `uncertain fields are listed first, out of their sections, and counted`() {
            val p = present(
                listOf(
                    field("Amount", "64,98 €", "total", confidence = 0.4f),
                    field("Deadline", "15.10.2026", "due_date", confidence = 0.9f),
                    field("Invoice Number", "R-1", "invoice_no", confidence = 0.6f),
                    field("IBAN", "DE89", "iban"),
                ),
                type = "invoice_bill",
            )

            assertThat(p.check.flatMap { it.rows }.map { it.slotKey }).containsExactly("total", "invoice_no").inOrder()
            assertThat(p.checkCount).isEqualTo(2)
            assertThat(p.slotsIn(SectionKind.ACTION)).containsExactly("due_date", "iban").inOrder()
            assertThat(p.kinds()).doesNotContain(SectionKind.REFERENCES)
        }

        @Test
        fun `a value that changed under a person's confirmation is checked even though it is theirs`() {
            val changed = field("Amount", "70 €", "total", source = ValueSource.USER, confirmed = true)
                .copy(hasUnreviewedMachineChange = true, machineValue = "75 €")
            val p = present(listOf(changed), type = "invoice_bill")
            assertThat(p.check.single().rows.single().slotKey).isEqualTo("total")
        }

        @Test
        fun `a visible uncertain extra is checked too, a very unsure one stays behind Show all`() {
            val p = present(listOf(extra("Tarif", 0.6f), extra("Zaehler", 0.4f), extra("Sicher", 0.9f)), type = "invoice_bill")
            assertThat(p.check.flatMap { it.rows }.map { it.fieldName }).containsExactly("Tarif")
            assertThat(p.extras.map { it.fieldName }).containsExactly("Sicher")
            assertThat(p.hiddenExtras).isEqualTo(1)
        }

        @Test
        fun `nothing uncertain means no check group`() {
            assertThat(present(listOf(field("Amount", "1 €", "total"))).check).isEmpty()
        }
    }

    @Nested
    @DisplayName("The review button")
    inner class ReviewButton {

        @Test
        fun `while uncertain fields remain it confirms the confident ones, with their count`() {
            val p = present(
                listOf(
                    field("Amount", "1 €", "total", confidence = 0.4f),
                    field("IBAN", "DE89", "iban"),
                    field("Reference", "R", "reference"),
                    field("Deadline", "1.1.2027", "due_date", confirmed = true),
                ),
            )
            assertThat(p.review.mode).isEqualTo(ConfirmMode.CONFIDENT)
            assertThat(p.review.confidentOpen).isEqualTo(2)
            assertThat(p.review.uncertain).isEqualTo(1)
        }

        @Test
        fun `once nothing is uncertain it confirms all`() {
            val p = present(listOf(field("Amount", "1 €", "total"), field("IBAN", "DE89", "iban")))
            assertThat(p.review.mode).isEqualTo(ConfirmMode.ALL)
            assertThat(p.review.open).isEqualTo(2)
        }

        @Test
        fun `with nothing open there is no button, and ignored rows are not open`() {
            val p = present(listOf(field("Amount", "1 €", "total", confirmed = true), field("IBAN", "x", "iban", deleted = true)))
            assertThat(p.review.mode).isEqualTo(ConfirmMode.NONE)
        }

        @Test
        fun `only uncertain fields open means no confident ones to confirm`() {
            val p = present(listOf(field("Amount", "1 €", "total", confidence = 0.4f)))
            assertThat(p.review.mode).isEqualTo(ConfirmMode.NONE)
        }

        @Test
        fun `an address block's unseen raw row is confirmed with the block and counts as confident`() {
            val p = present(listOf(field("Sender Name", "N", "sender"), part("sender", "city", "Berlin"), part("sender", "raw", "N\nBerlin")), type = "invoice_bill")
            assertThat(p.review.mode).isEqualTo(ConfirmMode.ALL)
            assertThat(p.review.open).isEqualTo(3)
        }
    }

    @Nested
    @DisplayName("Ignored footer")
    inner class IgnoredFooter {

        @Test
        fun `ignored rows are collected apart and never drawn in the sections`() {
            val p = present(
                listOf(field("Amount", "1 €", "total"), field("IBAN", "DE89", "iban", deleted = true), extra("Gone").copy(deletedByUser = true)),
                type = "invoice_bill",
            )

            assertThat(p.ignored.map { it.fieldName }).containsExactly("IBAN", "Gone")
            assertThat(p.slotsIn(SectionKind.ACTION)).containsExactly("total")
            assertThat(p.extraCount).isEqualTo(0)
        }

        @Test
        fun `an ignored row is not uncertain and not counted as open`() {
            val p = present(listOf(field("Amount", "1 €", "total", confidence = 0.2f, deleted = true)))
            assertThat(p.check).isEmpty()
            assertThat(p.review.uncertain).isEqualTo(0)
            assertThat(p.ignored).hasSize(1)
        }
    }

    @Nested
    @DisplayName("Other details")
    inner class Extras {

        @Test
        fun `extras are kept out of the sections and keep the AI's label`() {
            val p = present(listOf(field("Amount", "1 €", "total"), extra("Zaehlernummer"), extra("Tarif")))

            assertThat(p.sections.flatMap { it.items }.flatMap { it.rows }.map { it.fieldName }).containsExactly("Amount")
            assertThat(p.extras.map { it.fieldName }).containsExactly("Zaehlernummer", "Tarif").inOrder()
            assertThat(p.hiddenExtras).isEqualTo(0)
            assertThat(p.extraCount).isEqualTo(2)
        }

        @Test
        fun `very unsure extras are hidden behind Show all and counted`() {
            val fields = listOf(extra("Sure", 0.9f), extra("Unsure", 0.4f), extra("Fuzzy quote", 0.45f))

            val collapsed = present(fields)
            assertThat(collapsed.extras.map { it.fieldName }).containsExactly("Sure")
            assertThat(collapsed.hiddenExtras).isEqualTo(2)
            assertThat(collapsed.extraCount).isEqualTo(3)

            val all = present(fields, showAll = true)
            assertThat(all.hiddenExtras).isEqualTo(0)
            assertThat(all.extras.map { it.fieldName } + all.check.flatMap { it.rows }.map { it.fieldName })
                .containsExactly("Sure", "Unsure", "Fuzzy quote")
        }

        @Test
        fun `an extra the user owns or confirmed is never hidden`() {
            val p = present(listOf(extra("Mine", 0.2f, source = ValueSource.USER), extra("Confirmed", 0.2f, confirmed = true)))
            assertThat(p.extras.map { it.fieldName }).containsExactly("Mine", "Confirmed")
            assertThat(p.hiddenExtras).isEqualTo(0)
        }
    }
}
